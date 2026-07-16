package au.josh.unifiphone.ui.screens

import android.media.MediaPlayer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.josh.unifiphone.PhoneViewModel
import au.josh.unifiphone.R
import au.josh.unifiphone.data.AppSettings
import au.josh.unifiphone.ui.theme.DangerRed
import au.josh.unifiphone.ui.theme.SuccessGreen
import au.josh.unifiphone.ui.theme.UbntBlueDim
import kotlinx.coroutines.delay

private val DeliveryBackground = Color(0xFF080B10)
private val DeliverySurface = Color(0xFF11161D)
private val DeliverySurfaceHigh = Color(0xFF171D25)
private val DeliveryText = Color(0xFFF3F6FA)
private val DeliveryMuted = Color(0xFF9AA5B4)
private val DeliveryLine = Color(0xFF29323D)

private data class DeliveryRecipient(
    val id: String,
    val name: String,
    val webhook: String,
    val color: Color,
)

private enum class DeliveryUiState { SELECTING, SENDING, SUCCESS, ERROR }

@Composable
fun DeliveryScreen(
    vm: PhoneViewModel,
    settings: AppSettings,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onComplete: () -> Unit,
) {
    val context = LocalContext.current
    val recipients = remember(
        settings.doorbellDeliveryPerson1Name,
        settings.doorbellDeliveryPerson1Webhook,
        settings.doorbellDeliveryPerson2Name,
        settings.doorbellDeliveryPerson2Webhook,
        settings.doorbellDeliveryPerson3Name,
        settings.doorbellDeliveryPerson3Webhook,
        settings.doorbellDeliveryOtherName,
        settings.doorbellDeliveryOtherWebhook,
    ) {
        listOf(
            DeliveryRecipient(
                "person_1",
                settings.doorbellDeliveryPerson1Name.ifBlank { "Person 1" },
                settings.doorbellDeliveryPerson1Webhook,
                UbntBlueDim,
            ),
            DeliveryRecipient(
                "person_2",
                settings.doorbellDeliveryPerson2Name.ifBlank { "Person 2" },
                settings.doorbellDeliveryPerson2Webhook,
                Color(0xFF6F7885),
            ),
            DeliveryRecipient(
                "person_3",
                settings.doorbellDeliveryPerson3Name.ifBlank { "Person 3" },
                settings.doorbellDeliveryPerson3Webhook,
                Color(0xFF4E7C70),
            ),
            DeliveryRecipient(
                "other",
                settings.doorbellDeliveryOtherName.ifBlank { "Someone else" },
                settings.doorbellDeliveryOtherWebhook,
                Color(0xFF725C82),
            ),
        )
    }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var state by rememberSaveable { mutableStateOf(DeliveryUiState.SELECTING) }
    var webhookResult by remember { mutableStateOf<Boolean?>(null) }
    var sendStartedAt by remember { mutableLongStateOf(0L) }
    val selected = recipients.firstOrNull { it.id == selectedId }

    LaunchedEffect(webhookResult) {
        val success = webhookResult ?: return@LaunchedEffect
        val remainingAnimation = (1_500L - (System.currentTimeMillis() - sendStartedAt))
            .coerceAtLeast(0L)
        delay(remainingAnimation)
        if (success) {
            state = DeliveryUiState.SUCCESS
            MediaPlayer.create(context, R.raw.delivery_notification)?.apply {
                setOnCompletionListener { completed -> completed.release() }
                start()
            }
            delay(3_200)
            onComplete()
        } else {
            state = DeliveryUiState.ERROR
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeliveryBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = onBack,
                enabled = state == DeliveryUiState.SELECTING || state == DeliveryUiState.ERROR,
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = DeliveryText)
            }
            Text(
                "Delivery Instructions",
                color = DeliveryText,
                fontSize = 23.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (state == DeliveryUiState.SELECTING) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Who is this delivery for?", color = DeliveryMuted, fontSize = 15.sp)
                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        recipients.take(3).forEach { recipient ->
                            DeliveryRecipientCard(
                                recipient = recipient,
                                selected = selectedId == recipient.id,
                                onClick = { selectedId = recipient.id },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OtherRecipientButton(
                        recipient = recipients.last(),
                        selected = selectedId == recipients.last().id,
                        onClick = { selectedId = recipients.last().id },
                    )

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Delivery instructions",
                        color = DeliveryText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, DeliveryLine, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        Text(
                            settings.doorbellDeliveryInstructions,
                            color = DeliveryText,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            maxLines = 7,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(DeliverySurface)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(UbntBlueDim),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Info, null, tint = Color.White, modifier = Modifier.size(19.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (selected != null && selected.webhook.isBlank())
                                "This person does not have a webhook configured."
                            else
                                "A notification will be sent to the selected person.",
                            color = if (selected != null && selected.webhook.isBlank()) DangerRed else DeliveryText,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            selected?.let { target ->
                                state = DeliveryUiState.SENDING
                                webhookResult = null
                                sendStartedAt = System.currentTimeMillis()
                                vm.sendDeliveryNotification(target.name, target.webhook) { success ->
                                    webhookResult = success
                                }
                            }
                        },
                        enabled = selected?.webhook?.isNotBlank() == true,
                        colors = ButtonDefaults.buttonColors(containerColor = UbntBlueDim),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) {
                        Icon(Icons.Filled.NotificationsActive, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("Send Notification", fontSize = 16.sp)
                    }
                }
            } else {
                DeliveryProgressContent(
                    state = state,
                    thankYouMessage = settings.doorbellDeliveryThankYou,
                    onRetry = {
                        webhookResult = null
                        state = DeliveryUiState.SELECTING
                    },
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.MoreHoriz, contentDescription = "Open settings", tint = DeliveryMuted)
            }
        }
    }
}

@Composable
private fun DeliveryRecipientCard(
    recipient: DeliveryRecipient,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .height(98.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(DeliverySurface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) UbntBlueDim else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterVertically),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(recipient.color),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials(recipient.name), color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        Text(
            recipient.name,
            color = DeliveryText,
            fontSize = if (recipient.name.length > 18) 12.sp else 14.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun OtherRecipientButton(
    recipient: DeliveryRecipient,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth(0.72f)
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(DeliverySurface)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) UbntBlueDim else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.PersonAdd, contentDescription = null, tint = DeliveryMuted)
        Spacer(Modifier.width(14.dp))
        Text(
            recipient.name,
            color = DeliveryText,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DeliveryProgressContent(
    state: DeliveryUiState,
    thankYouMessage: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 70.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            DeliveryUiState.SENDING -> PostingLetterAnimation()
            DeliveryUiState.SUCCESS -> {
                Icon(
                    Icons.Filled.MarkEmailRead,
                    contentDescription = null,
                    tint = SuccessGreen,
                    modifier = Modifier.size(96.dp),
                )
                Spacer(Modifier.height(28.dp))
                Text("Thank you", color = SuccessGreen, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(14.dp))
                Text(
                    thankYouMessage,
                    color = DeliveryText,
                    fontSize = 17.sp,
                    lineHeight = 24.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(0.82f),
                )
            }
            DeliveryUiState.ERROR -> {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = DangerRed,
                    modifier = Modifier.size(88.dp),
                )
                Spacer(Modifier.height(24.dp))
                Text("Notification failed", color = DangerRed, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Text(
                    "The notification could not be delivered. Please try again or ring the doorbell.",
                    color = DeliveryMuted,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp,
                    modifier = Modifier.fillMaxWidth(0.84f),
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onRetry,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = UbntBlueDim),
                ) { Text("Try again") }
            }
            DeliveryUiState.SELECTING -> Unit
        }
    }
}

@Composable
private fun PostingLetterAnimation() {
    val progress = remember { Animatable(0f) }
    val travelPx = with(LocalDensity.current) { 68.dp.toPx() }
    LaunchedEffect(Unit) {
        while (true) {
            progress.snapTo(0f)
            progress.animateTo(
                1f,
                animationSpec = tween(1_050, easing = FastOutSlowInEasing),
            )
            delay(260)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(width = 160.dp, height = 150.dp)) {
            Icon(
                Icons.Filled.Mail,
                contentDescription = null,
                tint = UbntBlueDim,
                modifier = Modifier
                    .size(62.dp)
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        translationY = progress.value * travelPx
                        alpha = 1f - (progress.value * 0.75f)
                        scaleX = 1f - (progress.value * 0.18f)
                        scaleY = 1f - (progress.value * 0.18f)
                    },
            )
            Box(
                modifier = Modifier
                    .size(width = 126.dp, height = 70.dp)
                    .align(Alignment.BottomCenter)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DeliverySurfaceHigh)
                    .border(2.dp, UbntBlueDim, RoundedCornerShape(8.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .width(72.dp)
                        .height(4.dp)
                        .align(Alignment.TopCenter)
                        .background(UbntBlueDim)
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Posting notification...", color = UbntBlueDim, fontSize = 21.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        Text("Please wait", color = DeliveryMuted, fontSize = 15.sp)
    }
}

private fun initials(name: String): String = name
    .trim()
    .split(Regex("\\s+"))
    .filter { it.isNotEmpty() }
    .take(2)
    .mapNotNull { it.firstOrNull()?.uppercaseChar() }
    .joinToString("")
    .ifBlank { "?" }
