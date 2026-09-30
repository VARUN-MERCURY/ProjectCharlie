package com.alpha.charlie.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.alpha.charlie.stt.WhisperEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/**
 * Always-on wake word detector.
 *
 * Uses a SHARED WhisperEngine (pre-loaded by service) to avoid
 * duplicate model RAM. Passes the full transcription text back
 * so the orchestrator can extract inline commands.
 */
class WakeWordDetector(
    private val whisper: WhisperEngine,          // shared — pre-loaded
    private val whisperModelPath: String          // fallback path if not loaded
) {
    companion object {
        private const val TAG = "WakeWordDetector"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_FLOAT

        private const val ENERGY_THRESHOLD = 0.008f
        private const val WAKE_BUFFER_SECONDS = 3.0f
        private const val WAKE_BUFFER_SIZE = (SAMPLE_RATE * WAKE_BUFFER_SECONDS).toInt()
        private const val TRANSCRIBE_COOLDOWN_MS = 800L

        private val WAKE_PHRASES = listOf(
            "charlie", "charlie come in", "charlie coming",
            "hey charlie", "yo charlie", "charli", "charly",
            "charlie,", "sharli", "sharlie", "sharly"
        )
    }

    private var audioRecord: AudioRecord? = null
    private var monitorJob: Job? = null

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring

    private val _isPaused = MutableStateFlow(false)

    /**
     * Start continuous background monitoring.
     * [onWakeWordDetected] receives the full transcription text
     * (may contain command after wake word, e.g. "Charlie remember my birthday").
     */
    @SuppressLint("MissingPermission")
    fun start(parentScope: CoroutineScope, onWakeWordDetected: suspend (String) -> Unit) {
        if (_isMonitoring.value) return
        monitorJob?.cancel()

        monitorJob = parentScope.launch(Dispatchers.IO) {
            Log.i(TAG, "Wake word monitoring started")

            // Ensure whisper is loaded (normally pre-loaded by service)
            if (!whisper.isLoaded()) {
                Log.i(TAG, "Loading whisper for wake word detection...")
                if (!whisper.load(whisperModelPath)) {
                    Log.e(TAG, "Failed to load whisper model")
                    return@launch
                }
            }

            _isMonitoring.value = true

            val bufferSize = maxOf(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING),
                SAMPLE_RATE * 2
            )

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, CHANNEL, ENCODING,
                bufferSize * 4
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                recorder.release()
                _isMonitoring.value = false
                return@launch
            }

            audioRecord = recorder
            recorder.startRecording()

            val chunkSize = SAMPLE_RATE / 10
            val readBuffer = FloatArray(chunkSize)
            val circularBuffer = FloatArray(WAKE_BUFFER_SIZE)
            var circularPos = 0
            var totalSamplesWritten = 0L

            var speechStart = 0L
            var silenceStart = 0L
            var lastTranscribeTime = 0L
            var speechChunksInWindow = 0

            try {
                while (isActive) {
                    if (_isPaused.value) {
                        delay(200)
                        continue
                    }

                    val read = recorder.read(readBuffer, 0, chunkSize, AudioRecord.READ_BLOCKING)
                    if (read <= 0) continue

                    for (i in 0 until read) {
                        circularBuffer[circularPos % WAKE_BUFFER_SIZE] = readBuffer[i]
                        circularPos = (circularPos + 1) % WAKE_BUFFER_SIZE
                    }
                    totalSamplesWritten += read

                    val energy = readBuffer.take(read).map { abs(it) }.average().toFloat()

                    if (energy > ENERGY_THRESHOLD) {
                        if (speechStart == 0L) {
                            speechStart = System.currentTimeMillis()
                            Log.d(TAG, "Speech detected, energy=$energy")
                        }
                        silenceStart = 0L
                        speechChunksInWindow++
                    } else if (speechStart > 0L) {
                        if (silenceStart == 0L) {
                            silenceStart = System.currentTimeMillis()
                        }

                        // 500ms silence after speech → transcribe
                        if (System.currentTimeMillis() - silenceStart > 400) {
                            val now = System.currentTimeMillis()
                            val speechDuration = silenceStart - speechStart

                            if (speechChunksInWindow >= 3 && speechDuration > 300
                                && now - lastTranscribeTime > TRANSCRIBE_COOLDOWN_MS) {

                                lastTranscribeTime = now
                                Log.d(TAG, "Speech ended after ${speechDuration}ms, transcribing...")

                                val audioLen = minOf(totalSamplesWritten.toInt(), WAKE_BUFFER_SIZE)
                                val audio = extractFromCircularBuffer(circularBuffer, circularPos, audioLen)

                                val text = try {
                                    whisper.transcribe(audio)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Wake word transcription failed", e)
                                    ""
                                }

                                Log.d(TAG, "Wake check: \"$text\"")

                                if (containsWakeWord(text)) {
                                    Log.i(TAG, "WAKE WORD DETECTED: \"$text\"")
                                    _isPaused.value = true
                                    onWakeWordDetected(text.trim())
                                }
                            }

                            speechStart = 0L
                            silenceStart = 0L
                            speechChunksInWindow = 0
                        }
                    }
                }
            } finally {
                try { recorder.stop() } catch (_: Exception) {}
                try { recorder.release() } catch (_: Exception) {}
                audioRecord = null
                _isMonitoring.value = false
                Log.i(TAG, "Wake word monitoring stopped")
            }
        }
    }

    fun pause() {
        _isPaused.value = true
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    @SuppressLint("MissingPermission")
    fun resume(parentScope: CoroutineScope, onWakeWordDetected: suspend (String) -> Unit) {
        _isPaused.value = false
        stop()
        start(parentScope, onWakeWordDetected)
    }

    fun stop() {
        _isMonitoring.value = false
        _isPaused.value = false
        monitorJob?.cancel()
        monitorJob = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    private fun containsWakeWord(text: String): Boolean {
        val lower = text.lowercase().trim()
        return WAKE_PHRASES.any { lower.contains(it) }
    }

    private fun extractFromCircularBuffer(
        buffer: FloatArray, writePos: Int, length: Int
    ): FloatArray {
        val result = FloatArray(length)
        val startPos = (writePos - length + buffer.size) % buffer.size
        for (i in 0 until length) {
            result[i] = buffer[(startPos + i) % buffer.size]
        }
        return result
    }
}
