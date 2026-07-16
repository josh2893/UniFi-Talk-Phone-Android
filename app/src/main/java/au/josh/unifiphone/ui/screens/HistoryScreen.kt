package au.josh.unifiphone.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import au.josh.unifiphone.PhoneViewModel
import au.josh.unifiphone.ui.theme.DangerRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(vm: PhoneViewModel) {
    val settings by vm.settings.collectAsState()
    val history by vm.directory.history.collectAsState()

    // Ring-group friendly: when missed-call display is off, missed entries
    // are simply not shown (and no missed-call state accumulates on screen).
    val visible = if (settings.showMissedCalls) history else history.filterNot { it.missed }
    val fmt = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Recent calls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { vm.clearHistory() }) {
                Icon(Icons.Filled.DeleteSweep, "Clear history", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No calls yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visible, key = { it.id }) { rec ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            val (icon, tint) = when {
                                rec.missed -> Icons.AutoMirrored.Filled.CallMissed to DangerRed
                                rec.direction == "in" ->
                                    Icons.AutoMirrored.Filled.CallReceived to MaterialTheme.colorScheme.primary
                                else -> Icons.AutoMirrored.Filled.CallMade to au.josh.unifiphone.ui.theme.SuccessGreen
                            }
                            Icon(icon, null, tint = tint)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(rec.displayName ?: rec.number, fontWeight = FontWeight.Medium)
                                Text(
                                    text = buildString {
                                        append(fmt.format(Date(rec.timestampMs)))
                                        if (rec.durationSec > 0) {
                                            append("  •  %d:%02d".format(rec.durationSec / 60, rec.durationSec % 60))
                                        } else if (rec.missed) append("  •  Missed")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { vm.engine.dial(rec.number, videoOverride = false) }) {
                                Icon(Icons.Filled.Call, "Voice call back", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { vm.engine.dial(rec.number, videoOverride = true) }) {
                                Icon(Icons.Filled.Videocam, "Video call back", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}
