package com.alpha.charlie.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.alpha.charlie.MainActivity
import com.alpha.charlie.R
import com.alpha.charlie.audio.WakeWordDetector
import com.alpha.charlie.brain.CharlieOrchestrator
import com.alpha.charlie.brain.CharlieState
import com.alpha.charlie.memory.FactDatabase
import com.alpha.charlie.memory.MemoryManager
import com.alpha.charlie.stt.WhisperEngine
import kotlinx.coroutines.*
import java.io.File

/**
 * Foreground service that owns the orchestrator and wake word detector.
 *
 * SPEED ARCHITECTURE:
 *   - Creates ONE shared WhisperEngine (used by both detector + orchestrator)
 *   - Pre-loads whisper + LLM at startup (zero load time during interactions)
 *   - Passes wake word transcription text to orchestrator for instant commands
 */
class CharlieService : Service() {

    companion object {
        const val CHANNEL_ID = "charlie_service_channel"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "CharlieService"
        const val ACTION_STOP = "com.alpha.charlie.STOP"
    }

    private val binder = CharlieBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Shared whisper engine — one instance, used by both detector and orchestrator
    private val sharedWhisper = WhisperEngine()

    // Core components
    lateinit var orchestrator: CharlieOrchestrator
        private set
    private var wakeWordDetector: WakeWordDetector? = null
    private var isInitialized = false

    inner class CharlieBinder : Binder() {
        val service: CharlieService get() = this@CharlieService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initComponents()
    }

    private fun initComponents() {
        if (isInitialized) return

        val db = FactDatabase.getDatabase(this)
        val memoryManager = MemoryManager(db.factDao())

        // Orchestrator gets the shared whisper engine
        orchestrator = CharlieOrchestrator(this, memoryManager, sharedWhisper)

        // Wake word detector shares the same whisper engine
        val modelsDir = File(filesDir, "models")
        val whisperPath = File(modelsDir, "ggml-tiny.en.bin").absolutePath
        wakeWordDetector = WakeWordDetector(sharedWhisper, whisperPath)

        isInitialized = true
        Log.i(TAG, "Components initialized (shared whisper)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopWakeWordDetection()
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification("Loading models...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireWakeLock()

        // Pre-load all models, THEN start wake word detection
        serviceScope.launch {
            orchestrator.preloadModels()    // loads whisper + LLM
            startWakeWordDetection()        // whisper already hot
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // ─── Wake Word Detection ─────────────────────────────

    private fun startWakeWordDetection() {
        if (!orchestrator.hasModels()) {
            Log.w(TAG, "Models not found, skipping wake word detection")
            return
        }

        wakeWordDetector?.start(serviceScope) { transcription ->
            onWakeWordDetected(transcription)
        }
        updateNotification("Listening for \"Charlie\"...")
        Log.i(TAG, "Wake word detection started")
    }

    private fun stopWakeWordDetection() {
        wakeWordDetector?.stop()
    }

    private var stateCollectionJob: Job? = null

    private suspend fun onWakeWordDetected(transcription: String) {
        Log.i(TAG, "Wake word detected! text=\"$transcription\"")
        updateNotification("Processing...")

        wakeWordDetector?.pause()
        delay(50)

        orchestrator.startFromWakeWord(transcription)

        // Cancel previous state watcher, start new one
        stateCollectionJob?.cancel()
        stateCollectionJob = serviceScope.launch {
            orchestrator.state.collect { state ->
                if (state == CharlieState.IDLE || state == CharlieState.ERROR) {
                    delay(100)
                    wakeWordDetector?.resume(serviceScope) { text ->
                        onWakeWordDetected(text)
                    }
                    updateNotification("Listening for \"Charlie\"...")
                    this@launch.cancel()  // stop collecting, coroutine ends
                }
            }
        }
    }

    // ─── Wake Lock ───────────────────────────────────────

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "charlie:wakeword"
            ).apply {
                acquire()
            }
            Log.i(TAG, "Wake lock acquired")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
            wakeLock = null
            Log.i(TAG, "Wake lock released")
        }
    }

    // ─── Notifications ───────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Charlie Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Charlie ready for orders"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val launchIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CharlieService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Charlie Standing By")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(launchIntent)
            .addAction(R.drawable.ic_notification, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        stopWakeWordDetection()
        orchestrator.shutdown()
        sharedWhisper.unload()  // clean up shared whisper
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }
}
