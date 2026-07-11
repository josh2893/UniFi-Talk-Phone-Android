package au.josh.unifiphone.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import au.josh.unifiphone.PhoneViewModel
import au.josh.unifiphone.data.DirectoryEntry
import au.josh.unifiphone.data.EntryType

@Composable
fun ContactsScreen(vm: PhoneViewModel) {
    val entries by vm.directory.entries.collectAsState()
    var editing by remember { mutableStateOf<DirectoryEntry?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { editing = null; showDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
            ) { Icon(Icons.Filled.Add, "Add contact", tint = androidx.compose.ui.graphics.Color.White) }
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "No contacts yet. Tap + to add a contact,\nspeed dial, or paging target.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editing = entry; showDialog = true },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Icon(
                                imageVector = when (entry.type) {
                                    EntryType.PAGE -> Icons.Filled.Campaign
                                    EntryType.SPEED_DIAL -> Icons.Filled.Star
                                    EntryType.CONTACT -> Icons.Filled.Call
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(entry.name, fontWeight = FontWeight.Medium)
                                Text(
                                    text = when (entry.type) {
                                        EntryType.PAGE -> "Page  •  dials ${entry.dialString()}"
                                        EntryType.SPEED_DIAL -> "Speed dial  •  ${entry.number}"
                                        EntryType.CONTACT -> entry.number
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { vm.engine.dial(entry.dialString()) }) {
                                Icon(
                                    Icons.Filled.Call, "Call",
                                    tint = au.josh.unifiphone.ui.theme.SuccessGreen,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        EntryDialog(
            initial = editing,
            onDismiss = { showDialog = false },
            onSave = { vm.saveEntry(it); showDialog = false },
            onDelete = editing?.let { e -> { vm.deleteEntry(e.id); showDialog = false } },
        )
    }
}

@Composable
private fun EntryDialog(
    initial: DirectoryEntry?,
    onDismiss: () -> Unit,
    onSave: (DirectoryEntry) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var number by remember { mutableStateOf(initial?.number ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: EntryType.CONTACT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New entry" else "Edit entry") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = number, onValueChange = { number = it },
                    label = { Text(if (type == EntryType.PAGE) "Group extension" else "Number / extension") },
                    singleLine = true,
                    supportingText = if (type == EntryType.PAGE) {
                        { Text("Dialled as *0*${number.ifBlank { "<ext>" }}") }
                    } else null,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == EntryType.CONTACT,
                        onClick = { type = EntryType.CONTACT },
                        label = { Text("Contact") },
                    )
                    FilterChip(
                        selected = type == EntryType.SPEED_DIAL,
                        onClick = { type = EntryType.SPEED_DIAL },
                        label = { Text("Speed dial") },
                    )
                    FilterChip(
                        selected = type == EntryType.PAGE,
                        onClick = { type = EntryType.PAGE },
                        label = { Text("Page") },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && number.isNotBlank(),
                onClick = {
                    onSave(
                        DirectoryEntry(
                            id = initial?.id ?: java.util.UUID.randomUUID().toString(),
                            name = name.trim(),
                            number = number.trim(),
                            type = type,
                        )
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
