package au.josh.unifiphone.core

import android.content.Context
import au.josh.unifiphone.data.AppSettings
import au.josh.unifiphone.data.CallRecord
import au.josh.unifiphone.data.DirectoryRepository
import au.josh.unifiphone.data.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.linphone.core.Account
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType
import java.io.File

enum class RegState { NONE, PROGRESS, OK, FAILED }

data class CallUiState(
    val active: Boolean = false,
    val incoming: Boolean = false,
    val remoteNumber: String = "",
    val remoteName: String? = null,
    val connected: Boolean = false,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val onHold: Boolean = false,
    val startedAtMs: Long = 0L,
)

/**
 * Thin wrapper around the Linphone Core. One instance for the app's lifetime,
 * owned by [au.josh.unifiphone.App] and kept alive by [SipForegroundService].
 */
class SipEngine(
    private val context: Context,
    private val directory: DirectoryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var core: Core
    private var account: Account? = null
    private var currentSettings: AppSettings? = null

    private val _regState = MutableStateFlow(RegState.NONE)
    val regState: StateFlow<RegState> = _regState

    private val _regDetail = MutableStateFlow("")
    val regDetail: StateFlow<String> = _regDetail

    private val _callState = MutableStateFlow(CallUiState())
    val callState: StateFlow<CallUiState> = _callState

    private var callAnsweredAt = 0L
    private var callWasIncoming = false

    fun start() {
        val factory = Factory.instance()
        factory.setDebugMode(false, "UniFiPhone")
        core = factory.createCore(null, null, context)
        core.isPushNotificationEnabled = false
        core.isVideoCaptureEnabled = false
        core.isVideoDisplayEnabled = false
        core.maxCalls = 2
        core.addListener(listener)
        core.start()
    }

    /** (Re)apply SIP account + ringtone from settings. Safe to call repeatedly. */
    fun applySettings(s: AppSettings) {
        currentSettings = s
        applyRingtone(s.ringtone)

        // Tear down any existing account first
        account?.let { existing ->
            core.removeAccount(existing)
            core.clearAllAuthInfo()
            account = null
        }
        if (s.sipServer.isBlank() || s.sipUsername.isBlank()) {
            _regState.value = RegState.NONE
            _regDetail.value = "Not configured"
            return
        }

        val factory = Factory.instance()
        val transport = when (s.transport) {
            Transport.UDP -> TransportType.Udp
            Transport.TCP -> TransportType.Tcp
            Transport.TLS -> TransportType.Tls
        }

        val params = core.createAccountParams()
        val identity = factory.createAddress("sip:${s.sipUsername}@${s.sipServer}")
        params.identityAddress = identity
        val server = factory.createAddress("sip:${s.sipServer}:${s.sipPort}")
        server?.transport = transport
        params.serverAddress = server
        params.isRegisterEnabled = true
        params.expires = 300

        val auth = factory.createAuthInfo(
            s.sipUsername, null, s.sipPassword, null, null, s.sipServer, null
        )
        core.addAuthInfo(auth)

        val acc = core.createAccount(params)
        core.addAccount(acc)
        core.defaultAccount = acc
        account = acc
    }

    private fun applyRingtone(spec: String) {
        val path: String? = when {
            spec.startsWith("raw:") -> {
                val name = spec.removePrefix("raw:")
                val resId = context.resources.getIdentifier(name, "raw", context.packageName)
                if (resId == 0) null else {
                    val out = File(context.filesDir, "$name.wav")
                    if (!out.exists()) {
                        context.resources.openRawResource(resId).use { input ->
                            out.outputStream().use { input.copyTo(it) }
                        }
                    }
                    out.absolutePath
                }
            }
            spec.startsWith("file:") -> spec.removePrefix("file:")
                .takeIf { File(it).exists() }
            else -> null
        }
        core.ring = path        // Linphone plays this while an incoming call rings
        core.isNativeRingingEnabled = false
    }

    // ---- Call control -------------------------------------------------

    fun dial(rawTarget: String) {
        val s = currentSettings ?: return
        if (rawTarget.isBlank() || s.sipServer.isBlank()) return
        val address = core.interpretUrl("sip:${rawTarget}@${s.sipServer}", false) ?: return
        val params = core.createCallParams(null)
        core.inviteAddressWithParams(address, params ?: return)
    }

    fun accept() { core.currentCall?.accept() }

    fun hangup() {
        core.currentCall?.terminate() ?: core.calls.firstOrNull()?.terminate()
    }

    fun decline() { core.currentCall?.decline(org.linphone.core.Reason.Declined) }

    fun toggleMute() {
        core.isMicEnabled = !core.isMicEnabled
        _callState.value = _callState.value.copy(muted = !core.isMicEnabled)
    }

    fun toggleHold() {
        val call = core.currentCall ?: core.calls.firstOrNull() ?: return
        if (call.state == Call.State.Paused) call.resume() else call.pause()
    }

    fun toggleSpeaker() {
        val call = core.currentCall ?: return
        val want = if (_callState.value.speaker) AudioDevice.Type.Earpiece else AudioDevice.Type.Speaker
        val device = core.audioDevices.firstOrNull {
            it.type == want && it.hasCapability(AudioDevice.Capabilities.CapabilityPlay)
        } ?: return
        call.outputAudioDevice = device
        _callState.value = _callState.value.copy(speaker = want == AudioDevice.Type.Speaker)
    }

    fun sendDtmf(digits: String) {
        core.currentCall?.sendDtmfs(digits)
    }

    // ---- Listener -----------------------------------------------------

    private val listener = object : CoreListenerStub() {
        override fun onAccountRegistrationStateChanged(
            core: Core, account: Account, state: RegistrationState?, message: String
        ) {
            _regDetail.value = message
            _regState.value = when (state) {
                RegistrationState.Ok -> RegState.OK
                RegistrationState.Progress, RegistrationState.Refreshing -> RegState.PROGRESS
                RegistrationState.Failed -> RegState.FAILED
                else -> RegState.NONE
            }
        }

        override fun onCallStateChanged(
            core: Core, call: Call, state: Call.State?, message: String
        ) {
            val remote = call.remoteAddress.username ?: call.remoteAddress.asStringUriOnly()
            when (state) {
                Call.State.IncomingReceived, Call.State.IncomingEarlyMedia -> {
                    callWasIncoming = true
                    callAnsweredAt = 0L
                    _callState.value = CallUiState(
                        active = true, incoming = true,
                        remoteNumber = remote,
                        remoteName = directory.lookupName(remote)
                            ?: call.remoteAddress.displayName,
                    )
                }
                Call.State.OutgoingInit, Call.State.OutgoingProgress, Call.State.OutgoingRinging -> {
                    callWasIncoming = false
                    callAnsweredAt = 0L
                    _callState.value = CallUiState(
                        active = true, incoming = false,
                        remoteNumber = remote,
                        remoteName = directory.lookupName(remote),
                    )
                }
                Call.State.Connected, Call.State.StreamsRunning -> {
                    if (callAnsweredAt == 0L) callAnsweredAt = System.currentTimeMillis()
                    _callState.value = _callState.value.copy(
                        incoming = false, connected = true, onHold = false,
                        startedAtMs = callAnsweredAt,
                        muted = !core.isMicEnabled,
                    )
                }
                Call.State.Paused, Call.State.PausedByRemote -> {
                    _callState.value = _callState.value.copy(onHold = true)
                }
                Call.State.End, Call.State.Error, Call.State.Released -> {
                    if (_callState.value.active) recordHistory(call, remote)
                    _callState.value = CallUiState()
                    core.isMicEnabled = true
                }
                else -> Unit
            }
        }
    }

    private fun recordHistory(call: Call, remote: String) {
        val answered = callAnsweredAt != 0L
        val duration = if (answered)
            ((System.currentTimeMillis() - callAnsweredAt) / 1000).toInt() else 0
        val record = CallRecord(
            number = remote,
            displayName = directory.lookupName(remote),
            timestampMs = System.currentTimeMillis(),
            durationSec = duration,
            direction = if (callWasIncoming) "in" else "out",
            missed = callWasIncoming && !answered,
        )
        scope.launch { directory.addCallRecord(record) }
    }
}
