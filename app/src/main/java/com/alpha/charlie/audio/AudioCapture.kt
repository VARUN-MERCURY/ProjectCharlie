package com.alpha.charlie.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Records 16kHz mono PCM float audio from the microphone.
 * Automatically stops on silence or max duration.
 */
class AudioCapture {

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_FLOAT

        // Silence detection params — tuned for snappy response
        private const val SILENCE_THRESHOLD = 0.01f
        private const val SILENCE_DURATION_MS = 400L
        private const val MAX_RECORDING_MS = 5000L
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    /**
     * Start recording. When silence is detected (or max time reached),
     * calls [onAudioCaptured] with the full float PCM buffer.
     */
    @SuppressLint("MissingPermission")
    suspend fun startRecording(onAudioCaptured: suspend (FloatArray) -> Unit) = coroutineScope {
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING),
            SAMPLE_RATE * 2  // At least 2 seconds buffer
        )

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            bufferSize * 4   // Float = 4 bytes per sample
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return@coroutineScope
        }

        audioRecord = recorder
        recorder.startRecording()
        _isRecording.value = true

        recordingJob = launch(Dispatchers.IO) {
            val chunkSize = SAMPLE_RATE / 10   // 100ms chunks
            val buffer = FloatArray(chunkSize)
            val allSamples = mutableListOf<Float>()
            var silenceStart = 0L
            val recordingStart = System.currentTimeMillis()
            var hasSpeech = false

            while (isActive && _isRecording.value) {
                val read = recorder.read(buffer, 0, chunkSize, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue

                // Accumulate samples
                for (i in 0 until read) {
                    allSamples.add(buffer[i])
                }

                // Calculate energy of this chunk
                val energy = buffer.take(read).map { abs(it) }.average().toFloat()

                if (energy > SILENCE_THRESHOLD) {
                    hasSpeech = true
                    silenceStart = 0L
                } else if (hasSpeech) {
                    // Only start silence timer after we've heard speech
                    if (silenceStart == 0L) {
                        silenceStart = System.currentTimeMillis()
                    }
                    if (System.currentTimeMillis() - silenceStart > SILENCE_DURATION_MS) {
                        break  // Silence after speech → done
                    }
                }

                // Max recording safeguard
                if (System.currentTimeMillis() - recordingStart > MAX_RECORDING_MS) {
                    break
                }
            }

            releaseRecorder()

            // Only callback if we captured meaningful audio
            if (allSamples.size > SAMPLE_RATE / 2) {   // At least 0.5s
                onAudioCaptured(allSamples.toFloatArray())
            }
        }

        recordingJob?.join()
    }

    /** Stop recording immediately (recording loop exits within ~100ms). */
    fun stopRecording() {
        _isRecording.value = false
        // Don't cancel recordingJob — let the loop exit naturally
        // so that onAudioCaptured still runs with collected audio
        releaseRecorder()
    }

    private fun releaseRecorder() {
        _isRecording.value = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }
}
