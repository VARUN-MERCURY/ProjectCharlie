package com.alpha.charlie.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Offline Text-to-Speech using Android's built-in TTS engine.
 * Configured for a deep, gruff "Army Cop" voice via pitch/rate tuning.
 * Zero additional RAM cost — runs in the system TTS process.
 */
class CharlieTts(context: Context) {

    private var tts: TextToSpeech? = null
    private var isReady = false

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureTts()
                isReady = true
            }
        }
    }

    private fun configureTts() {
        val engine = tts ?: return

        engine.language = Locale.US
        engine.setPitch(0.7f)         // Lower pitch → deeper voice
        engine.setSpeechRate(1.2f)    // Fast rate for snappy responses

        // Try to find a deep male voice (offline)
        val maleVoice = engine.voices?.firstOrNull { voice ->
            voice.locale == Locale.US &&
            !voice.isNetworkConnectionRequired &&
            (voice.name.contains("male", ignoreCase = true) ||
             voice.name.contains("dfki", ignoreCase = true))     // Some TTS have dfki male voices
        }
        maleVoice?.let { engine.voice = it }
    }

    /** Speak text and suspend until speech finishes. */
    suspend fun speak(text: String): Unit = suspendCancellableCoroutine { cont ->
        if (!isReady || tts == null || text.isBlank()) {
            if (cont.isActive) cont.resume(Unit)
            return@suspendCancellableCoroutine
        }

        val utteranceId = UUID.randomUUID().toString()

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                if (id == utteranceId && cont.isActive) cont.resume(Unit)
            }
            @Deprecated("Deprecated in API")
            override fun onError(id: String?) {
                if (id == utteranceId && cont.isActive) cont.resume(Unit)
            }
        })

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)

        cont.invokeOnCancellation {
            tts?.stop()
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
