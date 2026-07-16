package au.josh.unifiphone.ui.screens

import android.app.Activity
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import au.josh.unifiphone.PhoneViewModel
import au.josh.unifiphone.data.EntryType
import au.josh.unifiphone.data.ThemeMode
import au.josh.unifiphone.data.Transport
import au.josh.unifiphone.kiosk.KioskManager
import au.josh.unifiphone.web.WebManagementServer
import androidx.compose.runtime.saveable.rememberSaveable

private val builtInTones = listOf(
    "raw:ringtone_classic" to "Classic",
    "raw:ringtone_chime" to "Chime",
    "raw:ringtone_digital" to "Digital",
)

@Composable
fun SettingsScreen(vm: PhoneViewModel, initialTab: Int = 0) {
    var selectedTab by rememberSaveable(initialTab) {
        mutableIntStateOf(initialTab.coerceIn(0, 1))
    }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Phone") },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Doorbell") },
            )
        }
        Box(Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> PhoneSettingsContent(vm)
                else -> DoorbellSettingsContent(vm)
            }
        }
    }
}

@Composable
private fun PhoneSettingsContent(vm: PhoneViewModel) {
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current

    // Local editable copies of SIP fields; saved on "Save & register"
    var server by remember(settings.sipServer) { mutableStateOf(settings.sipServer) }
    var port by remember(settings.sipPort) { mutableStateOf(settings.sipPort) }
    var domain by remember(settings.sipDomain) { mutableStateOf(settings.sipDomain) }
    var user by remember(settings.sipUsername) { mutableStateOf(settings.sipUsername) }
    var pass by remember(settings.sipPassword) { mutableStateOf(settings.sipPassword) }

    // Label is edited locally and committed on focus-loss so it survives
    // independently of the SIP "Save & register" button.
    var label by remember { mutableStateOf(settings.phoneLabel) }

    val ringtonePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "custom_ringtone"
            context.contentResolver.openInputStream(uri)?.use { input ->
                vm.importRingtone(input.readBytes(), name)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Identity") {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Phone label") },
                supportingText = { Text("Shown on the home screen, e.g. \"Warehouse — Front Desk\"") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focus ->
                        if (!focus.isFocused && label.trim() != settings.phoneLabel) {
                            vm.updateSettings { it.copy(phoneLabel = label.trim()) }
                        }
                    },
            )
            TextButton(onClick = {
                vm.updateSettings { it.copy(phoneLabel = label.trim()) }
            }) { Text("Save label") }
        }

        SectionCard("SIP account (UniFi Talk)") {
            OutlinedTextField(
                value = server, onValueChange = { server = it },
                label = { Text("Server (Talk console IP / hostname)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port, onValueChange = { port = it.filter(Char::isDigit) },
                label = { Text("Port") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = domain, onValueChange = { domain = it },
                label = { Text("SIP domain") },
                supportingText = { Text("UniFi Talk uses talk.com — not the console IP") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = user, onValueChange = { user = it },
                label = { Text("Username / extension") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pass, onValueChange = { pass = it },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Text("Transport", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Transport.entries.forEach { t ->
                    FilterChip(
                        selected = settings.transport == t,
                        onClick = { vm.updateSettings { s -> s.copy(transport = t) } },
                        label = { Text(t.name) },
                    )
                }
            }
            TextButton(onClick = {
                vm.updateSettings {
                    it.copy(
                        sipServer = server.trim(), sipPort = port.trim().ifBlank { "5060" },
                        sipDomain = domain.trim().ifBlank { "talk.com" },
                        sipUsername = user.trim(), sipPassword = pass,
                        phoneLabel = label.trim(),
                    )
                }
            }) { Text("Save & register") }
        }

        SectionCard("Behaviour") {
            ToggleRow(
                title = "Show missed calls",
                subtitle = "Turn off for ring-group handsets to avoid missed-call clutter",
                checked = settings.showMissedCalls,
                onChange = { vm.updateSettings { s -> s.copy(showMissedCalls = it) } },
            )
            ToggleRow(
                title = "Video calls",
                subtitle = "Place dial pad calls as H.265 video calls. " +
                    "When off, incoming video calls are still accepted.",
                checked = settings.videoCalls,
                onChange = { vm.updateSettings { s -> s.copy(videoCalls = it) } },
            )
        }

        SectionCard("Backup") {
            var msg by remember { mutableStateOf<String?>(null) }
            StepperRow(label = "Export settings", value = "Save") {
                val path = vm.exportSettings()
                msg = "Saved to: $path"
            }
            StepperRow(label = "Restore settings", value = "Load") {
                msg = if (vm.importSettings()) "Restored from backup" else "No backup file found"
            }
            msg?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "Backup is written to the app's external files dir, which SURVIVES " +
                    "uninstall only if you copy it off first. Export before uninstalling, " +
                    "then Restore after reinstalling.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Debug / experiments") {
            ToggleRow(
                title = "Live stats overlay",
                subtitle = "Show RTP packet + decoded-frame counters during a call",
                checked = settings.showDebugOverlay,
                onChange = { vm.updateSettings { s -> s.copy(showDebugOverlay = it) } },
            )
            ToggleRow(
                title = "Mid-call video upgrade",
                subtitle = "Adds an \"Add video\" button to connected audio-only calls. " +
                    "Sends a re-INVITE offering H.265. Use to test whether a ring " +
                    "group call can be upgraded to video after it answers.",
                checked = settings.videoUpgradeDebug,
                onChange = { vm.updateSettings { s -> s.copy(videoUpgradeDebug = it) } },
            )
        }

        SectionCard("Video tuning (live — no rebuild)") {
            // Rotation stepper
            StepperRow(
                label = "Rotation",
                value = "${settings.videoRotationOffset}°",
                onTap = {
                    val next = (settings.videoRotationOffset + 90) % 360
                    vm.updateSettings { s -> s.copy(videoRotationOffset = next) }
                },
            )
            ToggleRow(
                title = "Extra mirror",
                subtitle = "Flip horizontally (on top of the automatic front-camera mirror)",
                checked = settings.videoMirror,
                onChange = { vm.updateSettings { s -> s.copy(videoMirror = it) } },
            )
            ToggleRow(
                title = "Front camera",
                subtitle = "Off = use the rear camera for outgoing video",
                checked = settings.videoUseFrontCamera,
                onChange = { vm.updateSettings { s -> s.copy(videoUseFrontCamera = it) } },
            )
            // Resolution cycler
            StepperRow(
                label = "Resolution (short edge)",
                value = if (settings.videoResolution == 0) "Auto" else "${settings.videoResolution}p",
                onTap = {
                    val opts = listOf(0, 240, 360, 480, 720)
                    val idx = opts.indexOf(settings.videoResolution).let { if (it < 0) 0 else it }
                    val next = opts[(idx + 1) % opts.size]
                    vm.updateSettings { s -> s.copy(videoResolution = next) }
                },
            )
            // Bitrate cycler
            StepperRow(
                label = "Bitrate",
                value = "${settings.videoBitrateKbps} kbps",
                onTap = {
                    val opts = listOf(300, 500, 800, 1200, 2000, 4000)
                    val idx = opts.indexOf(settings.videoBitrateKbps).let { if (it < 0) 2 else it }
                    val next = opts[(idx + 1) % opts.size]
                    vm.updateSettings { s -> s.copy(videoBitrateKbps = next) }
                },
            )
            // Scale mode toggle
            StepperRow(
                label = "Scale mode",
                value = if (settings.videoScaleMode == "fill") "Fill (crop)" else "Fit (letterbox)",
                onTap = {
                    val next = if (settings.videoScaleMode == "fill") "fit" else "fill"
                    vm.updateSettings { s -> s.copy(videoScaleMode = next) }
                },
            )
            StepperRow(
                label = "Target aspect",
                value = settings.videoTargetAspect,
                onTap = {
                    val opts = listOf("source", "9:16", "3:4", "1:1")
                    val idx = opts.indexOf(settings.videoTargetAspect).let { if (it < 0) 0 else it }
                    val next = opts[(idx + 1) % opts.size]
                    vm.updateSettings { s -> s.copy(videoTargetAspect = next) }
                },
            )
            StepperRow(
                label = "Phone stretch fix",
                value = "${settings.videoStretchFixPercent}%",
                onTap = {
                    val opts = listOf(100, 90, 80, 75, 67, 60, 56, 50, 45)
                    val idx = opts.indexOf(settings.videoStretchFixPercent).let { if (it < 0) 0 else it }
                    val next = opts[(idx + 1) % opts.size]
                    vm.updateSettings { s -> s.copy(videoStretchFixPercent = next) }
                },
            )
            StepperRow(
                label = "Receive stretch fix",
                value = "${settings.videoReceiveStretchFixPercent}%",
                onTap = {
                    val opts = listOf(100, 95, 90, 85, 80, 75, 67, 60, 56, 50)
                    val idx = opts.indexOf(settings.videoReceiveStretchFixPercent).let { if (it < 0) 0 else it }
                    val next = opts[(idx + 1) % opts.size]
                    vm.updateSettings { s -> s.copy(videoReceiveStretchFixPercent = next) }
                },
            )
            Text(
                "Changes apply on the NEXT call — hang up and redial to see them. " +
                    "Tune rotation first (portrait upright), then resolution/bitrate for quality.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Ring list: enter several extensions separated by commas on the " +
                    "dial pad (e.g. 10,11,12) to ring them all at once — first to " +
                    "answer wins. Talk rejects video calls to ring groups, so this " +
                    "is how a doorbell rings multiple phones with video.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Appearance") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { m ->
                    FilterChip(
                        selected = settings.themeMode == m,
                        onClick = { vm.updateSettings { s -> s.copy(themeMode = m) } },
                        label = {
                            Text(m.name.lowercase().replaceFirstChar(Char::uppercase))
                        },
                    )
                }
            }
        }

        SectionCard("Ringtone") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                builtInTones.forEach { (spec, name) ->
                    FilterChip(
                        selected = settings.ringtone == spec,
                        onClick = {
                            vm.updateSettings { s -> s.copy(ringtone = spec) }
                            preview(context, spec)
                        },
                        label = { Text(name) },
                    )
                }
            }
            if (settings.ringtone.startsWith("file:")) {
                Text(
                    "Custom: ${settings.ringtone.removePrefix("file:").substringAfterLast('/')}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { ringtonePicker.launch(arrayOf("audio/*")) }) {
                Text("Import ringtone file…")
            }
        }

        SectionCard("Kiosk mode") {
            val isOwner = remember { KioskManager.isDeviceOwner(context) }
            ToggleRow(
                title = "Lock to this app",
                subtitle = if (isOwner)
                    "Device owner active — full kiosk lock"
                else
                    "Not device owner — falls back to screen pinning. See README for adb provisioning.",
                checked = settings.kioskEnabled,
                onChange = { enabled ->
                    vm.updateSettings { s -> s.copy(kioskEnabled = enabled) }
                    (context as? Activity)?.let {
                        if (enabled) KioskManager.enterKiosk(it) else KioskManager.exitKiosk(it)
                    }
                },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DoorbellSettingsContent(vm: PhoneViewModel) {
    val settings by vm.settings.collectAsState()
    val entries by vm.directory.entries.collectAsState()
    val context = LocalContext.current

    var banner by remember(settings.doorbellBanner) { mutableStateOf(settings.doorbellBanner) }
    var title by remember(settings.doorbellTitle) { mutableStateOf(settings.doorbellTitle) }
    var address by remember(settings.doorbellAddress) { mutableStateOf(settings.doorbellAddress) }
    var instruction by remember(settings.doorbellInstruction) { mutableStateOf(settings.doorbellInstruction) }
    var idleMessage by remember(settings.doorbellIdleMessage) { mutableStateOf(settings.doorbellIdleMessage) }
    var target by remember(settings.doorbellTarget) { mutableStateOf(settings.doorbellTarget) }
    var pin by remember(settings.doorbellAdminPin) { mutableStateOf(settings.doorbellAdminPin) }
    var noAnswer by remember(settings.doorbellNoAnswerMessage) {
        mutableStateOf(settings.doorbellNoAnswerMessage)
    }
    var deliveryInstructions by remember(settings.doorbellDeliveryInstructions) {
        mutableStateOf(settings.doorbellDeliveryInstructions)
    }
    var deliveryThankYou by remember(settings.doorbellDeliveryThankYou) {
        mutableStateOf(settings.doorbellDeliveryThankYou)
    }
    var person1Name by remember(settings.doorbellDeliveryPerson1Name) {
        mutableStateOf(settings.doorbellDeliveryPerson1Name)
    }
    var person1Webhook by remember(settings.doorbellDeliveryPerson1Webhook) {
        mutableStateOf(settings.doorbellDeliveryPerson1Webhook)
    }
    var person2Name by remember(settings.doorbellDeliveryPerson2Name) {
        mutableStateOf(settings.doorbellDeliveryPerson2Name)
    }
    var person2Webhook by remember(settings.doorbellDeliveryPerson2Webhook) {
        mutableStateOf(settings.doorbellDeliveryPerson2Webhook)
    }
    var person3Name by remember(settings.doorbellDeliveryPerson3Name) {
        mutableStateOf(settings.doorbellDeliveryPerson3Name)
    }
    var person3Webhook by remember(settings.doorbellDeliveryPerson3Webhook) {
        mutableStateOf(settings.doorbellDeliveryPerson3Webhook)
    }
    var otherName by remember(settings.doorbellDeliveryOtherName) {
        mutableStateOf(settings.doorbellDeliveryOtherName)
    }
    var otherWebhook by remember(settings.doorbellDeliveryOtherWebhook) {
        mutableStateOf(settings.doorbellDeliveryOtherWebhook)
    }
    var apiKeyHeader by remember(settings.doorbellDeliveryApiKeyHeader) {
        mutableStateOf(settings.doorbellDeliveryApiKeyHeader)
    }
    var apiKey by remember(settings.doorbellDeliveryApiKey) {
        mutableStateOf(settings.doorbellDeliveryApiKey)
    }
    var webPort by remember(settings.webManagementPort) {
        mutableStateOf(settings.webManagementPort.toString())
    }
    val phoneIp = remember(settings.webManagementEnabled, settings.webManagementPort) {
        WebManagementServer.localIpv4Address()
    }
    val videoGroups = entries.filter { it.type == EntryType.GROUP_VIDEO }

    fun saveDoorbellFields(enabled: Boolean = settings.doorbellEnabled) {
        vm.updateSettings {
            it.copy(
                doorbellEnabled = enabled,
                doorbellBanner = banner.trim(),
                doorbellTitle = title.trim().ifBlank { "Front Door" },
                doorbellAddress = address.trim(),
                doorbellIdleMessage = idleMessage.trim(),
                doorbellInstruction = instruction.trim().ifBlank {
                    "Welcome. Please use the doorbell button below."
                },
                doorbellTarget = target.split(',', ';', ' ')
                    .map { it.trim() }.filter { it.isNotEmpty() }.joinToString(","),
                doorbellAdminPin = pin.filter(Char::isDigit).take(8).ifBlank { "1234" },
                doorbellNoAnswerMessage = noAnswer.trim().ifBlank {
                    "Sorry, no one is available right now."
                },
                doorbellDeliveryInstructions = deliveryInstructions.trim(),
                doorbellDeliveryThankYou = deliveryThankYou.trim().ifBlank {
                    "Thank you. Your delivery notification has been sent."
                },
                doorbellDeliveryPerson1Name = person1Name.trim().ifBlank { "Person 1" },
                doorbellDeliveryPerson1Webhook = person1Webhook.trim(),
                doorbellDeliveryPerson2Name = person2Name.trim().ifBlank { "Person 2" },
                doorbellDeliveryPerson2Webhook = person2Webhook.trim(),
                doorbellDeliveryPerson3Name = person3Name.trim().ifBlank { "Person 3" },
                doorbellDeliveryPerson3Webhook = person3Webhook.trim(),
                doorbellDeliveryOtherName = otherName.trim().ifBlank { "Someone else" },
                doorbellDeliveryOtherWebhook = otherWebhook.trim(),
                doorbellDeliveryApiKeyHeader = apiKeyHeader.trim().ifBlank { "X-API-Key" },
                doorbellDeliveryApiKey = apiKey.trim(),
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Web management") {
            ToggleRow(
                title = "Enable web management",
                subtitle = "Manage this phone from a browser on the same network",
                checked = settings.webManagementEnabled,
                onChange = { enabled ->
                    vm.updateSettings { it.copy(webManagementEnabled = enabled) }
                },
            )
            Text(
                "Phone IP address: ${phoneIp ?: "Not connected"}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                if (settings.webManagementEnabled && phoneIp != null)
                    "Open http://$phoneIp:${settings.webManagementPort}"
                else
                    "Enable web management to open the browser interface.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = webPort,
                onValueChange = { webPort = it.filter(Char::isDigit).take(5) },
                label = { Text("Web server port") },
                supportingText = { Text("Use a port from 1024 to 65535") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val nextPort = webPort.toIntOrNull() ?: return@Button
                    vm.updateSettings { it.copy(webManagementPort = nextPort) }
                },
                enabled = webPort.toIntOrNull()?.let { it in 1024..65535 } == true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Apply web access")
            }
        }

        SectionCard("Display") {
            OutlinedTextField(
                value = banner,
                onValueChange = { banner = it },
                label = { Text("Message / banner") },
                supportingText = { Text("A short greeting shown above the door name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Door or building name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Building / house address") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = idleMessage,
                onValueChange = { idleMessage = it },
                label = { Text("Idle status message") },
                supportingText = { Text("Shown below the doorbell button while it is ready") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ToggleRow(
                title = "Show custom message",
                subtitle = "Display an information message above the doorbell button",
                checked = settings.doorbellMessageEnabled,
                onChange = { enabled ->
                    vm.updateSettings { it.copy(doorbellMessageEnabled = enabled) }
                },
            )
            if (settings.doorbellMessageEnabled) {
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    label = { Text("Custom visitor message") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SectionCard("Video call destination") {
            OutlinedTextField(
                value = target,
                onValueChange = { target = it },
                label = { Text("Extensions") },
                supportingText = { Text("Separate several extensions with commas, e.g. 10,11,12") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (videoGroups.isNotEmpty()) {
                Text("Use a saved group video call", style = MaterialTheme.typography.labelLarge)
                videoGroups.forEach { group ->
                    FilterChip(
                        selected = target == group.dialString(),
                        onClick = { target = group.dialString() },
                        label = { Text("${group.name}  ${group.dialString()}") },
                    )
                }
            }
            Text(
                "Doorbell calls always use video. When several extensions are entered, " +
                    "they ring at the same time and the first phone to answer wins.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Visitor chime") {
            ToggleRow(
                title = "Play until ringing stops",
                subtitle = "Keep playing the visitor chime until a phone answers or the call rings out",
                checked = settings.doorbellChimeUntilCallEnds,
                onChange = { enabled ->
                    vm.updateSettings { it.copy(doorbellChimeUntilCallEnds = enabled) }
                },
            )
            if (!settings.doorbellChimeUntilCallEnds) {
                StepperRow(
                    label = "Number of chimes",
                    value = settings.doorbellChimeCount.toString(),
                    onTap = {
                        val next = if (settings.doorbellChimeCount >= 10) 1
                        else settings.doorbellChimeCount + 1
                        vm.updateSettings { it.copy(doorbellChimeCount = next) }
                    },
                )
            }
            TextButton(onClick = { preview(context, "raw:chime_ring") }) {
                Text("Preview visitor chime")
            }
        }

        SectionCard("Security") {
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                label = { Text("Settings PIN") },
                supportingText = { Text("Use 4 to 8 digits. The factory PIN is 1234.") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard("No answer") {
            OutlinedTextField(
                value = noAnswer,
                onValueChange = { noAnswer = it },
                label = { Text("Message shown to visitor") },
                minLines = 2,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard("Delivery notifications") {
            ToggleRow(
                title = "Show delivery option",
                subtitle = "Let a delivery person notify a selected recipient when no one answers",
                checked = settings.doorbellDeliveryEnabled,
                onChange = { enabled ->
                    vm.updateSettings { it.copy(doorbellDeliveryEnabled = enabled) }
                },
            )
            if (settings.doorbellDeliveryEnabled) {
                OutlinedTextField(
                    value = deliveryInstructions,
                    onValueChange = { deliveryInstructions = it },
                    label = { Text("Delivery instructions") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = deliveryThankYou,
                    onValueChange = { deliveryThankYou = it },
                    label = { Text("Thank-you message") },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { preview(context, "raw:delivery_notification") }) {
                    Text("Preview notification sound")
                }
            }
        }

        if (settings.doorbellDeliveryEnabled) {
            SectionCard("Webhook authentication") {
                OutlinedTextField(
                    value = apiKeyHeader,
                    onValueChange = { apiKeyHeader = it },
                    label = { Text("API key header") },
                    supportingText = { Text("For example: X-API-Key or Authorization") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API key or bearer value") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionCard("Delivery recipients") {
                DeliveryRecipientFields(
                    label = "Person 1",
                    name = person1Name,
                    webhook = person1Webhook,
                    onNameChange = { person1Name = it },
                    onWebhookChange = { person1Webhook = it },
                )
                DeliveryRecipientFields(
                    label = "Person 2",
                    name = person2Name,
                    webhook = person2Webhook,
                    onNameChange = { person2Name = it },
                    onWebhookChange = { person2Webhook = it },
                )
                DeliveryRecipientFields(
                    label = "Person 3",
                    name = person3Name,
                    webhook = person3Webhook,
                    onNameChange = { person3Name = it },
                    onWebhookChange = { person3Webhook = it },
                )
                DeliveryRecipientFields(
                    label = "Other recipient",
                    name = otherName,
                    webhook = otherWebhook,
                    onNameChange = { otherName = it },
                    onWebhookChange = { otherWebhook = it },
                )
                Text(
                    "Webhooks are sent as HTTP POST requests with a JSON body containing " +
                        "the recipient, door name, address, and timestamp.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Button(
            onClick = { saveDoorbellFields() },
            enabled = target.isNotBlank() && pin.length >= 4,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save doorbell settings")
        }

        SectionCard("Doorbell mode") {
            ToggleRow(
                title = "Use this device as a doorbell",
                subtitle = if (settings.doorbellEnabled)
                    "Doorbell mode is active. Turning it off restores the normal phone interface."
                else
                    "Configure and save the fields above before enabling the dedicated visitor screen.",
                checked = settings.doorbellEnabled,
                enabled = settings.doorbellEnabled || (target.isNotBlank() && pin.length >= 4),
                onChange = { enabled ->
                    if (!enabled || (target.isNotBlank() && pin.length >= 4)) {
                        saveDoorbellFields(enabled)
                    }
                },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DeliveryRecipientFields(
    label: String,
    name: String,
    webhook: String,
    onNameChange: (String) -> Unit,
    onWebhookChange: (String) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text("Display name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = webhook,
        onValueChange = onWebhookChange,
        label = { Text("Webhook URL") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun preview(context: android.content.Context, spec: String) {
    val resName = spec.removePrefix("raw:")
    val id = context.resources.getIdentifier(resName, "raw", context.packageName)
    if (id != 0) MediaPlayer.create(context, id)?.apply {
        setOnCompletionListener { it.release() }
        start()
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
@Composable
private fun StepperRow(label: String, value: String, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Text(
            value,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
