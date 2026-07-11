package au.josh.unifiphone.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.josh.unifiphone.PhoneViewModel
import au.josh.unifiphone.data.EntryType
import au.josh.unifiphone.ui.Keypad
import au.josh.unifiphone.ui.RegStatusPill
import au.josh.unifiphone.ui.RoundActionButton
import au.josh.unifiphone.ui.theme.SuccessGreen

@Composable
fun HomeScreen(vm: PhoneViewModel) {
    val settings by vm.settings.collectAsState()
    val regState by vm.engine.regState.collectAsState()
    val regDetail by vm.engine.regDetail.collectAsState()
    val entries by vm.directory.entries.collectAsState()
    var number by rememberSaveable { mutableStateOf("") }

    val quickDials = entries.filter { it.type != EntryType.CONTACT }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Identity header: label + registered extension + status
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = settings.phoneLabel.ifBlank { "Unlabelled phone" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (settings.sipUsername.isBlank()) "No extension configured"
                    else "Ext ${settings.sipUsername}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RegStatusPill(regState, regDetail)
        }

        Spacer(Modifier.height(8.dp))

        // Speed dials + paging targets
        if (quickDials.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(quickDials, key = { it.id }) { entry ->
                    AssistChip(
                        onClick = { vm.engine.dial(entry.dialString()) },
                        label = { Text(entry.name) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (entry.type == EntryType.PAGE)
                                    Icons.Filled.Campaign else Icons.Filled.Call,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Number display
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = number.ifEmpty { "Enter number" },
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium,
                color = if (number.isEmpty())
                    MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (number.isNotEmpty()) {
                IconButton(onClick = { number = number.dropLast(1) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Keypad(onKey = { number += it })
        Spacer(Modifier.height(20.dp))

        RoundActionButton(background = SuccessGreen, onClick = {
            if (number.isNotBlank()) {
                vm.engine.dial(number)
                number = ""
            }
        }) {
            Icon(Icons.Filled.Call, contentDescription = "Call", tint = androidx.compose.ui.graphics.Color.White)
        }
        Spacer(Modifier.height(8.dp))
    }
}
