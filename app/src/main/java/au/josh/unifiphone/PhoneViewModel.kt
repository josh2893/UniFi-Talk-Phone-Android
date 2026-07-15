package au.josh.unifiphone

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import au.josh.unifiphone.data.AppSettings
import au.josh.unifiphone.data.DirectoryEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class PhoneViewModel(app: Application) : AndroidViewModel(app) {

    private val application get() = getApplication<App>()
    val engine get() = application.sipEngine
    val directory get() = application.directoryRepo
    private val settingsRepo get() = application.settingsRepo

    val settings = settingsRepo.settings.stateIn(
        viewModelScope, SharingStarted.Eagerly, AppSettings()
    )

    fun updateSettings(transform: (AppSettings) -> AppSettings) =
        viewModelScope.launch { settingsRepo.update(transform) }

    /** Write current settings to a JSON file in external files dir; returns path. */
    fun exportSettings(): String {
        val dir = application.getExternalFilesDir(null) ?: application.filesDir
        val f = java.io.File(dir, "unifiphone-settings-backup.json")
        f.writeText(settings.value.toBackupJson())
        return f.absolutePath
    }

    /** Restore settings from the backup file if present; returns success. */
    fun importSettings(): Boolean {
        val dir = application.getExternalFilesDir(null) ?: application.filesDir
        val f = java.io.File(dir, "unifiphone-settings-backup.json")
        if (!f.exists()) return false
        return runCatching {
            val restored = appSettingsFromBackupJson(f.readText(), settings.value)
            viewModelScope.launch { settingsRepo.update { restored } }
            true
        }.getOrDefault(false)
    }

    fun saveEntry(entry: DirectoryEntry) = viewModelScope.launch { directory.upsert(entry) }
    fun deleteEntry(id: String) = viewModelScope.launch { directory.delete(id) }
    fun clearHistory() = viewModelScope.launch { directory.clearHistory() }

    /** Copy a user-picked audio file into app storage and select it as ringtone. */
    fun importRingtone(bytes: ByteArray, fileName: String) = viewModelScope.launch {
        val dir = File(application.filesDir, "ringtones").apply { mkdirs() }
        val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val out = File(dir, safe)
        out.writeBytes(bytes)
        settingsRepo.update { it.copy(ringtone = "file:${out.absolutePath}") }
    }
}
