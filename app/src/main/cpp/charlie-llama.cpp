/**
 * Project Charlie — Llama JNI Bridge
 *
 * Bridges Kotlin LlamaEngine <-> llama.cpp C API.
 * Handles: model loading/unloading, text generation with sampling.
 *
 * Uses the core llama.h API only (no common/ dependency).
 */

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"

#define TAG "CharlieLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Holds both model and context pointers
struct charlie_llm {
    llama_model   *model;
    llama_context *ctx;
};

// Inline batch helper (avoids dependency on common/)
static void batch_add(struct llama_batch &batch, llama_token id, llama_pos pos, bool logits) {
    batch.token    [batch.n_tokens] = id;
    batch.pos      [batch.n_tokens] = pos;
    batch.n_seq_id [batch.n_tokens] = 1;
    batch.seq_id   [batch.n_tokens][0] = 0;
    batch.logits   [batch.n_tokens] = logits ? 1 : 0;
    batch.n_tokens++;
}

static void batch_clear(struct llama_batch &batch) {
    batch.n_tokens = 0;
}

extern "C" {

/**
 * Initialize llama backend (call once).
 */
JNIEXPORT void JNICALL
Java_com_alpha_charlie_llm_LlamaEngine_nativeBackendInit(
        JNIEnv * /* env */, jobject /* thiz */) {
    llama_backend_init();
    LOGI("Llama backend initialized");
}

/**
 * Load GGUF model from file.
 * Returns pointer to charlie_llm struct (cast to jlong), or 0 on failure.
 */
JNIEXPORT jlong JNICALL
Java_com_alpha_charlie_llm_LlamaEngine_nativeInit(
        JNIEnv *env, jobject /* thiz */, jstring model_path, jint n_threads) {

    const char *path = env->GetStringUTFChars(model_path, nullptr);
    if (!path) {
        LOGE("Failed to get model path");
        return 0;
    }

    LOGI("Loading llama model: %s (threads=%d)", path, n_threads);

    // Model params
    llama_model_params model_params = llama_model_default_params();
    model_params.use_mmap = true;   // Memory-map for efficient loading

    llama_model *model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);

    if (!model) {
        LOGE("Failed to load llama model");
        return 0;
    }

    // Context params
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx           = 512;          // Reduced: short prompts, faster KV cache
    ctx_params.n_threads       = n_threads;    // 2 A76 cores
    ctx_params.n_threads_batch = n_threads;
    ctx_params.n_batch         = 512;
    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;

    llama_context *ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGE("Failed to create llama context");
        llama_model_free(model);
        return 0;
    }

    auto *llm = new charlie_llm{model, ctx};
    LOGI("Llama model loaded successfully");
    return reinterpret_cast<jlong>(llm);
}

/**
 * Generate text from a prompt string.
 * Returns the generated text (excluding the prompt).
 */
JNIEXPORT jstring JNICALL
Java_com_alpha_charlie_llm_LlamaEngine_nativeGenerate(
        JNIEnv *env, jobject /* thiz */, jlong llm_ptr, jstring prompt_str, jint max_tokens) {

    auto *llm = reinterpret_cast<charlie_llm *>(llm_ptr);
    if (!llm || !llm->ctx || !llm->model) {
        LOGE("LLM context is null");
        return env->NewStringUTF("");
    }

    const char *prompt_cstr = env->GetStringUTFChars(prompt_str, nullptr);
    if (!prompt_cstr) {
        return env->NewStringUTF("");
    }
    std::string prompt(prompt_cstr);
    env->ReleaseStringUTFChars(prompt_str, prompt_cstr);

    LOGI("Generating from prompt (%zu chars), max_tokens=%d", prompt.size(), max_tokens);

    const llama_vocab *vocab = llama_model_get_vocab(llm->model);

    // ---- Tokenize prompt ----
    int n_prompt_max = prompt.size() + 256;
    std::vector<llama_token> tokens(n_prompt_max);
    int n_tokens = llama_tokenize(
        vocab,
        prompt.c_str(),
        prompt.size(),
        tokens.data(),
        n_prompt_max,
        true,   // add_special (BOS)
        true    // parse_special (handle <|im_start|> etc.)
    );

    if (n_tokens < 0) {
        // Buffer too small, resize and retry
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(
            vocab, prompt.c_str(), prompt.size(),
            tokens.data(), tokens.size(),
            true, true
        );
    }
    if (n_tokens <= 0) {
        LOGE("Tokenization failed");
        return env->NewStringUTF("");
    }
    tokens.resize(n_tokens);

    LOGI("Prompt tokenized: %d tokens", n_tokens);

    // ---- Clear KV cache ----
    llama_memory_clear(llama_get_memory(llm->ctx), true);

    // ---- Process prompt in batch ----
    struct llama_batch batch = llama_batch_init(n_tokens + max_tokens, 0, 1);

    for (int i = 0; i < n_tokens; i++) {
        batch_add(batch, tokens[i], i, i == n_tokens - 1);
    }

    if (llama_decode(llm->ctx, batch) != 0) {
        LOGE("llama_decode failed for prompt");
        llama_batch_free(batch);
        return env->NewStringUTF("");
    }

    // ---- Set up sampler chain ----
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(42));

    // ---- Generate tokens one-by-one ----
    std::string result;
    int n_cur = n_tokens;

    for (int i = 0; i < max_tokens; i++) {
        llama_token new_token = llama_sampler_sample(sampler, llm->ctx, -1);

        // Check for end-of-generation
        if (llama_vocab_is_eog(vocab, new_token)) {
            LOGI("Hit EOG at token %d", i);
            break;
        }

        // Convert token to text
        char buf[256];
        int n = llama_token_to_piece(vocab, new_token, buf, sizeof(buf) - 1, 0, true);
        if (n > 0) {
            buf[n] = '\0';
            result.append(buf, n);
        }

        // Decode next token
        batch_clear(batch);
        batch_add(batch, new_token, n_cur, true);
        n_cur++;

        if (llama_decode(llm->ctx, batch) != 0) {
            LOGE("llama_decode failed at generation step %d", i);
            break;
        }
    }

    llama_sampler_free(sampler);
    llama_batch_free(batch);

    LOGI("Generated %zu chars: \"%.80s...\"", result.size(), result.c_str());
    return env->NewStringUTF(result.c_str());
}

/**
 * Free model and context, release all memory.
 */
JNIEXPORT void JNICALL
Java_com_alpha_charlie_llm_LlamaEngine_nativeFree(
        JNIEnv * /* env */, jobject /* thiz */, jlong llm_ptr) {

    auto *llm = reinterpret_cast<charlie_llm *>(llm_ptr);
    if (llm) {
        if (llm->ctx)   llama_free(llm->ctx);
        if (llm->model) llama_model_free(llm->model);
        delete llm;
        LOGI("Llama model freed");
    }
}

} // extern "C"
