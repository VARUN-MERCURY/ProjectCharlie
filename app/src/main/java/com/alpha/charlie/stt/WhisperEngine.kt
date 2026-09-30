package com.alpha.charlie.stt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Kotlin wrapper around whisper.cpp via JNI.
 * Loads/unloads the whisper model and transcribes audio buffers.
 *
 * Thread safety: all heavy ops run on Dispatchers.IO.
 * Memory: call [unload] to free the model (~200-270 MB) when done.
 */
class WhisperEngine {

    companion object {
        init {
            System.loadLibrary("charlie")
        }
    }

    private var contextPtr: Long = 0L

    /** Load whisper model from file. Returns true on success. */
    suspend fun load(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        if (contextPtr != 0L) unload()
        contextPtr = nativeInit(modelPath)
        contextPtr != 0L
    }

    /** Transcribe 16kHz mono float PCM samples to text. */
    suspend fun transcribe(audioSamples: FloatArray): String = withContext(Dispatchers.IO) {
        if (contextPtr == 0L) return@withContext ""
        nativeTranscribe(contextPtr, audioSamples)
    }

    /** Free the whisper model from memory. */
    fun unload() {
        if (contextPtr != 0L) {
            nativeFree(contextPtr)
            contextPtr = 0L
        }
    }

    fun isLoaded(): Boolean = contextPtr != 0L

    // ---- JNI declarations (implemented in charlie-whisper.cpp) ----
    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribe(contextPtr: Long, audioData: FloatArray): String
    private external fun nativeFree(contextPtr: Long)
}
