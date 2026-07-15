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
    val sipServer: String = "",          // Talk console IP or hostname (transport target)
    val sipPort: String = "5060",
    // SIP domain used inside From/To/Request-URI. UniFi Talk uses "talk.com".
    // This is NOT the console IP — sofia routes on the domain, and an IP here
    // means INVITEs are silently dropped even though REGISTER succeeds.
    val sipDomain: String = "talk.com",
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

    // DEBUG: show an "Add video" button on connected audio-only calls, which
    // sends a re-INVITE with an H.265 m-line. Experiment: Talk rejects video
    // INVITEs to ring groups outright, but a group call that answered as audio
    // MIGHT accept a mid-call video upgrade (bypass_media makes the media path
    // peer-to-peer once bridged). Off by default.
    val videoUpgradeDebug: Boolean = false,

    // ---- Live video tuning (applied at call setup; no rebuild needed) ----
    // Rotation added to the automatic sensor calc: 0/90/180/270.
    val videoRotationOffset: Int = 270,  // 270 confirmed upright on test device
    // Extra mirror toggle on top of the automatic front-camera mirror.
    val videoMirror: Boolean = false,
    // Which physical camera to use for outgoing video.
    val videoUseFrontCamera: Boolean = true,
    // Target encode resolution (short edge). 0 = auto-pick nearest supported.
    // Common: 240, 360, 480, 720. The nearest advertised size is chosen.
    val videoResolution: Int = 0,
    // Encoder bitrate in kbps.
    val videoBitrateKbps: Int = 800,
    // Scale/crop mode for how the camera frame fills the encoded frame.
    // "fit" = letterbox (whole frame, bars), "fill" = center-crop (fills, trims).
    val videoScaleMode: String = "fill",
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val SERVER = stringPreferencesKey("sip_server")
        val PORT = stringPreferencesKey("sip_port")
        val DOMAIN = stringPreferencesKey("sip_domain")
        val USER = stringPreferencesKey("sip_user")
        val PASS = stringPreferencesKey("sip_pass")
        val TRANSPORT = stringPreferencesKey("sip_transport")
        val LABEL = stringPreferencesKey("phone_label")
        val SHOW_MISSED = booleanPreferencesKey("show_missed")
        val THEME = stringPreferencesKey("theme_mode")
        val KIOSK = booleanPreferencesKey("kiosk_enabled")
        val RINGTONE = stringPreferencesKey("ringtone")
        val VIDEO_CALLS = booleanPreferencesKey("video_calls")
        val VIDEO_UPGRADE_DEBUG = booleanPreferencesKey("video_upgrade_debug")
        val VIDEO_ROTATION = androidx.datastore.preferences.core.intPreferencesKey("video_rotation_offset")
        val VIDEO_MIRROR = booleanPreferencesKey("video_mirror")
        val VIDEO_FRONT = booleanPreferencesKey("video_front_camera")
        val VIDEO_RES = androidx.datastore.preferences.core.intPreferencesKey("video_resolution")
        val VIDEO_BITRATE = androidx.datastore.preferences.core.intPreferencesKey("video_bitrate")
        val VIDEO_SCALE = stringPreferencesKey("video_scale_mode")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            sipServer = p[Keys.SERVER] ?: "",
            sipPort = p[Keys.PORT] ?: "5060",
            sipDomain = p[Keys.DOMAIN]?.takeIf { it.isNotBlank() } ?: "talk.com",
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
            videoUpgradeDebug = p[Keys.VIDEO_UPGRADE_DEBUG] ?: false,
            videoRotationOffset = p[Keys.VIDEO_ROTATION] ?: 0,
            videoMirror = p[Keys.VIDEO_MIRROR] ?: false,
            videoUseFrontCamera = p[Keys.VIDEO_FRONT] ?: true,
            videoResolution = p[Keys.VIDEO_RES] ?: 0,
            videoBitrateKbps = p[Keys.VIDEO_BITRATE] ?: 800,
            videoScaleMode = p[Keys.VIDEO_SCALE] ?: "fill",
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(current())
        context.dataStore.edit { p ->
            p[Keys.SERVER] = next.sipServer
            p[Keys.PORT] = next.sipPort
            p[Keys.DOMAIN] = next.sipDomain
            p[Keys.USER] = next.sipUsername
            p[Keys.PASS] = next.sipPassword
            p[Keys.TRANSPORT] = next.transport.name
            p[Keys.LABEL] = next.phoneLabel
            p[Keys.SHOW_MISSED] = next.showMissedCalls
            p[Keys.THEME] = next.themeMode.name
            p[Keys.KIOSK] = next.kioskEnabled
            p[Keys.RINGTONE] = next.ringtone
            p[Keys.VIDEO_CALLS] = next.videoCalls
            p[Keys.VIDEO_UPGRADE_DEBUG] = next.videoUpgradeDebug
            p[Keys.VIDEO_ROTATION] = next.videoRotationOffset
            p[Keys.VIDEO_MIRROR] = next.videoMirror
            p[Keys.VIDEO_FRONT] = next.videoUseFrontCamera
            p[Keys.VIDEO_RES] = next.videoResolution
            p[Keys.VIDEO_BITRATE] = next.videoBitrateKbps
            p[Keys.VIDEO_SCALE] = next.videoScaleMode
        }
    }
}
