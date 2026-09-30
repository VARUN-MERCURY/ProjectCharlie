package com.alpha.charlie.brain

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.alpha.charlie.audio.AudioCapture
import com.alpha.charlie.llm.LlamaEngine
import com.alpha.charlie.memory.MemoryManager
import com.alpha.charlie.stt.WhisperEngine
import com.alpha.charlie.tts.CharlieTts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * The brain of Project Charlie — optimized for real-time response.
 *
 * SPEED ARCHITECTURE:
 *   - Both whisper + LLM are PRE-LOADED at startup (zero load time)
 *   - Whisper is SHARED with wake word detector (one instance, ~273 MB)
 *   - Simple saves use REGEX extraction (skip LLM entirely → instant)
 *   - Single-fact queries skip LLM too (direct answer)
 *   - Wake word transcription can contain inline commands → skip recording
 *   - No model unload/reload between calls
 */
class CharlieOrchestrator(
    private val context: Context,
    private val memoryManager: MemoryManager,
    val whisper: WhisperEngine,         // shared with WakeWordDetector — pre-loaded
) {
    companion object {
        private const val TAG = "CharlieOrchestrator"

        private val STOP_WORDS = setOf(
            "the", "is", "at", "which", "on", "a", "an", "and", "or", "but",
            "in", "with", "to", "for", "of", "not", "no", "can", "had", "has",
            "was", "were", "been", "being", "have", "do", "does", "did", "will",
            "would", "could", "should", "may", "might", "shall", "this", "that",
            "what", "who", "how", "when", "where", "why", "are", "you", "your",
            "my", "me", "tell", "know", "about", "charlie", "hey"
        )

        /** Short acks after saving/processing */
        private val DONE_ACKS = listOf(
            "Copy that.",
            "Roger that.",
            "Solid copy.",
            "Noted.",
            "10-4."
        )
    }

    // Own coroutine scope — survives UI recomposition
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Engines — LLM is owned here, whisper is shared
    private val llama = LlamaEngine()
    private val tts = CharlieTts(context)
    private val audioCapture = AudioCapture()

    // UI state
    private val _state = MutableStateFlow(CharlieState.IDLE)
    val state: StateFlow<CharlieState> = _state

    private val _statusMessage = MutableStateFlow("Say \"Charlie\" or tap the mic")
    val statusMessage: StateFlow<String> = _statusMessage

    private val _conversationLog = MutableStateFlow<List<ConversationEntry>>(emptyList())
    val conversationLog: StateFlow<List<ConversationEntry>> = _conversationLog

    val isRecording: StateFlow<Boolean> = audioCapture.isRecording

    // Model paths
    private val modelsDir get() = File(context.filesDir, "models")
    private val whisperModelPath get() = File(modelsDir, "ggml-tiny.en.bin").absolutePath
    private val llamaModelPath get() = File(modelsDir, "qwen2.5-0.5b-instruct-q4_k_m.gguf").absolutePath

    fun hasModels(): Boolean {
        return File(whisperModelPath).exists() && File(llamaModelPath).exists()
    }

    fun getModelStatus(): String = buildString {
        val w = File(whisperModelPath).exists()
        val l = File(llamaModelPath).exists()
        append("Whisper: ${if (w) "✓ Ready" else "✗ Missing"}\n")
        append("LLM:     ${if (l) "✓ Ready" else "✗ Missing"}\n")
        append("Path: ${modelsDir.absolutePath}")
    }

    /**
     * Pre-load BOTH models into RAM at startup.
     * After this, all interactions have ZERO model loading time.
     * Total RAM: whisper ~273 MB + LLM ~500 MB = ~773 MB
     */
    suspend fun preloadModels() {
        if (!whisper.isLoaded()) {
            Log.i(TAG, "Pre-loading whisper...")
            whisper.load(whisperModelPath)
        }
        // LLM not pre-loaded — all responses use regex/direct lookup (no LLM in hot path)
        Log.i(TAG, "Whisper pre-loaded and HOT")
    }

    // ═══════════════════════════════════════════
    // Trigger: UI mic button tap
    // ═══════════════════════════════════════════

    fun startListening() {
        if (_state.value != CharlieState.IDLE && _state.value != CharlieState.ERROR) return

        _state.value = CharlieState.LISTENING
        _statusMessage.value = "Listening..."

        scope.launch {
            audioCapture.startRecording { audioSamples ->
                processFromAudio(audioSamples)
            }
        }
    }

    fun stopListening() {
        audioCapture.stopRecording()
    }

    // ═══════════════════════════════════════════
    // Trigger: Background wake word detection
    // ═══════════════════════════════════════════

    /**
     * Called by CharlieService with the full wake transcription text.
     * If the transcription contains a command after "charlie",
     * we process it INSTANTLY (no recording needed!).
     */
    fun startFromWakeWord(wakeTranscription: String = "") {
        if (_state.value != CharlieState.IDLE && _state.value != CharlieState.ERROR) return
        _state.value = CharlieState.TRANSCRIBING  // prevent race
        vibrate()

        scope.launch {
            val command = stripWakeWord(wakeTranscription)
            if (command.isNotBlank() && command.length > 3) {
                // FAST PATH: command was in the wake phrase — skip recording entirely!
                Log.i(TAG, "Instant command from wake phrase: \"$command\"")
                processCommand(command)
            } else {
                // User only said "Charlie" — need to record the command
                _state.value = CharlieState.LISTENING
                _statusMessage.value = "Listening..."

                audioCapture.startRecording { audioSamples ->
                    processFromAudio(audioSamples)
                }
            }
        }
    }

    private fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed", e)
        }
    }

    // ═══════════════════════════════════════════
    // Pipeline — split into audio + command phases
    // ═══════════════════════════════════════════

    /** Audio → transcription → command processing */
    private suspend fun processFromAudio(audioSamples: FloatArray) {
        try {
            _state.value = CharlieState.TRANSCRIBING
            _statusMessage.value = "Transcribing..."

            if (!whisper.isLoaded()) {
                if (!whisper.load(whisperModelPath)) {
                    fail("Failed to load whisper model.")
                    return
                }
            }

            val transcription = whisper.transcribe(audioSamples).trim()
            Log.i(TAG, "Transcription: \"$transcription\"")
            // DON'T unload whisper — keep it hot for next interaction

            if (transcription.isBlank() || transcription.length < 2) {
                _state.value = CharlieState.IDLE
                _statusMessage.value = "Didn't catch that. Try again."
                return
            }

            val cleanedText = stripWakeWord(transcription)
            if (cleanedText.isBlank()) {
                _state.value = CharlieState.IDLE
                _statusMessage.value = "Say \"Charlie\" or tap the mic"
                return
            }

            processCommand(cleanedText)

        } catch (e: CancellationException) {
            Log.i(TAG, "Pipeline cancelled")
            _state.value = CharlieState.IDLE
            _statusMessage.value = "Say \"Charlie\" or tap the mic"
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline error", e)
            fail("Error: ${e.message}")
        }
    }

    /** Intent → handler → response (TTS for queries only, saves skip TTS) */
    private suspend fun processCommand(text: String) {
        try {
            _state.value = CharlieState.THINKING
            _statusMessage.value = "Processing..."

            val intent = classifyIntent(text)
            val response = when (intent) {
                Intent.SAVE  -> handleSave(text)
                Intent.QUERY -> handleQuery(text)
                Intent.LIST  -> handleList()
                Intent.CLEAR -> handleClear()
            }

            _conversationLog.value += ConversationEntry(text, response)
            _statusMessage.value = response

            // Saves & clears: skip TTS (vibrate only) → instant
            // Queries & lists: speak the answer
            if (intent == Intent.QUERY || intent == Intent.LIST) {
                _state.value = CharlieState.SPEAKING
                tts.speak(response)
            } else {
                vibrate()
            }

            _state.value = CharlieState.IDLE
            _statusMessage.value = "Say \"Charlie\" or tap the mic"

        } catch (e: CancellationException) {
            Log.i(TAG, "Command processing cancelled")
            _state.value = CharlieState.IDLE
            _statusMessage.value = "Say \"Charlie\" or tap the mic"
        } catch (e: Exception) {
            Log.e(TAG, "Command processing error", e)
            fail("Error: ${e.message}")
        }
    }

    private fun stripWakeWord(text: String): String {
        val lower = text.lowercase().trim()
        val strippers = listOf(
            "charlie come in ", "charlie coming ", "charlie, come in ",
            "hey charlie ", "hey charlie, ", "yo charlie ",
            "charlie, ", "charlie "
        )
        for (prefix in strippers) {
            if (lower.startsWith(prefix)) {
                return text.substring(prefix.length).trim()
            }
        }
        // Handle trailing "charlie" with no command
        if (lower == "charlie" || lower == "charlie." || lower == "charlie!") return ""
        return text
    }

    // ═══════════════════════════════════════════
    // Intent Classification
    // ═══════════════════════════════════════════

    private enum class Intent { SAVE, QUERY, LIST, CLEAR }

    private fun classifyIntent(text: String): Intent {
        val lower = text.lowercase().trim()

        // List/clear — specific, check first
        if (lower.contains("list") && (lower.contains("everything") || lower.contains("all") || lower.contains("facts") || lower.contains("memory")))
            return Intent.LIST
        if ((lower.contains("clear") || lower.contains("wipe") || lower.contains("delete")) &&
            (lower.contains("memory") || lower.contains("everything") || lower.contains("all")))
            return Intent.CLEAR

        // Questions → always QUERY (prevents "what is my X" matching SAVE)
        if (lower.startsWith("what") || lower.startsWith("who") ||
            lower.startsWith("where") || lower.startsWith("when") ||
            lower.startsWith("how") || lower.startsWith("do ") ||
            lower.startsWith("does ") || lower.startsWith("is ") ||
            lower.startsWith("tell me") || lower.endsWith("?"))
            return Intent.QUERY

        // Save patterns
        if (lower.startsWith("remember") || lower.startsWith("save") ||
            lower.startsWith("note that") || lower.startsWith("don't forget") ||
            lower.startsWith("store") ||
            (lower.contains("my ") && lower.contains(" is ")) ||
            (lower.contains("i ") && (lower.contains(" like ") || lower.contains(" love "))))
            return Intent.SAVE

        return Intent.QUERY
    }

    // ═══════════════════════════════════════════
    // Intent Handlers — SPEED OPTIMIZED
    // ═══════════════════════════════════════════

    /**
     * FAST SAVE: tries regex extraction first (instant, no LLM).
     * Only falls back to LLM for complex patterns regex can't handle.
     */
    private suspend fun handleSave(text: String): String {
        val regexResult = tryRegexExtract(text)
        if (regexResult != null) {
            val (subject, value) = regexResult
            memoryManager.saveFact(rawText = text, subject = subject, value = value)
            Log.i(TAG, "Fast save: $subject = $value")
            return "Noted."
        }
        // No regex match — save raw text instantly (no LLM)
        memoryManager.saveFact(rawText = text, subject = "note", value = text)
        return "Noted."
    }

    /** Instant regex extraction for common patterns — no LLM needed. */
    private fun tryRegexExtract(text: String): Pair<String, String>? {
        // "my X is Y"
        Regex("(?:my |the )(.+?)\\s+is\\s+(.+)", RegexOption.IGNORE_CASE).find(text)?.let {
            return Pair(it.groupValues[1].trim(), it.groupValues[2].trim())
        }
        // "remember/save/note that X is Y"
        Regex("(?:remember|save|note|store)\\s+(?:that\\s+)?(.+?)\\s+is\\s+(.+)", RegexOption.IGNORE_CASE).find(text)?.let {
            return Pair(it.groupValues[1].trim(), it.groupValues[2].trim())
        }
        // "I like/love X"
        Regex("i\\s+(?:like|love)\\s+(.+)", RegexOption.IGNORE_CASE).find(text)?.let {
            return Pair("likes", it.groupValues[1].trim())
        }
        // "my X's name is Y" or "my X name is Y"
        Regex("my\\s+(.+?)(?:'s)?\\s+name\\s+is\\s+(.+)", RegexOption.IGNORE_CASE).find(text)?.let {
            return Pair("${it.groupValues[1].trim()}'s name", it.groupValues[2].trim())
        }
        return null
    }

    /**
     * FAST QUERY: single-fact lookups skip LLM entirely.
     * Only uses LLM when multiple facts need synthesizing.
     */
    private suspend fun handleQuery(text: String): String {
        val keywords = text.lowercase()
            .replace(Regex("[^a-z0-9 ]"), "")
            .split(" ")
            .filter { it.length > 2 && it !in STOP_WORDS }

        val facts = mutableListOf<com.alpha.charlie.memory.Fact>()
        for (keyword in keywords) {
            facts.addAll(memoryManager.searchFacts(keyword))
        }
        val uniqueFacts = facts.distinctBy { it.id }.take(5)

        if (uniqueFacts.isEmpty()) return "Nothing on that."
        if (uniqueFacts.size == 1) return "${uniqueFacts[0].value}."
        return uniqueFacts.joinToString(". ") { "${it.subject}: ${it.value}" }
    }

    private suspend fun handleList(): String {
        val facts = memoryManager.getAllFacts()
        return if (facts.isEmpty()) {
            "Memory empty."
        } else {
            facts.take(10).joinToString(". ") { "${it.subject}: ${it.value}" }
        }
    }

    private suspend fun handleClear(): String {
        val count = memoryManager.factCount()
        if (count == 0) return "Already empty."
        memoryManager.clearAll()
        return "Cleared."
    }

    // ═══════════════════════════════════════════

    private suspend fun ensureLlamaLoaded(): Boolean {
        if (llama.isLoaded()) return true
        _statusMessage.value = "Loading brain..."
        return llama.load(llamaModelPath, nThreads = 2)
    }

    private fun fail(message: String) {
        _state.value = CharlieState.ERROR
        _statusMessage.value = message
    }

    fun shutdown() {
        audioCapture.stopRecording()
        // Don't unload whisper — it's shared, service manages lifecycle
        llama.unload()
        tts.shutdown()
        scope.cancel()
    }
}

enum class CharlieState {
    IDLE,
    LISTENING,
    TRANSCRIBING,
    THINKING,
    SPEAKING,
    ERROR
}

data class ConversationEntry(
    val userText: String,
    val charlieResponse: String,
    val timestamp: Long = System.currentTimeMillis()
)
