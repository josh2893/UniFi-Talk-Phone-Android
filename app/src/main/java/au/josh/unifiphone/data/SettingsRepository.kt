package au.josh.unifiphone.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class Transport { UDP, TCP, TLS }

data class AppSettings(
    // SIP account (create a third-party SIP device in UniFi Talk to obtain these)
    val sipServer: String = "",          // Talk console IP or hostname
    val sipPort: String = "5060",
    val sipUsername: String = "",        // extension / auth user from Talk
    val sipPassword: String = "",
    val transport: Transport = Transport.UDP,

    // Identity shown on the home screen
    val phoneLabel: String = "",         // e.g. "Warehouse - Front Desk"

    // Behaviour
    val showMissedCalls: Boolean = true, // hide when handset sits in a ring group
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val kioskEnabled: Boolean = false,

    // Ringtone: "raw:<resName>" for a bundled tone, "file:<path>" for imported
    val ringtone: String = "raw:ringtone_classic",

    // Video calling: when ON, calls placed from the dial pad are offered as
    // H.265 video calls. When OFF, outgoing calls are audio-only but the
    // phone still ACCEPTS incoming video calls.
    val videoCalls: Boolean = false,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val SERVER = stringPreferencesKey("sip_server")
        val PORT = stringPreferencesKey("sip_port")
        val USER = stringPreferencesKey("sip_user")
        val PASS = stringPreferencesKey("sip_pass")
        val TRANSPORT = stringPreferencesKey("sip_transport")
        val LABEL = stringPreferencesKey("phone_label")
        val SHOW_MISSED = booleanPreferencesKey("show_missed")
        val THEME = stringPreferencesKey("theme_mode")
        val KIOSK = booleanPreferencesKey("kiosk_enabled")
        val RINGTONE = stringPreferencesKey("ringtone")
        val VIDEO_CALLS = booleanPreferencesKey("video_calls")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            sipServer = p[Keys.SERVER] ?: "",
            sipPort = p[Keys.PORT] ?: "5060",
            sipUsername = p[Keys.USER] ?: "",
            sipPassword = p[Keys.PASS] ?: "",
            transport = runCatching { Transport.valueOf(p[Keys.TRANSPORT] ?: "UDP") }
                .getOrDefault(Transport.UDP),
            phoneLabel = p[Keys.LABEL] ?: "",
            showMissedCalls = p[Keys.SHOW_MISSED] ?: true,
            themeMode = runCatching { ThemeMode.valueOf(p[Keys.THEME] ?: "SYSTEM") }
                .getOrDefault(ThemeMode.SYSTEM),
            kioskEnabled = p[Keys.KIOSK] ?: false,
            ringtone = p[Keys.RINGTONE] ?: "raw:ringtone_classic",
            videoCalls = p[Keys.VIDEO_CALLS] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(current())
        context.dataStore.edit { p ->
            p[Keys.SERVER] = next.sipServer
            p[Keys.PORT] = next.sipPort
            p[Keys.USER] = next.sipUsername
            p[Keys.PASS] = next.sipPassword
            p[Keys.TRANSPORT] = next.transport.name
            p[Keys.LABEL] = next.phoneLabel
            p[Keys.SHOW_MISSED] = next.showMissedCalls
            p[Keys.THEME] = next.themeMode.name
            p[Keys.KIOSK] = next.kioskEnabled
            p[Keys.RINGTONE] = next.ringtone
            p[Keys.VIDEO_CALLS] = next.videoCalls
        }
    }
}
