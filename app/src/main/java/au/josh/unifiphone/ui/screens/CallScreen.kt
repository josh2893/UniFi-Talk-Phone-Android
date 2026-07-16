package au.josh.unifiphone.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
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
import kotlin.math.roundToInt

@Composable
fun CallScreen(vm: PhoneViewModel, call: CallUiState) {
    var showDtmf by remember { mutableStateOf(false) }
    var showSelfView by remember { mutableStateOf(true) }
    var fullScreen by remember { mutableStateOf(false) }
    var selfOffsetX by remember { mutableStateOf(0f) }
    var selfOffsetY by remember { mutableStateOf(0f) }
    var elapsed by remember { mutableLongStateOf(0L) }

    LaunchedEffect(call.connected, call.startedAtMs) {
        while (call.connected) {
            elapsed = (System.currentTimeMillis() - call.startedAtMs) / 1000
            delay(1000)
        }
    }

    val videoOn = call.videoActive && call.connected
    val settings by vm.settings.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = if (fullScreen) 0.dp else 24.dp,
                vertical = if (fullScreen) 0.dp else if (videoOn) 12.dp else 24.dp,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!fullScreen) {
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
                call.incoming -> if (call.videoActive) "Incoming video call" else "Incoming voice call"
                call.onHold -> "On hold"
                call.connected -> "%d:%02d".format(elapsed / 60, elapsed % 60)
                else -> "Calling…"
            },
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
        }

        // Live debug overlay: poll engine stats once a second.
        if (!fullScreen && settings.showDebugOverlay && call.connected) {
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
            Spacer(Modifier.height(if (fullScreen) 0.dp else 12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(if (fullScreen) 0.dp else 16.dp)),
            ) {
                AndroidView(
                    factory = { ctx ->
                        val textureView = TextureView(ctx)
                        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                private var outputSurface: Surface? = null
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                    outputSurface = Surface(st).also { vm.engine.attachRemoteVideoSurface(it) }
                                    applyReceiveStretchFix(textureView, settings.videoReceiveStretchFixPercent)
                                }
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                                    applyReceiveStretchFix(textureView, settings.videoReceiveStretchFixPercent)
                                }
                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    vm.engine.attachRemoteVideoSurface(null)
                                    outputSurface?.release()
                                    outputSurface = null
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }
                        textureView
                    },
                    update = { applyReceiveStretchFix(it, settings.videoReceiveStretchFixPercent) },
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
            Spacer(Modifier.height(if (fullScreen) 0.dp else 12.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }

        if (!fullScreen && call.incoming) {
            Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                RoundActionButton(background = DangerRed, onClick = { vm.engine.decline() }, size = 76) {
                    Icon(Icons.Filled.CallEnd, "Decline", tint = Color.White)
                }
                RoundActionButton(background = SuccessGreen, onClick = { vm.engine.accept() }, size = 76) {
                    Icon(Icons.Filled.Call, "Answer", tint = Color.White)
                }
            }
        } else if (!fullScreen) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(if (videoOn) 12.dp else 20.dp)) {
                ToggleCallButton(
                    active = call.muted,
                    icon = { Icon(if (call.muted) Icons.Filled.MicOff else Icons.Filled.Mic, "Mute", tint = it) },
                    onClick = { vm.engine.toggleMute() },
                    size = if (videoOn) 50 else 60,
                )
                ToggleCallButton(
                    active = showDtmf,
                    icon = { Icon(Icons.Filled.Dialpad, "Keypad", tint = it) },
                    onClick = { showDtmf = !showDtmf },
                    size = if (videoOn) 50 else 60,
                )
                ToggleCallButton(
                    active = call.onHold,
                    icon = {
                        Icon(if (call.onHold) Icons.Filled.PlayArrow else Icons.Filled.Pause, "Hold", tint = it)
                    },
                    onClick = { vm.engine.toggleHold() },
                    size = if (videoOn) 50 else 60,
                )
                ToggleCallButton(
                    active = call.speaker,
                    icon = { Icon(Icons.Filled.VolumeUp, "Speaker", tint = it) },
                    onClick = { vm.engine.toggleSpeaker() },
                    size = if (videoOn) 50 else 60,
                )
            }
            Spacer(Modifier.height(if (videoOn) 16.dp else 28.dp))
            RoundActionButton(background = DangerRed, onClick = { vm.engine.hangup() }, size = if (videoOn) 62 else 76) {
                Icon(Icons.Filled.CallEnd, "End call", tint = Color.White)
            }
        }
        Spacer(Modifier.height(if (fullScreen) 0.dp else if (videoOn) 12.dp else 40.dp))
    }
    if (videoOn) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp),
        ) {
            RoundActionButton(
                background = if (showSelfView) MaterialTheme.colorScheme.primary
                else Color.Black.copy(alpha = 0.45f),
                onClick = { showSelfView = !showSelfView },
                size = 40,
            ) {
                Icon(Icons.Filled.Videocam, "Self view", tint = Color.White)
            }
            RoundActionButton(
                background = Color.Black.copy(alpha = 0.45f),
                onClick = { fullScreen = !fullScreen },
                size = 40,
            ) {
                Icon(
                    if (fullScreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    "Fullscreen",
                    tint = Color.White,
                )
            }
        }
        if (showSelfView) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .offset { IntOffset(selfOffsetX.roundToInt(), selfOffsetY.roundToInt()) }
                    .width(132.dp)
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectDragGestures { _, drag ->
                            selfOffsetX += drag.x
                            selfOffsetY += drag.y
                        }
                    },
            ) {
                SelfView(vm)
            }
        } else {
            DisposableEffect(Unit) {
                vm.engine.attachLocalPreviewSurface(null, 0, 0)
                onDispose {}
            }
        }
    }
    }
}

@Composable
private fun SelfView(vm: PhoneViewModel) {
    AndroidView(
        factory = { ctx ->
            val textureView = TextureView(ctx)
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                private var previewSurface: Surface? = null
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    previewSurface = Surface(st).also {
                        vm.engine.attachLocalPreviewSurface(it, w, h)
                    }
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                    previewSurface?.let {
                        vm.engine.attachLocalPreviewSurface(it, w, h)
                    }
                }
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    vm.engine.attachLocalPreviewSurface(null, 0, 0)
                    previewSurface?.release()
                    previewSurface = null
                    return true
                }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
            textureView
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun ToggleCallButton(
    active: Boolean,
    icon: @Composable (tint: Color) -> Unit,
    onClick: () -> Unit,
    size: Int = 60,
) {
    val bg = if (active) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val tint = if (active) Color.White else MaterialTheme.colorScheme.onSurface
    RoundActionButton(background = bg, onClick = onClick, size = size) { icon(tint) }
}

private fun applyReceiveStretchFix(view: TextureView, percent: Int) {
    val scaleX = percent.coerceIn(40, 140) / 100f
    val matrix = Matrix()
    matrix.setScale(scaleX, 1f, view.width / 2f, view.height / 2f)
    view.setTransform(matrix)
}
