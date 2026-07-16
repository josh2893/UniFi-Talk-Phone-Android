package au.josh.unifiphone.ui.screens

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.josh.unifiphone.PhoneViewModel
import au.josh.unifiphone.R
import au.josh.unifiphone.core.RegState
import au.josh.unifiphone.ui.theme.DangerRed
import au.josh.unifiphone.ui.theme.SuccessGreen
import au.josh.unifiphone.ui.theme.UbntBlueDim
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private val DoorbellBackground = Color(0xFF080B10)
private val DoorbellSurface = Color(0xFF11161D)
private val DoorbellText = Color(0xFFF3F6FA)
private val DoorbellMuted = Color(0xFF9AA5B4)
private val DoorbellLine = Color(0xFF202832)

private enum class DoorbellCallState { IDLE, RINGING, CONNECTED, NO_ANSWER }

@Composable
fun DoorbellScreen(vm: PhoneViewModel, onAdminUnlocked: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val call by vm.engine.callState.collectAsState()
    val registration by vm.engine.regState.collectAsState()
    val context = LocalContext.current
    val chime = remember { DoorbellChimePlayer() }

    var callInitiatedHere by rememberSaveable { mutableStateOf(false) }
    var callWasConnected by rememberSaveable { mutableStateOf(false) }
    var showNoAnswer by rememberSaveable { mutableStateOf(false) }
    var localMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var showPin by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(chime) {
        onDispose { chime.stop() }
    }

    LaunchedEffect(call.active, call.connected, callInitiatedHere) {
        if (call.connected) {
            callWasConnected = true
            chime.stop()
        }
        if (callInitiatedHere && !call.active) {
            chime.stop()
            callInitiatedHere = false
            if (callWasConnected) {
                callWasConnected = false
                showNoAnswer = false
            } else {
                showNoAnswer = true
            }
        }
    }

    LaunchedEffect(showNoAnswer) {
        if (showNoAnswer) {
            delay(6_000)
            showNoAnswer = false
        }
    }

    LaunchedEffect(localMessage) {
        if (localMessage != null) {
            delay(6_000)
            localMessage = null
        }
    }

    val visualState = when {
        call.active && call.connected -> DoorbellCallState.CONNECTED
        call.active -> DoorbellCallState.RINGING
        showNoAnswer || localMessage != null -> DoorbellCallState.NO_ANSWER
        else -> DoorbellCallState.IDLE
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(DoorbellBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 18.dp),
    ) {
        val buttonSize = (maxWidth * 0.62f).coerceIn(184.dp, 244.dp)

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DoorbellTopBar(registration)
            Spacer(Modifier.weight(0.42f))

            Text(
                text = settings.doorbellBanner.ifBlank { "WELCOME" }.uppercase(),
                color = UbntBlueDim,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = settings.doorbellTitle.ifBlank { "Front Door" },
                color = DoorbellText,
                fontSize = 36.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            if (settings.doorbellAddress.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = settings.doorbellAddress,
                    color = DoorbellMuted,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(42.dp))
            Row(
                modifier = Modifier.fillMaxWidth(0.84f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .border(1.dp, DoorbellLine, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Notifications,
                        contentDescription = null,
                        tint = DoorbellText,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Text(
                    text = settings.doorbellInstruction,
                    color = DoorbellText,
                    fontSize = 17.sp,
                    lineHeight = 24.sp,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(34.dp))
            DoorbellButton(
                state = visualState,
                size = buttonSize,
                enabled = visualState == DoorbellCallState.IDLE,
                onClick = {
                    localMessage = null
                    when {
                        registration != RegState.OK -> {
                            localMessage = "Doorbell is offline. Please try again shortly."
                        }
                        settings.doorbellTarget.isBlank() -> {
                            localMessage = "Doorbell calling has not been configured."
                        }
                        else -> {
                            showNoAnswer = false
                            callWasConnected = false
                            callInitiatedHere = true
                            chime.play(
                                context = context,
                                count = settings.doorbellChimeCount,
                                untilStopped = settings.doorbellChimeUntilCallEnds,
                            )
                            vm.engine.dial(settings.doorbellTarget, videoOverride = true)
                        }
                    }
                },
            )

            Spacer(Modifier.height(22.dp))
            DoorbellStateMessage(
                state = visualState,
                noAnswerMessage = localMessage ?: settings.doorbellNoAnswerMessage,
            )
            Spacer(Modifier.weight(1f))

            HorizontalDivider(color = DoorbellLine)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = { showPin = true },
                        enabled = !call.active,
                    ) {
                        Icon(
                            Icons.Filled.MoreHoriz,
                            contentDescription = "Open settings",
                            tint = if (call.active) DoorbellMuted.copy(alpha = 0.4f) else DoorbellMuted,
                        )
                    }
                    Text("MORE", color = DoorbellMuted, fontSize = 11.sp)
                }
            }
        }
    }

    if (showPin) {
        DoorbellPinDialog(
            expectedPin = settings.doorbellAdminPin.ifBlank { "1234" },
            onDismiss = { showPin = false },
            onUnlocked = {
                showPin = false
                onAdminUnlocked()
            },
        )
    }
}

@Composable
private fun DoorbellTopBar(registration: RegState) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(1_000)
        }
    }
    val registered = registration == RegState.OK
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(11.dp)
                .clip(CircleShape)
                .background(if (registered) SuccessGreen else DangerRed)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (registered) "REGISTERED" else "OFFLINE",
            color = if (registered) SuccessGreen else DangerRed,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = now.format(DateTimeFormatter.ofPattern("h:mm a")),
            color = DoorbellText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun DoorbellButton(
    state: DoorbellCallState,
    size: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "doorbell")
    val pulse by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(tween(1_150), RepeatMode.Reverse),
        label = "button pulse",
    )
    val bellSwing by transition.animateFloat(
        initialValue = -10f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(tween(340), RepeatMode.Reverse),
        label = "bell swing",
    )
    val accent = when (state) {
        DoorbellCallState.CONNECTED -> SuccessGreen
        DoorbellCallState.NO_ANSWER -> DangerRed
        else -> UbntBlueDim
    }

    Box(
        modifier = Modifier.size(size + 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (state == DoorbellCallState.RINGING) {
            CircularProgressIndicator(
                modifier = Modifier.fillMaxSize(),
                color = accent,
                trackColor = accent.copy(alpha = 0.14f),
                strokeWidth = 5.dp,
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = if (state == DoorbellCallState.IDLE) pulse else 1f
                        scaleY = if (state == DoorbellCallState.IDLE) pulse else 1f
                        alpha = if (state == DoorbellCallState.IDLE) 0.28f else 0.18f
                    }
                    .border(5.dp, accent, CircleShape)
            )
        }

        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(DoorbellSurface)
                .border(1.dp, accent.copy(alpha = 0.72f), CircleShape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val icon = when (state) {
                    DoorbellCallState.CONNECTED -> Icons.Filled.CheckCircle
                    DoorbellCallState.NO_ANSWER -> Icons.Filled.ErrorOutline
                    DoorbellCallState.RINGING -> Icons.Filled.PhoneInTalk
                    DoorbellCallState.IDLE -> Icons.Filled.Notifications
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = DoorbellText,
                    modifier = Modifier
                        .size(72.dp)
                        .graphicsLayer {
                            rotationZ = if (state == DoorbellCallState.RINGING) bellSwing else 0f
                        },
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = when (state) {
                        DoorbellCallState.IDLE -> "TAP TO RING"
                        DoorbellCallState.RINGING -> "RINGING"
                        DoorbellCallState.CONNECTED -> "ANSWERED"
                        DoorbellCallState.NO_ANSWER -> "NO ANSWER"
                    },
                    color = accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun DoorbellStateMessage(state: DoorbellCallState, noAnswerMessage: String) {
    val (message, color) = when (state) {
        DoorbellCallState.IDLE -> "We are ready when you are." to DoorbellMuted
        DoorbellCallState.RINGING -> "Calling now. Please wait..." to UbntBlueDim
        DoorbellCallState.CONNECTED -> "Connected. You may speak now." to SuccessGreen
        DoorbellCallState.NO_ANSWER -> noAnswerMessage to DangerRed
    }
    Text(
        text = message,
        color = color,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(0.84f),
    )
}

@Composable
private fun DoorbellPinDialog(
    expectedPin: String,
    onDismiss: () -> Unit,
    onUnlocked: () -> Unit,
) {
    var entered by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf(false) }
    var secondsLeft by remember { mutableIntStateOf(10) }

    LaunchedEffect(Unit) {
        repeat(10) { elapsed ->
            secondsLeft = 10 - elapsed
            delay(1_000)
        }
        onDismiss()
    }

    fun submit() {
        if (entered == expectedPin) onUnlocked()
        else {
            entered = ""
            error = true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
        title = { Text("Enter settings PIN") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = if (entered.isEmpty()) "-" else "*".repeat(entered.length),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (error) DangerRed else androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (error) "Incorrect PIN" else "Closes in $secondsLeft seconds",
                    color = if (error) DangerRed else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
                listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                ).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        row.forEach { digit ->
                            PinKey(digit) {
                                if (entered.length < 8) entered += digit
                                error = false
                            }
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.size(54.dp))
                    PinKey("0") {
                        if (entered.length < 8) entered += "0"
                        error = false
                    }
                    IconButton(
                        onClick = { entered = entered.dropLast(1); error = false },
                        modifier = Modifier.size(54.dp),
                    ) {
                        Icon(Icons.Filled.Backspace, contentDescription = "Delete digit")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = entered.isNotEmpty()) { Text("Unlock") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun PinKey(digit: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(digit, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

private class DoorbellChimePlayer {
    private var player: MediaPlayer? = null

    fun play(context: Context, count: Int, untilStopped: Boolean) {
        stop()
        val mediaPlayer = MediaPlayer.create(context, R.raw.chime_ring) ?: return
        player = mediaPlayer
        mediaPlayer.setVolume(1f, 1f)
        if (untilStopped) {
            mediaPlayer.isLooping = true
        } else {
            var plays = 1
            mediaPlayer.setOnCompletionListener { completed ->
                if (plays < count.coerceIn(1, 10) && player === completed) {
                    plays += 1
                    completed.seekTo(0)
                    completed.start()
                } else {
                    completed.release()
                    if (player === completed) player = null
                }
            }
        }
        mediaPlayer.start()
    }

    fun stop() {
        player?.runCatching {
            if (isPlaying) stop()
            release()
        }
        player = null
    }
}
