package au.josh.unifiphone.ui.screens

import android.app.Activity
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import au.josh.unifiphone.PhoneViewModel
import au.josh.unifiphone.data.ThemeMode
import au.josh.unifiphone.data.Transport
import au.josh.unifiphone.kiosk.KioskManager

private val builtInTones = listOf(
    "raw:ringtone_classic" to "Classic",
    "raw:ringtone_chime" to "Chime",
    "raw:ringtone_digital" to "Digital",
)

@Composable
fun SettingsScreen(vm: PhoneViewModel) {
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
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}