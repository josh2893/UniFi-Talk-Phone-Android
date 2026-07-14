package au.josh.unifiphone.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.view.Surface
import au.josh.unifiphone.core.engine.AudioStream
import au.josh.unifiphone.core.engine.EngineLog
import au.josh.unifiphone.core.engine.RtpSession
import au.josh.unifiphone.core.engine.Sdp
import au.josh.unifiphone.core.engine.SdpSession
import au.josh.unifiphone.core.engine.SipClient
import au.josh.unifiphone.core.engine.SipMessage
import au.josh.unifiphone.core.engine.VideoReceiver
import au.josh.unifiphone.core.engine.VideoSender
import au.josh.unifiphone.data.AppSettings
import au.josh.unifiphone.data.CallRecord
import au.josh.unifiphone.data.DirectoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

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
    /** True when a video stream is negotiated on this call. */
    val videoActive: Boolean = false,
)

/**
 * SIP engine v2 — pure-Kotlin SIP/RTP stack replacing the Linphone SDK.
 *
 * Wire format is exactly what UniFi Talk handsets speak (verified via fs_cli
 * captures of handset<->handset video calls):
 *   - SIP over UDP, digest auth on REGISTER and INVITE
 *   - plain RTP/AVP (no SRTP/ICE/DTLS) — Talk's route_to_video dialplan sets
 *     bypass_media=true so SDP travels end-to-end
 *   - audio: PCMU (G.711 µ-law) + RFC2833 DTMF
 *   - video: H.265 payload 96, RFC 7798, PLI/FIR keyframe requests
 */
class SipEngine(
    private val context: Context,
    private val directory: DirectoryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var sip: SipClient? = null
    private var currentSettings: AppSettings? = null

    // Per-call media state
    private var dialog: SipClient.Dialog? = null
    private var audioRtp: RtpSession? = null
    private var videoRtp: RtpSession? = null
    private var audio: AudioStream? = null
    private var videoRx: VideoReceiver? = null
    private var videoTx: VideoSender? = null
    private var pendingOffer: SdpSession? = null
    private var pendingRemoteSurface: Surface? = null

    private var ringPlayer: MediaPlayer? = null
    private var ringbackTone: ToneGenerator? = null
    private val audioManager get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var savedAlarmVolume: Int? = null

    private val _regState = MutableStateFlow(RegState.NONE)
    val regState: StateFlow<RegState> = _regState
    private val _regDetail = MutableStateFlow("")
    val regDetail: StateFlow<String> = _regDetail
    private val _callState = MutableStateFlow(CallUiState())
    val callState: StateFlow<CallUiState> = _callState

    private var callAnsweredAt = 0L
    private var callWasIncoming = false

    fun start() { /* stack starts on applySettings */ }

    fun applySettings(s: AppSettings) {
        currentSettings = s
        sip?.stop(); sip = null
        if (s.sipServer.isBlank() || s.sipUsername.isBlank()) {
            _regState.value = RegState.NONE
            _regDetail.value = "Not configured"
            return
        }
        _regState.value = RegState.PROGRESS
        _regDetail.value = "Registering…"
        val client = SipClient(
            server = s.sipServer,
            serverPort = s.sipPort.toIntOrNull() ?: 5060,
            domain = s.sipDomain.ifBlank { "talk.com" },
            user = s.sipUsername,
            password = s.sipPassword,
            listener = sipListener,
            tracer = ::traceSip,
        )
        EngineLog.sink = { line -> traceSip("### $line") }
        sip = client
        Thread { client.start() }.start()
    }

    // ---- Call control -------------------------------------------------

    fun dial(rawTarget: String) {
        val s = currentSettings ?: return
        val client = sip ?: return
        if (rawTarget.isBlank() || dialog != null) return

        val withVideo = s.videoCalls
        setupMedia(video = withVideo)
        val sdp = Sdp.build(
            localIp = client.localIp,
            user = s.sipUsername,
            audioPort = audioRtp!!.localRtpPort,
            videoPort = if (withVideo) videoRtp!!.localRtpPort else null,
            sessionId = Random.nextLong(1000, 99999),
            sessionVersion = Random.nextLong(1000, 99999),
        )
        callWasIncoming = false
        callAnsweredAt = 0L
        dialog = client.invite(rawTarget, sdp)
        dialog?.localSdp = sdp
        _callState.value = CallUiState(
            active = true, incoming = false,
            remoteNumber = rawTarget,
            remoteName = directory.lookupName(rawTarget),
            videoActive = withVideo,
        )
    }

    fun accept() {
        val d = dialog ?: return
        val client = sip ?: return
        val s = currentSettings ?: return
        val offer = pendingOffer ?: return
        stopRinging()

        // Video is answered whenever offered — toggle only controls outgoing.
        val remoteVideo = offer.video()
        val withVideo = remoteVideo != null
        setupMedia(video = withVideo)

        val audioOffer = offer.audio()
        audioOffer?.let { audioRtp?.setRemote(offer.remoteIp, it.port, it.rtcpPort) }
        remoteVideo?.let { videoRtp?.setRemote(offer.remoteIp, it.port, it.rtcpPort) }

        // Codec negotiation: the answer must be a SUBSET of the offer.
        // Talk's media path frequently offers PCMA-only (m=audio ... 8 101 13).
        val chosenPt = when {
            audioOffer == null -> Sdp.PT_PCMU
            Sdp.PT_PCMU in audioOffer.payloadTypes -> Sdp.PT_PCMU
            Sdp.PT_PCMA in audioOffer.payloadTypes -> Sdp.PT_PCMA
            else -> { decline(); return }
        }
        audio?.txPayloadType = chosenPt

        val answer = Sdp.build(
            localIp = client.localIp,
            user = s.sipUsername,
            audioPort = audioRtp!!.localRtpPort,
            videoPort = if (withVideo) videoRtp!!.localRtpPort else null,
            sessionId = Random.nextLong(1000, 99999),
            sessionVersion = Random.nextLong(1000, 99999),
            audioPayloads = listOf(chosenPt),
            dtmfPt = audioOffer?.dtmfPt8k() ?: Sdp.PT_DTMF,
        )
        client.accept(d, answer)
        startMedia(sendVideo = withVideo)
        callAnsweredAt = System.currentTimeMillis()
        _callState.value = _callState.value.copy(
            incoming = false, connected = true,
            startedAtMs = callAnsweredAt, videoActive = withVideo,
        )
    }

    fun hangup() {
        val d = dialog ?: return
        stopRinging()
        sip?.bye(d)
        // onCallEnded will tear down media
    }

    fun decline() {
        val d = dialog ?: return
        stopRinging()
        sip?.decline(d)
    }

    fun toggleMute() {
        val a = audio ?: return
        a.muted = !a.muted
        _callState.value = _callState.value.copy(muted = a.muted)
    }

    /**
     * v1 hold = local mute of both directions. A SIP hold (re-INVITE
     * a=sendonly) is on the roadmap; this keeps the dialog untouched.
     */
    fun toggleHold() {
        val holding = !_callState.value.onHold
        audio?.muted = holding
        _callState.value = _callState.value.copy(onHold = holding, muted = holding)
    }

    fun toggleSpeaker() {
        val want = !_callState.value.speaker
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = want
        _callState.value = _callState.value.copy(speaker = want)
    }

    fun sendDtmf(digits: String) {
        for (c in digits) audio?.sendDtmf(c)
    }

    /** CallScreen hands us the SurfaceView surface for remote video. */
    fun attachRemoteVideoSurface(surface: Surface) {
        pendingRemoteSurface = surface
        videoRx?.attachSurface(surface)
    }

    // ---- SIP listener ---------------------------------------------------

    private val sipListener = object : SipClient.Listener {
        override fun onRegistered() {
            _regState.value = RegState.OK
            _regDetail.value = "Registered"
        }

        override fun onRegistrationFailed(reason: String) {
            _regState.value = RegState.FAILED
            _regDetail.value = reason
        }

        override fun onIncomingCall(
            call: SipClient.Dialog, from: String, fromDisplay: String?,
            offer: SdpSession, rawInvite: SipMessage,
        ) {
            if (dialog != null) { sip?.decline(call); return }
            dialog = call
            pendingOffer = offer
            callWasIncoming = true
            callAnsweredAt = 0L
            dumpIncomingHeaders(rawInvite)
            scope.launch {
                startRinging()
                _callState.value = CallUiState(
                    active = true, incoming = true,
                    remoteNumber = from,
                    remoteName = directory.lookupName(from) ?: fromDisplay,
                    videoActive = offer.video() != null,
                )
            }
        }

        override fun onCallRinging(call: SipClient.Dialog) {
            // Local ringback: the app doesn't render early media, so give the
            // caller audible feedback that the far end is ringing.
            scope.launch { startRingback() }
        }

        override fun onCallAnswered(call: SipClient.Dialog, answer: SdpSession) {
            val audioMedia = answer.audio() ?: run { hangup(); return }
            audioRtp?.setRemote(answer.remoteIp, audioMedia.port, audioMedia.rtcpPort)
            // Send with the codec the far end picked in its answer.
            audio?.txPayloadType = when {
                Sdp.PT_PCMU in audioMedia.payloadTypes -> Sdp.PT_PCMU
                Sdp.PT_PCMA in audioMedia.payloadTypes -> Sdp.PT_PCMA
                else -> Sdp.PT_PCMU
            }
            val videoMedia = answer.video()
            val videoUp = videoMedia != null && videoRtp != null
            videoMedia?.let { videoRtp?.setRemote(answer.remoteIp, it.port, it.rtcpPort) }
            startMedia(sendVideo = videoUp)
            callAnsweredAt = System.currentTimeMillis()
            scope.launch {
                stopRingback()
                _callState.value = _callState.value.copy(
                    connected = true, startedAtMs = callAnsweredAt,
                    videoActive = videoUp,
                )
            }
        }

        override fun onCallEnded(call: SipClient.Dialog, reason: String) {
            if (call.callId != dialog?.callId) return
            scope.launch {
                stopRinging()
                stopRingback()
                if (_callState.value.active) recordHistory(_callState.value.remoteNumber)
                teardownMedia()
                dialog = null
                pendingOffer = null
                _callState.value = CallUiState()
            }
        }
    }

    // ---- Media plumbing -------------------------------------------------

    private fun setupMedia(video: Boolean) {
        teardownMedia()
        val aPort = RtpSession.allocatePortPair()
        audioRtp = RtpSession(aPort, onPacket = { pt, _, _, _, payload ->
            audio?.onRtpAudio(pt, payload)
        })
        audio = AudioStream(audioRtp!!)
        if (video) {
            val vPort = RtpSession.allocatePortPair()
            videoRtp = RtpSession(
                vPort,
                onPacket = { pt, marker, seq, _, payload ->
                    if (pt == Sdp.PT_H265) videoRx?.onRtpVideo(seq, marker, payload)
                },
                onKeyframeRequest = { videoTx?.requestKeyframe() },
            )
            videoRx = VideoReceiver(videoRtp!!)
            videoTx = VideoSender(context, videoRtp!!)
            pendingRemoteSurface?.let { videoRx?.attachSurface(it) }
        }
    }

    private fun startMedia(sendVideo: Boolean) {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioRtp?.start()
        audio?.start()
        if (videoRtp != null) {
            videoRtp?.start()
            videoRx?.start()
            if (sendVideo) videoTx?.start()
        }
    }

    private fun teardownMedia() {
        runCatching { audio?.stop() }
        runCatching { videoTx?.stop() }
        runCatching { videoRx?.stop() }
        runCatching { audioRtp?.close() }
        runCatching { videoRtp?.close() }
        audio = null; videoTx = null; videoRx = null
        audioRtp = null; videoRtp = null
        audioManager.mode = AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
    }

    // ---- Ringing (unchanged behaviour from v1) ---------------------------

    private fun resolveRingtonePath(spec: String): String? = when {
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
        spec.startsWith("file:") -> spec.removePrefix("file:").takeIf { File(it).exists() }
        else -> null
    }

    private fun startRinging() {
        stopRinging()
        val spec = currentSettings?.ringtone ?: "raw:ringtone_classic"
        val path = resolveRingtonePath(spec) ?: return
        runCatching {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            savedAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
            ringPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(path)
                isLooping = true
                setVolume(1f, 1f)
                prepare()
                start()
            }
        }
    }

    private fun startRingback() {
        if (ringbackTone != null) return
        runCatching {
            ringbackTone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).also {
                it.startTone(ToneGenerator.TONE_SUP_RINGTONE)
            }
        }
    }

    private fun stopRingback() {
        runCatching { ringbackTone?.stopTone(); ringbackTone?.release() }
        ringbackTone = null
    }

    private fun stopRinging() {
        ringPlayer?.runCatching {
            if (isPlaying) stop()
            release()
        }
        ringPlayer = null
        savedAlarmVolume?.let {
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) }
            savedAlarmVolume = null
        }
    }

    // ---- Diagnostics / history -------------------------------------------

    /** Full SIP wire trace -> sip_debug.log (capped at ~2 MB). */
    private fun traceSip(text: String) {
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val f = File(dir, "sip_debug.log")
            if (f.length() > 2_000_000) f.writeText("")
            f.appendText("---- ${System.currentTimeMillis()} ----\n$text\n")
        }
    }

    private fun dumpIncomingHeaders(invite: SipMessage) {
        runCatching {
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val sb = StringBuilder()
            sb.appendLine("==== INCOMING CALL $ts ====")
            val candidates = listOf(
                "To", "From", "Contact",
                "P-Called-Party-ID", "P-Asserted-Identity", "P-Preferred-Identity",
                "Diversion", "History-Info", "Referred-By",
                "X-Group", "X-Group-Name", "Alert-Info", "Call-Info",
                "Subject", "Remote-Party-ID",
            )
            for (h in candidates) {
                for (v in invite.headerAll(h)) sb.appendLine("HDR $h = $v")
            }
            sb.appendLine("SDP:").appendLine(String(invite.body))
            sb.appendLine()
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            File(dir, "sip_debug.log").appendText(sb.toString())
        }
    }

    private fun recordHistory(remote: String) {
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
