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

/** Serialize AppSettings to a flat JSON string for backup. */
fun AppSettings.toBackupJson(): String {
    val o = org.json.JSONObject()
    o.put("sipServer", sipServer); o.put("sipPort", sipPort); o.put("sipDomain", sipDomain)
    o.put("sipUsername", sipUsername); o.put("sipPassword", sipPassword)
    o.put("transport", transport.name); o.put("phoneLabel", phoneLabel)
    o.put("showMissedCalls", showMissedCalls); o.put("themeMode", themeMode.name)
    o.put("kioskEnabled", kioskEnabled); o.put("ringtone", ringtone)
    o.put("videoCalls", videoCalls); o.put("videoUpgradeDebug", videoUpgradeDebug)
    o.put("videoRotationOffset", videoRotationOffset); o.put("videoMirror", videoMirror)
    o.put("videoUseFrontCamera", videoUseFrontCamera); o.put("videoResolution", videoResolution)
    o.put("videoBitrateKbps", videoBitrateKbps); o.put("videoScaleMode", videoScaleMode); o.put("videoTargetAspect", videoTargetAspect)
    o.put("videoStretchFixPercent", videoStretchFixPercent)
    o.put("videoReceiveStretchFixPercent", videoReceiveStretchFixPercent)
    o.put("showDebugOverlay", showDebugOverlay)
    o.put("doorbellEnabled", doorbellEnabled)
    o.put("doorbellBanner", doorbellBanner); o.put("doorbellTitle", doorbellTitle)
    o.put("doorbellAddress", doorbellAddress); o.put("doorbellInstruction", doorbellInstruction)
    o.put("doorbellTarget", doorbellTarget); o.put("doorbellAdminPin", doorbellAdminPin)
    o.put("doorbellChimeUntilCallEnds", doorbellChimeUntilCallEnds)
    o.put("doorbellChimeCount", doorbellChimeCount)
    o.put("doorbellNoAnswerMessage", doorbellNoAnswerMessage)
    o.put("doorbellIdleMessage", doorbellIdleMessage)
    o.put("doorbellMessageEnabled", doorbellMessageEnabled)
    o.put("doorbellDeliveryEnabled", doorbellDeliveryEnabled)
    o.put("doorbellDeliveryInstructions", doorbellDeliveryInstructions)
    o.put("doorbellDeliveryThankYou", doorbellDeliveryThankYou)
    o.put("doorbellDeliveryPerson1Name", doorbellDeliveryPerson1Name)
    o.put("doorbellDeliveryPerson1Webhook", doorbellDeliveryPerson1Webhook)
    o.put("doorbellDeliveryPerson2Name", doorbellDeliveryPerson2Name)
    o.put("doorbellDeliveryPerson2Webhook", doorbellDeliveryPerson2Webhook)
    o.put("doorbellDeliveryPerson3Name", doorbellDeliveryPerson3Name)
    o.put("doorbellDeliveryPerson3Webhook", doorbellDeliveryPerson3Webhook)
    o.put("doorbellDeliveryOtherName", doorbellDeliveryOtherName)
    o.put("doorbellDeliveryOtherWebhook", doorbellDeliveryOtherWebhook)
    o.put("doorbellDeliveryApiKeyHeader", doorbellDeliveryApiKeyHeader)
    o.put("doorbellDeliveryApiKey", doorbellDeliveryApiKey)
    o.put("webManagementEnabled", webManagementEnabled)
    o.put("webManagementPort", webManagementPort)
    return o.toString(2)
}

/** Parse a backup JSON string into AppSettings, keeping defaults for missing keys. */
fun appSettingsFromBackupJson(json: String, base: AppSettings = AppSettings()): AppSettings {
    val o = org.json.JSONObject(json)
    fun str(k: String, d: String) = if (o.has(k)) o.getString(k) else d
    fun bool(k: String, d: Boolean) = if (o.has(k)) o.getBoolean(k) else d
    fun int(k: String, d: Int) = if (o.has(k)) o.getInt(k) else d
    return base.copy(
        sipServer = str("sipServer", base.sipServer),
        sipPort = str("sipPort", base.sipPort),
        sipDomain = str("sipDomain", base.sipDomain),
        sipUsername = str("sipUsername", base.sipUsername),
        sipPassword = str("sipPassword", base.sipPassword),
        transport = runCatching { Transport.valueOf(str("transport", base.transport.name)) }.getOrDefault(base.transport),
        phoneLabel = str("phoneLabel", base.phoneLabel),
        showMissedCalls = bool("showMissedCalls", base.showMissedCalls),
        themeMode = runCatching { ThemeMode.valueOf(str("themeMode", base.themeMode.name)) }.getOrDefault(base.themeMode),
        kioskEnabled = bool("kioskEnabled", base.kioskEnabled),
        ringtone = str("ringtone", base.ringtone),
        videoCalls = bool("videoCalls", base.videoCalls),
        videoUpgradeDebug = bool("videoUpgradeDebug", base.videoUpgradeDebug),
        videoRotationOffset = int("videoRotationOffset", base.videoRotationOffset),
        videoMirror = bool("videoMirror", base.videoMirror),
        videoUseFrontCamera = bool("videoUseFrontCamera", base.videoUseFrontCamera),
        videoResolution = int("videoResolution", base.videoResolution),
        videoBitrateKbps = int("videoBitrateKbps", base.videoBitrateKbps),
        videoScaleMode = str("videoScaleMode", base.videoScaleMode),
        videoTargetAspect = str("videoTargetAspect", base.videoTargetAspect),
        videoStretchFixPercent = int("videoStretchFixPercent", base.videoStretchFixPercent),
        videoReceiveStretchFixPercent = int("videoReceiveStretchFixPercent", base.videoReceiveStretchFixPercent),
        showDebugOverlay = bool("showDebugOverlay", base.showDebugOverlay),
        doorbellEnabled = bool("doorbellEnabled", base.doorbellEnabled),
        doorbellBanner = str("doorbellBanner", base.doorbellBanner),
        doorbellTitle = str("doorbellTitle", base.doorbellTitle),
        doorbellAddress = str("doorbellAddress", base.doorbellAddress),
        doorbellInstruction = str("doorbellInstruction", base.doorbellInstruction),
        doorbellTarget = str("doorbellTarget", base.doorbellTarget),
        doorbellAdminPin = str("doorbellAdminPin", base.doorbellAdminPin),
        doorbellChimeUntilCallEnds = bool("doorbellChimeUntilCallEnds", base.doorbellChimeUntilCallEnds),
        doorbellChimeCount = int("doorbellChimeCount", base.doorbellChimeCount),
        doorbellNoAnswerMessage = str("doorbellNoAnswerMessage", base.doorbellNoAnswerMessage),
        doorbellIdleMessage = str("doorbellIdleMessage", base.doorbellIdleMessage),
        doorbellMessageEnabled = bool("doorbellMessageEnabled", base.doorbellMessageEnabled),
        doorbellDeliveryEnabled = bool("doorbellDeliveryEnabled", base.doorbellDeliveryEnabled),
        doorbellDeliveryInstructions = str("doorbellDeliveryInstructions", base.doorbellDeliveryInstructions),
        doorbellDeliveryThankYou = str("doorbellDeliveryThankYou", base.doorbellDeliveryThankYou),
        doorbellDeliveryPerson1Name = str("doorbellDeliveryPerson1Name", base.doorbellDeliveryPerson1Name),
        doorbellDeliveryPerson1Webhook = str("doorbellDeliveryPerson1Webhook", base.doorbellDeliveryPerson1Webhook),
        doorbellDeliveryPerson2Name = str("doorbellDeliveryPerson2Name", base.doorbellDeliveryPerson2Name),
        doorbellDeliveryPerson2Webhook = str("doorbellDeliveryPerson2Webhook", base.doorbellDeliveryPerson2Webhook),
        doorbellDeliveryPerson3Name = str("doorbellDeliveryPerson3Name", base.doorbellDeliveryPerson3Name),
        doorbellDeliveryPerson3Webhook = str("doorbellDeliveryPerson3Webhook", base.doorbellDeliveryPerson3Webhook),
        doorbellDeliveryOtherName = str("doorbellDeliveryOtherName", base.doorbellDeliveryOtherName),
        doorbellDeliveryOtherWebhook = str("doorbellDeliveryOtherWebhook", base.doorbellDeliveryOtherWebhook),
        doorbellDeliveryApiKeyHeader = str("doorbellDeliveryApiKeyHeader", base.doorbellDeliveryApiKeyHeader),
        doorbellDeliveryApiKey = str("doorbellDeliveryApiKey", base.doorbellDeliveryApiKey),
        webManagementEnabled = bool("webManagementEnabled", base.webManagementEnabled),
        webManagementPort = int("webManagementPort", base.webManagementPort),
    )
}

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
    // Target aspect of the ENCODED frame: "source" (camera), "9:16", "3:4", "1:1".
    // UTP-Touch renders video in a portrait viewport, so default to portrait.
    val videoTargetAspect: String = "9:16",
    // Pre-squeeze outgoing pixels horizontally before encoding. 100 = neutral.
    val videoStretchFixPercent: Int = 100,
    // Squeeze/expand received video on screen. 100 = neutral.
    val videoReceiveStretchFixPercent: Int = 100,
    // Show a live stats overlay (packet/frame counters) during calls.
    val showDebugOverlay: Boolean = false,

    // Dedicated wall-mounted doorbell experience.
    val doorbellEnabled: Boolean = false,
    val doorbellBanner: String = "WELCOME",
    val doorbellTitle: String = "Front Door",
    val doorbellAddress: String = "",
    val doorbellInstruction: String = "Please press the button below to ring the doorbell.",
    // One or more extensions separated by commas. Calls are always placed as video.
    val doorbellTarget: String = "",
    val doorbellAdminPin: String = "1234",
    val doorbellChimeUntilCallEnds: Boolean = false,
    val doorbellChimeCount: Int = 2,
    val doorbellNoAnswerMessage: String = "Sorry, no one is available right now.",
    val doorbellIdleMessage: String = "We are ready when you are.",
    val doorbellMessageEnabled: Boolean = false,
    val doorbellDeliveryEnabled: Boolean = false,
    val doorbellDeliveryInstructions: String =
        "Select who the delivery is for so they can be notified.\n\n" +
            "Please leave the package in a safe place near the front door.",
    val doorbellDeliveryThankYou: String = "Thank you. Your delivery notification has been sent.",
    val doorbellDeliveryPerson1Name: String = "Person 1",
    val doorbellDeliveryPerson1Webhook: String = "",
    val doorbellDeliveryPerson2Name: String = "Person 2",
    val doorbellDeliveryPerson2Webhook: String = "",
    val doorbellDeliveryPerson3Name: String = "Person 3",
    val doorbellDeliveryPerson3Webhook: String = "",
    val doorbellDeliveryOtherName: String = "Someone else",
    val doorbellDeliveryOtherWebhook: String = "",
    val doorbellDeliveryApiKeyHeader: String = "X-API-Key",
    val doorbellDeliveryApiKey: String = "",

    // PIN-protected administration interface served on the local network.
    val webManagementEnabled: Boolean = false,
    val webManagementPort: Int = 8080,
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
        val VIDEO_ASPECT = stringPreferencesKey("video_target_aspect")
        val VIDEO_STRETCH_FIX = androidx.datastore.preferences.core.intPreferencesKey("video_stretch_fix_percent")
        val VIDEO_RECEIVE_STRETCH_FIX = androidx.datastore.preferences.core.intPreferencesKey("video_receive_stretch_fix_percent")
        val DEBUG_OVERLAY = booleanPreferencesKey("debug_overlay")
        val DOORBELL_ENABLED = booleanPreferencesKey("doorbell_enabled")
        val DOORBELL_BANNER = stringPreferencesKey("doorbell_banner")
        val DOORBELL_TITLE = stringPreferencesKey("doorbell_title")
        val DOORBELL_ADDRESS = stringPreferencesKey("doorbell_address")
        val DOORBELL_INSTRUCTION = stringPreferencesKey("doorbell_instruction")
        val DOORBELL_TARGET = stringPreferencesKey("doorbell_target")
        val DOORBELL_ADMIN_PIN = stringPreferencesKey("doorbell_admin_pin")
        val DOORBELL_CHIME_UNTIL_END = booleanPreferencesKey("doorbell_chime_until_end")
        val DOORBELL_CHIME_COUNT = androidx.datastore.preferences.core.intPreferencesKey("doorbell_chime_count")
        val DOORBELL_NO_ANSWER = stringPreferencesKey("doorbell_no_answer_message")
        val DOORBELL_IDLE_MESSAGE = stringPreferencesKey("doorbell_idle_message")
        val DOORBELL_MESSAGE_ENABLED = booleanPreferencesKey("doorbell_message_enabled")
        val DOORBELL_DELIVERY_ENABLED = booleanPreferencesKey("doorbell_delivery_enabled")
        val DOORBELL_DELIVERY_INSTRUCTIONS = stringPreferencesKey("doorbell_delivery_instructions")
        val DOORBELL_DELIVERY_THANK_YOU = stringPreferencesKey("doorbell_delivery_thank_you")
        val DOORBELL_DELIVERY_PERSON_1_NAME = stringPreferencesKey("doorbell_delivery_person_1_name")
        val DOORBELL_DELIVERY_PERSON_1_WEBHOOK = stringPreferencesKey("doorbell_delivery_person_1_webhook")
        val DOORBELL_DELIVERY_PERSON_2_NAME = stringPreferencesKey("doorbell_delivery_person_2_name")
        val DOORBELL_DELIVERY_PERSON_2_WEBHOOK = stringPreferencesKey("doorbell_delivery_person_2_webhook")
        val DOORBELL_DELIVERY_PERSON_3_NAME = stringPreferencesKey("doorbell_delivery_person_3_name")
        val DOORBELL_DELIVERY_PERSON_3_WEBHOOK = stringPreferencesKey("doorbell_delivery_person_3_webhook")
        val DOORBELL_DELIVERY_OTHER_NAME = stringPreferencesKey("doorbell_delivery_other_name")
        val DOORBELL_DELIVERY_OTHER_WEBHOOK = stringPreferencesKey("doorbell_delivery_other_webhook")
        val DOORBELL_DELIVERY_API_KEY_HEADER = stringPreferencesKey("doorbell_delivery_api_key_header")
        val DOORBELL_DELIVERY_API_KEY = stringPreferencesKey("doorbell_delivery_api_key")
        val WEB_MANAGEMENT_ENABLED = booleanPreferencesKey("web_management_enabled")
        val WEB_MANAGEMENT_PORT = androidx.datastore.preferences.core.intPreferencesKey("web_management_port")
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
            videoRotationOffset = p[Keys.VIDEO_ROTATION] ?: 270,
            videoMirror = p[Keys.VIDEO_MIRROR] ?: false,
            videoUseFrontCamera = p[Keys.VIDEO_FRONT] ?: true,
            videoResolution = p[Keys.VIDEO_RES] ?: 0,
            videoBitrateKbps = p[Keys.VIDEO_BITRATE] ?: 800,
            videoScaleMode = p[Keys.VIDEO_SCALE] ?: "fill",
            videoTargetAspect = p[Keys.VIDEO_ASPECT] ?: "9:16",
            videoStretchFixPercent = p[Keys.VIDEO_STRETCH_FIX] ?: 100,
            videoReceiveStretchFixPercent = p[Keys.VIDEO_RECEIVE_STRETCH_FIX] ?: 100,
            showDebugOverlay = p[Keys.DEBUG_OVERLAY] ?: false,
            doorbellEnabled = p[Keys.DOORBELL_ENABLED] ?: false,
            doorbellBanner = p[Keys.DOORBELL_BANNER] ?: "WELCOME",
            doorbellTitle = p[Keys.DOORBELL_TITLE] ?: "Front Door",
            doorbellAddress = p[Keys.DOORBELL_ADDRESS] ?: "",
            doorbellInstruction = p[Keys.DOORBELL_INSTRUCTION]
                ?: "Please press the button below to ring the doorbell.",
            doorbellTarget = p[Keys.DOORBELL_TARGET] ?: "",
            doorbellAdminPin = p[Keys.DOORBELL_ADMIN_PIN] ?: "1234",
            doorbellChimeUntilCallEnds = p[Keys.DOORBELL_CHIME_UNTIL_END] ?: false,
            doorbellChimeCount = p[Keys.DOORBELL_CHIME_COUNT] ?: 2,
            doorbellNoAnswerMessage = p[Keys.DOORBELL_NO_ANSWER]
                ?: "Sorry, no one is available right now.",
            doorbellIdleMessage = p[Keys.DOORBELL_IDLE_MESSAGE] ?: "We are ready when you are.",
            doorbellMessageEnabled = p[Keys.DOORBELL_MESSAGE_ENABLED] ?: false,
            doorbellDeliveryEnabled = p[Keys.DOORBELL_DELIVERY_ENABLED] ?: false,
            doorbellDeliveryInstructions = p[Keys.DOORBELL_DELIVERY_INSTRUCTIONS]
                ?: "Select who the delivery is for so they can be notified.\n\n" +
                    "Please leave the package in a safe place near the front door.",
            doorbellDeliveryThankYou = p[Keys.DOORBELL_DELIVERY_THANK_YOU]
                ?: "Thank you. Your delivery notification has been sent.",
            doorbellDeliveryPerson1Name = p[Keys.DOORBELL_DELIVERY_PERSON_1_NAME] ?: "Person 1",
            doorbellDeliveryPerson1Webhook = p[Keys.DOORBELL_DELIVERY_PERSON_1_WEBHOOK] ?: "",
            doorbellDeliveryPerson2Name = p[Keys.DOORBELL_DELIVERY_PERSON_2_NAME] ?: "Person 2",
            doorbellDeliveryPerson2Webhook = p[Keys.DOORBELL_DELIVERY_PERSON_2_WEBHOOK] ?: "",
            doorbellDeliveryPerson3Name = p[Keys.DOORBELL_DELIVERY_PERSON_3_NAME] ?: "Person 3",
            doorbellDeliveryPerson3Webhook = p[Keys.DOORBELL_DELIVERY_PERSON_3_WEBHOOK] ?: "",
            doorbellDeliveryOtherName = p[Keys.DOORBELL_DELIVERY_OTHER_NAME] ?: "Someone else",
            doorbellDeliveryOtherWebhook = p[Keys.DOORBELL_DELIVERY_OTHER_WEBHOOK] ?: "",
            doorbellDeliveryApiKeyHeader = p[Keys.DOORBELL_DELIVERY_API_KEY_HEADER] ?: "X-API-Key",
            doorbellDeliveryApiKey = p[Keys.DOORBELL_DELIVERY_API_KEY] ?: "",
            webManagementEnabled = p[Keys.WEB_MANAGEMENT_ENABLED] ?: false,
            webManagementPort = p[Keys.WEB_MANAGEMENT_PORT] ?: 8080,
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
            p[Keys.VIDEO_ASPECT] = next.videoTargetAspect
            p[Keys.VIDEO_STRETCH_FIX] = next.videoStretchFixPercent.coerceIn(40, 140)
            p[Keys.VIDEO_RECEIVE_STRETCH_FIX] = next.videoReceiveStretchFixPercent.coerceIn(40, 140)
            p[Keys.DEBUG_OVERLAY] = next.showDebugOverlay
            p[Keys.DOORBELL_ENABLED] = next.doorbellEnabled
            p[Keys.DOORBELL_BANNER] = next.doorbellBanner
            p[Keys.DOORBELL_TITLE] = next.doorbellTitle
            p[Keys.DOORBELL_ADDRESS] = next.doorbellAddress
            p[Keys.DOORBELL_INSTRUCTION] = next.doorbellInstruction
            p[Keys.DOORBELL_TARGET] = next.doorbellTarget
            p[Keys.DOORBELL_ADMIN_PIN] = next.doorbellAdminPin
            p[Keys.DOORBELL_CHIME_UNTIL_END] = next.doorbellChimeUntilCallEnds
            p[Keys.DOORBELL_CHIME_COUNT] = next.doorbellChimeCount.coerceIn(1, 10)
            p[Keys.DOORBELL_NO_ANSWER] = next.doorbellNoAnswerMessage
            p[Keys.DOORBELL_IDLE_MESSAGE] = next.doorbellIdleMessage
            p[Keys.DOORBELL_MESSAGE_ENABLED] = next.doorbellMessageEnabled
            p[Keys.DOORBELL_DELIVERY_ENABLED] = next.doorbellDeliveryEnabled
            p[Keys.DOORBELL_DELIVERY_INSTRUCTIONS] = next.doorbellDeliveryInstructions
            p[Keys.DOORBELL_DELIVERY_THANK_YOU] = next.doorbellDeliveryThankYou
            p[Keys.DOORBELL_DELIVERY_PERSON_1_NAME] = next.doorbellDeliveryPerson1Name
            p[Keys.DOORBELL_DELIVERY_PERSON_1_WEBHOOK] = next.doorbellDeliveryPerson1Webhook
            p[Keys.DOORBELL_DELIVERY_PERSON_2_NAME] = next.doorbellDeliveryPerson2Name
            p[Keys.DOORBELL_DELIVERY_PERSON_2_WEBHOOK] = next.doorbellDeliveryPerson2Webhook
            p[Keys.DOORBELL_DELIVERY_PERSON_3_NAME] = next.doorbellDeliveryPerson3Name
            p[Keys.DOORBELL_DELIVERY_PERSON_3_WEBHOOK] = next.doorbellDeliveryPerson3Webhook
            p[Keys.DOORBELL_DELIVERY_OTHER_NAME] = next.doorbellDeliveryOtherName
            p[Keys.DOORBELL_DELIVERY_OTHER_WEBHOOK] = next.doorbellDeliveryOtherWebhook
            p[Keys.DOORBELL_DELIVERY_API_KEY_HEADER] = next.doorbellDeliveryApiKeyHeader
            p[Keys.DOORBELL_DELIVERY_API_KEY] = next.doorbellDeliveryApiKey
            p[Keys.WEB_MANAGEMENT_ENABLED] = next.webManagementEnabled
            p[Keys.WEB_MANAGEMENT_PORT] = next.webManagementPort.coerceIn(1024, 65535)
        }
    }
}
