package au.josh.unifiphone.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import au.josh.unifiphone.PhoneViewModel
import au.josh.unifiphone.core.CallUiState
import au.josh.unifiphone.ui.Keypad
import au.josh.unifiphone.ui.RoundActionButton
import au.josh.unifiphone.ui.theme.DangerRed
import au.josh.unifiphone.ui.theme.SuccessGreen
import kotlinx.coroutines.delay

@Composable
fun CallScreen(vm: PhoneViewModel, call: CallUiState) {
    var showDtmf by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }

    LaunchedEffect(call.connected, call.startedAtMs) {
        while (call.connected) {
            elapsed = (System.currentTimeMillis() - call.startedAtMs) / 1000
            delay(1000)
        }
    }

    val videoOn = call.videoActive && call.connected

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = if (videoOn) 12.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(if (videoOn) 8.dp else 48.dp))
        Text(
            text = call.remoteName ?: call.remoteNumber,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (call.remoteName != null) {
            Text(
                text = call.remoteNumber,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                call.incoming -> "Incoming call"
                call.onHold -> "On hold"
                call.connected -> "%d:%02d".format(elapsed / 60, elapsed % 60)
                else -> "Calling…"
            },
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )

        // Live debug overlay: poll engine stats once a second.
        val settings by vm.settings.collectAsState()
        if (settings.showDebugOverlay && call.connected) {
            var stats by remember { mutableStateOf("") }
            LaunchedEffect(call.active) {
                while (call.active) {
                    stats = vm.engine.videoDebugStats()
                    kotlinx.coroutines.delay(1000)
                }
            }
            Text(
                stats,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }

        // Keep the screen awake for the whole call (video especially).
        val view = LocalView.current
        DisposableEffect(call.active) {
            view.keepScreenOn = call.active
            onDispose { view.keepScreenOn = false }
        }

        if (videoOn) {
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp)),
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(h: SurfaceHolder) {
                                    vm.engine.attachRemoteVideoSurface(h.surface)
                                }
                                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                                override fun surfaceDestroyed(h: SurfaceHolder) {}
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (showDtmf) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.background(Color.Black.copy(alpha = 0.6f))) {
                            Keypad(compact = true, onKey = { vm.engine.sendDtmf(it) })
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }

        if (call.incoming) {
            Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                RoundActionButton(background = DangerRed, onClick = { vm.engine.decline() }, size = 76) {
                    Icon(Icons.Filled.CallEnd, "Decline", tint = Color.White)
                }
                RoundActionButton(background = SuccessGreen, onClick = { vm.engine.accept() }, size = 76) {
                    Icon(Icons.Filled.Call, "Answer", tint = Color.White)
                }
            }
        } else {
            if (showDtmf && !videoOn) {
                Keypad(compact = true, onKey = { vm.engine.sendDtmf(it) })
                Spacer(Modifier.height(20.dp))
            }
            if (call.canUpgradeToVideo) {
                RoundActionButton(
                    background = MaterialTheme.colorScheme.primary,
                    onClick = { vm.engine.upgradeToVideo() },
                    size = 56,
                ) {
                    Icon(Icons.Filled.Videocam, "Add video", tint = Color.White)
                }
                Spacer(Modifier.height(8.dp))
            }
            call.upgradeStatus?.let { status ->
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                ToggleCallButton(
                    active = call.muted,
                    icon = { Icon(if (call.muted) Icons.Filled.MicOff else Icons.Filled.Mic, "Mute", tint = it) },
                    onClick = { vm.engine.toggleMute() },
                )
                ToggleCallButton(
                    active = showDtmf,
                    icon = { Icon(Icons.Filled.Dialpad, "Keypad", tint = it) },
                    onClick = { showDtmf = !showDtmf },
                )
                ToggleCallButton(
                    active = call.onHold,
                    icon = {
                        Icon(if (call.onHold) Icons.Filled.PlayArrow else Icons.Filled.Pause, "Hold", tint = it)
                    },
                    onClick = { vm.engine.toggleHold() },
                )
                ToggleCallButton(
                    active = call.speaker,
                    icon = { Icon(Icons.Filled.VolumeUp, "Speaker", tint = it) },
                    onClick = { vm.engine.toggleSpeaker() },
                )
            }
            Spacer(Modifier.height(28.dp))
            RoundActionButton(background = DangerRed, onClick = { vm.engine.hangup() }, size = 76) {
                Icon(Icons.Filled.CallEnd, "End call", tint = Color.White)
            }
        }
        Spacer(Modifier.height(if (videoOn) 12.dp else 40.dp))
    }
}

@Composable
private fun ToggleCallButton(
    active: Boolean,
    icon: @Composable (tint: Color) -> Unit,
    onClick: () -> Unit,
) {
    val bg = if (active) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val tint = if (active) Color.White else MaterialTheme.colorScheme.onSurface
    RoundActionButton(background = bg, onClick = onClick, size = 60) { icon(tint) }
}