package com.alpha.charlie

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alpha.charlie.brain.CharlieOrchestrator
import com.alpha.charlie.brain.CharlieState
import com.alpha.charlie.service.CharlieService

class MainActivity : ComponentActivity() {

    private var orchestrator: CharlieOrchestrator? = null
    private var serviceBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as CharlieService.CharlieBinder).service
            orchestrator = service.orchestrator
            serviceBound = true
            // Re-compose with the real orchestrator
            setContent {
                CharlieTheme {
                    orchestrator?.let { CharlieScreen(it) }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            orchestrator = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled — UI updates reactively */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request permissions
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        )

        // Start and bind to foreground service
        val serviceIntent = Intent(this, CharlieService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, connection, BIND_AUTO_CREATE)

        setContent {
            CharlieTheme {
                // Show loading state until service binds
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DarkBg),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "CHARLIE STANDING BY...",
                        fontFamily = FontFamily.Monospace,
                        color = DarkGreen,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(connection)
            serviceBound = false
        }
        // Don't stop the service — let it run in background!
        super.onDestroy()
    }
}

// ═══════════════════════════════════════════════════════
// Theme
// ═══════════════════════════════════════════════════════

private val DarkGreen = Color(0xFF4CAF50)
private val DarkBg = Color(0xFF0F0F23)
private val DarkSurface = Color(0xFF1A1A2E)
private val TerminalGreen = Color(0xFF81C784)
private val AmberGold = Color(0xFFFFD54F)
private val MutedText = Color(0xFF888899)
private val ErrorRed = Color(0xFFCF6679)

@Composable
fun CharlieTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = DarkGreen,
        onPrimary = Color.White,
        surface = DarkSurface,
        onSurface = Color(0xFFE0E0E0),
        background = DarkBg,
        onBackground = Color(0xFFE0E0E0),
        error = ErrorRed
    )
    MaterialTheme(colorScheme = colors, content = content)
}

// ═══════════════════════════════════════════════════════
// Main Screen
// ═══════════════════════════════════════════════════════

@Composable
fun CharlieScreen(orchestrator: CharlieOrchestrator) {
    val state by orchestrator.state.collectAsState()
    val statusMessage by orchestrator.statusMessage.collectAsState()
    val isRecording by orchestrator.isRecording.collectAsState()
    val conversationLog by orchestrator.conversationLog.collectAsState()
    val hasModels = orchestrator.hasModels()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Header ──
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "C H A R L I E",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = DarkGreen,
            letterSpacing = 6.sp
        )
        Text(
            text = "AI MEMORY ASSISTANT • OFFLINE",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MutedText,
            letterSpacing = 2.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ── Model Warning ──
        if (!hasModels) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3E2723))
            ) {
                Text(
                    text = "⚠ MODELS NOT FOUND\n\n${orchestrator.getModelStatus()}\n\n" +
                           "Run setup.sh then push models via ADB.\n" +
                           "See README.md for instructions.",
                    modifier = Modifier.padding(12.dp),
                    color = Color(0xFFFFAB91),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // ── Status Bar ──
        StatusBar(state = state, message = statusMessage)

        Spacer(modifier = Modifier.height(12.dp))

        // ── Conversation Log ──
        ConversationLog(
            entries = conversationLog,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── Mic Button ──
        MicButton(
            state = state,
            isRecording = isRecording,
            enabled = state == CharlieState.IDLE || state == CharlieState.LISTENING || state == CharlieState.ERROR,
            onClick = {
                when (state) {
                    CharlieState.LISTENING -> orchestrator.stopListening()
                    CharlieState.IDLE, CharlieState.ERROR -> {
                        orchestrator.startListening()
                    }
                    else -> { /* busy, ignore */ }
                }
            }
        )

        // ── State Label ──
        Text(
            text = when (state) {
                CharlieState.IDLE -> "TAP TO TALK  •  OR SAY \"CHARLIE\""
                CharlieState.LISTENING -> "LISTENING • TAP TO STOP"
                CharlieState.TRANSCRIBING -> "TRANSCRIBING..."
                CharlieState.THINKING -> "PROCESSING..."
                CharlieState.SPEAKING -> "SPEAKING..."
                CharlieState.ERROR -> "TAP TO RETRY"
            },
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MutedText,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
        )
    }
}

// ═══════════════════════════════════════════════════════
// Components
// ═══════════════════════════════════════════════════════

@Composable
fun StatusBar(state: CharlieState, message: String) {
    val bgColor by animateColorAsState(
        targetValue = when (state) {
            CharlieState.ERROR -> Color(0xFF3E2723)
            CharlieState.LISTENING -> Color(0xFF1B3E1B)
            CharlieState.THINKING, CharlieState.TRANSCRIBING -> Color(0xFF1A2744)
            CharlieState.SPEAKING -> Color(0xFF2E1A44)
            else -> DarkSurface
        },
        label = "statusBg"
    )
    val textColor = when (state) {
        CharlieState.ERROR -> ErrorRed
        CharlieState.LISTENING -> TerminalGreen
        CharlieState.SPEAKING -> Color(0xFFCE93D8)
        else -> Color(0xFFB0BEC5)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Text(
            text = message,
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = textColor,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun ConversationLog(
    entries: List<com.alpha.charlie.brain.ConversationEntry>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12122B))
    ) {
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "[ NO TRANSMISSIONS ]",
                        color = Color(0xFF444466),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap the mic or say \"Charlie\"\n\n" +
                               "\"Charlie, remember my birthday is March 5\"\n" +
                               "\"Charlie, what's my birthday?\"",
                        textAlign = TextAlign.Center,
                        color = Color(0xFF333355),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            val listState = rememberLazyListState()

            LaunchedEffect(entries.size) {
                if (entries.isNotEmpty()) {
                    listState.animateScrollToItem(entries.size - 1)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(entries) { entry ->
                    // User message
                    Text(
                        text = "▶ YOU: ${entry.userText}",
                        color = TerminalGreen,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    // Charlie response
                    Text(
                        text = "◀ CHARLIE: ${entry.charlieResponse}",
                        color = AmberGold,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    HorizontalDivider(color = Color(0xFF252545))
                }
            }
        }
    }
}

@Composable
fun MicButton(
    state: CharlieState,
    isRecording: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val buttonColor by animateColorAsState(
        targetValue = when {
            isRecording -> Color(0xFFD32F2F)
            state == CharlieState.ERROR -> Color(0xFFFF8F00)
            else -> DarkGreen
        },
        label = "micColor"
    )

    // Pulse animation when recording
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Button(
        onClick = onClick,
        modifier = Modifier
            .size(80.dp)
            .scale(scale),
        shape = CircleShape,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = buttonColor,
            disabledContainerColor = Color(0xFF444444)
        )
    ) {
        Icon(
            imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = if (isRecording) "Stop Recording" else "Start Recording",
            modifier = Modifier.size(36.dp),
            tint = Color.White
        )
    }
}
