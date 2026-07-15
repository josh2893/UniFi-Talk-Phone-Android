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
    /** Debug: a connected audio-only call that we could try to upgrade to video. */
    val canUpgradeToVideo: Boolean = false,
    /** Debug: last re-INVITE outcome, for the Stage-2 experiment. */
    val upgradeStatus: String? = null,
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

    /**
     * Parallel ring: dialogs currently ringing. No media is set up for these —
     * during ringing nobody is talking, so we only need N signalling dialogs and
     * ZERO media sessions. On first answer we CANCEL the losers and build a
     * single media session for the winner, reusing the normal path.
     */
    private val ringingDialogs = mutableListOf<SipClient.Dialog>()
    private var parallelRingActive = false
    private var pendingVideoUpgrade = false
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
        if (rawTarget.isBlank() || dialog != null || parallelRingActive) return

        // A ring list ("10, 11, 12") fans out to several extensions at once.
        // Talk rejects video INVITEs to ring GROUPS with USER_BUSY, so ringing
        // individual extensions in parallel is how a doorbell rings the house
        // and still gets video.
        val targets = rawTarget.split(',', ';', ' ')
            .map { it.trim() }.filter { it.isNotEmpty() }

        if (targets.size > 1) {
            dialParallel(targets, s, client)
            return
        }

        val target = targets.firstOrNull() ?: return
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
        dialog = client.invite(target, sdp)
        dialog?.localSdp = sdp
        _callState.value = CallUiState(
            active = true, incoming = false,
            remoteNumber = target,
            remoteName = directory.lookupName(target),
            videoActive = withVideo,
        )
    }

    /**
     * Fan out INVITEs to every extension at once. Each leg gets its OWN SDP with
     * its own RTP ports (they must be distinct), but no media is started until
     * one of them answers.
     */
    private fun dialParallel(targets: List<String>, s: AppSettings, client: SipClient) {
        parallelRingActive = true
        callWasIncoming = false
        callAnsweredAt = 0L
        ringingDialogs.clear()

        val withVideo = s.videoCalls
        for (t in targets) {
            // Allocate throwaway ports per leg so the SDPs are valid and distinct.
            val aPort = RtpSession.allocatePortPair()
            val vPort = if (withVideo) RtpSession.allocatePortPair() else null
            val sdp = Sdp.build(
                localIp = client.localIp,
                user = s.sipUsername,
                audioPort = aPort,
                videoPort = vPort,
                sessionId = Random.nextLong(1000, 99999),
                sessionVersion = Random.nextLong(1000, 99999),
            )
            val d = client.invite(t, sdp)
            d.localSdp = sdp
            ringingDialogs.add(d)
            EngineLog.d("RING: INVITE -> $t (audio $aPort${vPort?.let { ", video $it" } ?: ""})")
        }

        _callState.value = CallUiState(
            active = true, incoming = false,
            remoteNumber = targets.joinToString(", "),
            remoteName = "Ringing ${targets.size} phones",
            videoActive = withVideo,
        )
    }

    /** Try to add video to a connected audio-only call (debug / Stage-2 tool). */
    fun upgradeToVideo() {
        val d = dialog ?: return
        val s = currentSettings ?: return
        val client = sip ?: return
        if (_callState.value.videoActive || !_callState.value.connected) return

        // Build a fresh offer that keeps the negotiated audio port and adds video.
        val aPort = audioRtp?.localRtpPort ?: return
        if (videoRtp == null) {
            val vPort = RtpSession.allocatePortPair()
            videoRtp = RtpSession(
                vPort,
                onPacket = { pt, marker, seq, _, payload ->
                    if (pt == Sdp.PT_H265) videoRx?.onRtpVideo(seq, marker, payload)
                },
                onKeyframeRequest = { videoTx?.requestKeyframe() },
            )
            videoRx = VideoReceiver(videoRtp!!)
            videoTx = VideoSender(context, videoRtp!!, buildVideoTuning())
            pendingRemoteSurface?.let { videoRx?.attachSurface(it) }
        }
        val sdp = Sdp.build(
            localIp = client.localIp,
            user = s.sipUsername,
            audioPort = aPort,
            videoPort = videoRtp!!.localRtpPort,
            sessionId = Random.nextLong(1000, 99999),
            sessionVersion = Random.nextLong(1000, 99999),
            audioPayloads = listOf(audio?.txPayloadType ?: Sdp.PT_PCMU),
        )
        pendingVideoUpgrade = true
        _callState.value = _callState.value.copy(upgradeStatus = "Sending re-INVITE…")
        EngineLog.d("UPGRADE: sending re-INVITE with H.265 video m-line")
        client.reinvite(d, sdp)
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

        override fun onReinviteResult(
            call: SipClient.Dialog, success: Boolean, answer: SdpSession?, code: Int,
        ) {
            pendingVideoUpgrade = false
            val videoMedia = answer?.video()
            if (success && videoMedia != null && answer != null) {
                EngineLog.d("UPGRADE: accepted — video on port ${videoMedia.port}")
                videoRtp?.setRemote(answer.remoteIp, videoMedia.port, videoMedia.rtcpPort)
                videoRtp?.start()
                videoRx?.start()
                videoTx?.start()
                scope.launch {
                    _callState.value = _callState.value.copy(
                        videoActive = true,
                        canUpgradeToVideo = false,
                        upgradeStatus = "Video added (port ${videoMedia.port})",
                    )
                }
            } else {
                val why = if (code >= 300) "rejected with $code"
                else "answered with m=video 0 (peer declined video)"
                EngineLog.d("UPGRADE: $why")
                scope.launch {
                    _callState.value = _callState.value.copy(upgradeStatus = "Failed: $why")
                }
            }
        }

        override fun onCallRinging(call: SipClient.Dialog) {
            // Local ringback: the app doesn't render early media, so give the
            // caller audible feedback that the far end is ringing.
            scope.launch { startRingback() }
        }

        override fun onCallAnswered(call: SipClient.Dialog, answer: SdpSession) {
            if (parallelRingActive) {
                // First answer wins. CANCEL every other ringing leg, then build a
                // real media session for the winner. The ringing legs carried
                // throwaway SDP ports (no media), so we set media up fresh here.
                parallelRingActive = false
                val losers = ringingDialogs.filter { it.callId != call.callId }
                ringingDialogs.clear()
                for (l in losers) {
                    EngineLog.d("RING: cancelling loser leg ${l.callId}")
                    runCatching { sip?.cancel(l) }
                }
                dialog = call
                val withVideo = answer.video() != null
                setupMedia(video = withVideo)

                // Point our fresh RTP sessions at the winner's advertised media.
                answer.audio()?.let { audioRtp?.setRemote(answer.remoteIp, it.port, it.rtcpPort) }
                answer.video()?.let { videoRtp?.setRemote(answer.remoteIp, it.port, it.rtcpPort) }
                audio?.txPayloadType = when {
                    Sdp.PT_PCMU in (answer.audio()?.payloadTypes ?: emptyList()) -> Sdp.PT_PCMU
                    else -> Sdp.PT_PCMA
                }
                startMedia(sendVideo = withVideo)
                callAnsweredAt = System.currentTimeMillis()
                scope.launch {
                    _callState.value = _callState.value.copy(
                        connected = true,
                        startedAtMs = callAnsweredAt,
                        videoActive = withVideo,
                        remoteNumber = call.remoteTarget.substringAfter("sip:").substringBefore("@"),
                        remoteName = null,
                    )
                }
                return  // fully handled; don't fall through to the single-leg path
            }
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
                    canUpgradeToVideo = !videoUp && currentSettings?.videoUpgradeDebug == true,
                )
            }
        }

        override fun onCallEnded(call: SipClient.Dialog, reason: String) {
            // A losing/declined leg of a parallel ring: ignore unless it was the
            // last one still ringing.
            if (parallelRingActive && call.callId != dialog?.callId) {
                ringingDialogs.removeAll { it.callId == call.callId }
                EngineLog.d("RING: leg ended ($reason), ${ringingDialogs.size} still ringing")
                if (ringingDialogs.isNotEmpty()) return
                parallelRingActive = false
                scope.launch {
                    stopRingback()
                    _callState.value = CallUiState()
                }
                return
            }
            if (call.callId != dialog?.callId) return
            scope.launch {
                stopRinging()
                stopRingback()
                clearRingState()
                if (_callState.value.active) recordHistory(_callState.value.remoteNumber)
                teardownMedia()
                dialog = null
                pendingOffer = null
                _callState.value = CallUiState()
            }
        }
    }

    // ---- Media plumbing -------------------------------------------------

    private fun buildVideoTuning(): VideoSender.Tuning {
        val s = currentSettings ?: return VideoSender.Tuning()
        return VideoSender.Tuning(
            rotationOffset = s.videoRotationOffset,
            extraMirror = s.videoMirror,
            useFrontCamera = s.videoUseFrontCamera,
            resolutionShortEdge = s.videoResolution,
            bitrateKbps = s.videoBitrateKbps,
            scaleMode = s.videoScaleMode,
        )
    }

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
            videoTx = VideoSender(context, videoRtp!!, buildVideoTuning())
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

    private fun clearRingState() {
        parallelRingActive = false
        ringingDialogs.clear()
        pendingVideoUpgrade = false
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
