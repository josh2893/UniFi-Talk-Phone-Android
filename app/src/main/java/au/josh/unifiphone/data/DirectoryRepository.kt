package au.josh.unifiphone.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Contact entry types:
 *  CONTACT    - normal directory entry, dials the number as-is
 *  SPEED_DIAL - pinned to the home screen for one-tap dialling
 *  PAGE       - paging target; dialled as *0*<extension> per UniFi Talk's
 *               paging feature code (page a group extension)
 */
@Serializable
enum class EntryType { CONTACT, SPEED_DIAL, PAGE }

@Serializable
data class DirectoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val number: String,          // extension or full number; for PAGE: the group extension only
    val type: EntryType = EntryType.CONTACT,
) {
    /** The string actually sent to the PBX. */
    fun dialString(): String = when (type) {
        EntryType.PAGE -> "*0*$number"
        else -> number
    }
}

@Serializable
data class CallRecord(
    val id: String = UUID.randomUUID().toString(),
    val number: String,
    val displayName: String? = null,
    val timestampMs: Long,
    val durationSec: Int,
    val direction: String,        // "in" | "out"
    val missed: Boolean,
)

class DirectoryRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val contactsFile get() = File(context.filesDir, "contacts.json")
    private val historyFile get() = File(context.filesDir, "history.json")

    private val _entries = MutableStateFlow<List<DirectoryEntry>>(emptyList())
    val entries: StateFlow<List<DirectoryEntry>> = _entries

    private val _history = MutableStateFlow<List<CallRecord>>(emptyList())
    val history: StateFlow<List<CallRecord>> = _history

    suspend fun load() = withContext(Dispatchers.IO) {
        _entries.value = runCatching {
            json.decodeFromString<List<DirectoryEntry>>(contactsFile.readText())
        }.getOrDefault(emptyList())
        _history.value = runCatching {
            json.decodeFromString<List<CallRecord>>(historyFile.readText())
        }.getOrDefault(emptyList())
    }

    suspend fun upsert(entry: DirectoryEntry) = mutateEntries { list ->
        list.filterNot { it.id == entry.id } + entry
    }

    suspend fun delete(id: String) = mutateEntries { list -> list.filterNot { it.id == id } }

    fun lookupName(number: String): String? =
        _entries.value.firstOrNull { it.number == number || it.dialString() == number }?.name

    suspend fun addCallRecord(record: CallRecord) = withContext(Dispatchers.IO) {
        val next = (listOf(record) + _history.value).take(200)
        _history.value = next
        runCatching { historyFile.writeText(json.encodeToString(next)) }
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        _history.value = emptyList()
        runCatching { historyFile.delete() }
    }

    private suspend fun mutateEntries(transform: (List<DirectoryEntry>) -> List<DirectoryEntry>) =
        withContext(Dispatchers.IO) {
            val next = transform(_entries.value).sortedBy { it.name.lowercase() }
            _entries.value = next
            runCatching { contactsFile.writeText(json.encodeToString(next)) }
        }
}
