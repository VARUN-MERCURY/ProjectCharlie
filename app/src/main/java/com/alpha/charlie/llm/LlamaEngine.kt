package com.alpha.charlie.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Kotlin wrapper around llama.cpp via JNI.
 * Loads/unloads a GGUF model and generates text completions.
 *
 * Memory: call [unload] to free the model (~450-550 MB) when done.
 */
class LlamaEngine {

    companion object {
        private var backendInitialized = false

        init {
            System.loadLibrary("charlie")
        }
    }

    private var enginePtr: Long = 0L

    /** Load GGUF model. Returns true on success. */
    suspend fun load(modelPath: String, nThreads: Int = 2): Boolean = withContext(Dispatchers.IO) {
        if (!backendInitialized) {
            nativeBackendInit()
            backendInitialized = true
        }
        if (enginePtr != 0L) unload()
        enginePtr = nativeInit(modelPath, nThreads)
        enginePtr != 0L
    }

    /** Generate a text completion from a prompt. */
    suspend fun generate(prompt: String, maxTokens: Int = 128): String = withContext(Dispatchers.IO) {
        if (enginePtr == 0L) return@withContext ""
        nativeGenerate(enginePtr, prompt, maxTokens)
    }

    /** Free the model from memory. */
    fun unload() {
        if (enginePtr != 0L) {
            nativeFree(enginePtr)
            enginePtr = 0L
        }
    }

    fun isLoaded(): Boolean = enginePtr != 0L

    // ---- JNI declarations (implemented in charlie-llama.cpp) ----
    private external fun nativeBackendInit()
    private external fun nativeInit(modelPath: String, nThreads: Int): Long
    private external fun nativeGenerate(enginePtr: Long, prompt: String, maxTokens: Int): String
    private external fun nativeFree(enginePtr: Long)
}
