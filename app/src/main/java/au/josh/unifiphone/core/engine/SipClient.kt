package au.josh.unifiphone.core.engine

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Minimal SIP UA over UDP, targeting one registrar/proxy: UniFi Talk's
 * sofia/FreeSWITCH. Deliberately not a general-purpose stack — it implements
 * exactly the flows observed against Talk in fs_cli:
 *
 *  - REGISTER with digest challenge (401) and periodic refresh
 *  - Outgoing INVITE: 401/407 -> ACK -> re-INVITE with credentials,
 *    1xx ringing, 200 w/ SDP answer -> ACK, in-dialog BYE
 *  - CANCEL of a pending outgoing INVITE
 *  - Incoming INVITE: 180 Ringing, 200 w/ SDP answer / 486, ACK awaited
 *  - Responds to OPTIONS (200), in-dialog re-INVITE (200 w/ current SDP),
 *    INFO (200), BYE (200 + teardown)
 */
class SipClient(
    /** Transport target: the console's IP/hostname, where packets are sent. */
    private val server: String,
    private val serverPort: Int,
    private val user: String,
    private val password: String,
    /**
     * SIP domain used inside URIs (From/To/Request-URI). On UniFi Talk this is
     * `talk.com`, NOT the console IP — sofia matches the domain to route calls,
     * and an IP in the Request-URI is silently dropped (REGISTER still works,
     * which is why registration succeeds but INVITEs vanish).
     */
    private val domain: String,
    private val listener: Listener,
    /** Optional wire trace: every SIP message in/out, for sip_debug.log. */
    private val tracer: ((String) -> Unit)? = null,
) {
    interface Listener {
        fun onRegistered()
        fun onRegistrationFailed(reason: String)
        fun onIncomingCall(call: Dialog, from: String, fromDisplay: String?, offer: SdpSession, rawInvite: SipMessage)
        fun onCallRinging(call: Dialog)
        fun onCallAnswered(call: Dialog, answer: SdpSession)
        fun onCallEnded(call: Dialog, reason: String)
    }

    class Dialog(
        val callId: String,
        var localTag: String,
        var remoteTag: String? = null,
        var remoteTarget: String,          // Request-URI for in-dialog requests
        var routeSet: List<String> = emptyList(),
        var localCseq: Long = 1,
        var remoteCseq: Long = 0,
        val outgoing: Boolean,
        var localSdp: String = "",
        var pendingInvite: SipMessage? = null, // for CANCEL / auth retry
        var answered: Boolean = false,
        var lastResponse: SipMessage? = null,  // resent on INVITE retransmission
        var awaitingAck: Boolean = false,      // 200 OK retransmit until ACK
    )

    private lateinit var socket: DatagramSocket
    private val running = AtomicBoolean(false)
    private var serverAddr: InetAddress? = null

    /**
     * ALL socket sends go through this single thread. Android throws
     * NetworkOnMainThreadException for sends on the UI thread — dial()/accept()
     * are invoked from Compose onClick, and a swallowed exception here meant
     * INVITEs were logged but never transmitted. (Linphone hid this by running
     * its own core thread.)
     */
    private val sendExec = Executors.newSingleThreadExecutor { r -> Thread(r, "sip-send") }

    @Volatile private var cachedLocalIp: String = "127.0.0.1"
    val localIp: String get() = cachedLocalIp
    var localPort: Int = 0; private set

    private val cseq = AtomicLong(Random.nextLong(1, 5000))
    private var regCallId = newToken(12)
    private var regCseq = AtomicLong(1)
    private var regAuthNc = 0
    private var timer: Timer? = null
    private val dialogs = ConcurrentHashMap<String, Dialog>()

    // ------------------------------------------------------------------ setup

    fun start() {
        if (!running.compareAndSet(false, true)) return
        socket = DatagramSocket(0)
        localPort = socket.localPort
        serverAddr = runCatching { InetAddress.getByName(server) }.getOrNull()
        if (serverAddr == null) {
            listener.onRegistrationFailed("Cannot resolve $server"); return
        }
        cachedLocalIp = detectLocalIp()
        Thread(::receiveLoop, "sip-recv").start()
        timer = Timer("sip-timer", true)
        register()
        timer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() { register() }
        }, 150_000L, 150_000L) // refresh at half of 300s expiry
    }

    fun stop() {
        running.set(false)
        timer?.cancel()
        runCatching { socket.close() }
        sendExec.shutdown()
        dialogs.clear()
    }

    // --------------------------------------------------------------- REGISTER

    private var regChallenge: DigestAuth.Challenge? = null

    private fun register() {
        val m = SipMessage().apply {
            isRequest = true; method = "REGISTER"
            requestUri = "sip:$domain"
            add("Via", via())
            add("Max-Forwards", "70")
            add("From", "<sip:$user@$domain>;tag=${newToken(8)}")
            add("To", "<sip:$user@$domain>")
            add("Call-ID", regCallId)
            add("CSeq", "${regCseq.incrementAndGet()} REGISTER")
            add("Contact", "<sip:$user@$localIp:$localPort;transport=udp>")
            add("Expires", "300")
            add("User-Agent", USER_AGENT)
            add("Allow", ALLOW)
        }
        regChallenge?.let {
            val (h, v) = DigestAuth.authorizationHeader(it, user, password, "REGISTER", "sip:$domain", ++regAuthNc)
            m.add(h, v)
        }
        send(m)
    }

    // ----------------------------------------------------------------- INVITE

    /** Place a call. Returns the dialog handle. */
    fun invite(target: String, sdp: String): Dialog {
        val callId = newToken(16)
        val d = Dialog(
            callId = callId, localTag = newToken(8),
            remoteTarget = "sip:$target@$domain", outgoing = true, localSdp = sdp,
        )
        dialogs[callId] = d
        sendInvite(d, auth = null)
        return d
    }

    private fun sendInvite(d: Dialog, auth: Pair<String, String>?) {
        d.localCseq = cseq.incrementAndGet()
        val m = SipMessage().apply {
            isRequest = true; method = "INVITE"
            requestUri = d.remoteTarget
            add("Via", via())
            add("Max-Forwards", "70")
            add("From", "\"$user\" <sip:$user@$domain>;tag=${d.localTag}")
            add("To", "<${d.remoteTarget}>")
            add("Call-ID", d.callId)
            add("CSeq", "${d.localCseq} INVITE")
            add("Contact", "<sip:$user@$localIp:$localPort;transport=udp>")
            add("User-Agent", USER_AGENT)
            add("Allow", ALLOW)
            add("Content-Type", "application/sdp")
            body = d.localSdp.toByteArray()
        }
        auth?.let { m.add(it.first, it.second) }
        d.pendingInvite = m
        send(m)
    }

    fun cancel(d: Dialog) {
        val inv = d.pendingInvite ?: return
        val m = SipMessage().apply {
            isRequest = true; method = "CANCEL"
            requestUri = inv.requestUri
            add("Via", inv.header("Via")!!)
            add("Max-Forwards", "70")
            add("From", inv.header("From")!!)
            add("To", inv.header("To")!!)
            add("Call-ID", d.callId)
            add("CSeq", "${d.localCseq} CANCEL")
        }
        send(m)
        endDialog(d, "cancelled")
    }

    fun bye(d: Dialog) {
        if (!d.answered) { cancel(d); return }
        d.localCseq = cseq.incrementAndGet()
        val m = SipMessage().apply {
            isRequest = true; method = "BYE"
            requestUri = d.remoteTarget
            add("Via", via())
            add("Max-Forwards", "70")
            add("From", "<sip:$user@$domain>;tag=${d.localTag}")
            add("To", "<sip:${d.remoteTarget.removePrefix("sip:")}>${d.remoteTag?.let { ";tag=$it" } ?: ""}")
            add("Call-ID", d.callId)
            add("CSeq", "${d.localCseq} BYE")
            for (r in d.routeSet) add("Route", r)
        }
        send(m)
        endDialog(d, "hangup")
    }

    /** Answer an incoming INVITE with an SDP answer. */
    fun accept(d: Dialog, sdpAnswer: String) {
        val inv = d.pendingInvite ?: return
        d.localSdp = sdpAnswer
        d.answered = true
        val resp = response(inv, 200, "OK").apply {
            set("Contact", "<sip:$user@$localIp:$localPort;transport=udp>")
            set("Content-Type", "application/sdp")
            add("Allow", ALLOW)
            body = sdpAnswer.toByteArray()
        }
        // Our To-tag was already assigned when we sent 180.
        resp.set("To", "${inv.header("To")};tag=${d.localTag}")
        d.lastResponse = resp
        d.awaitingAck = true
        send(resp)
        // RFC 3261 17.2.1: retransmit 2xx until ACK (UDP). LAN-scaled: 500 ms x 8.
        var attempts = 0
        timer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (!d.awaitingAck || attempts++ >= 8 || !dialogs.containsKey(d.callId)) {
                    cancel(); return
                }
                send(resp)
            }
        }, 500L, 500L)
    }

    fun decline(d: Dialog, code: Int = 486, reason: String = "Busy Here") {
        val inv = d.pendingInvite ?: return
        val resp = response(inv, code, reason)
        resp.set("To", "${inv.header("To")};tag=${d.localTag}")
        send(resp)
        endDialog(d, "declined")
    }

    // ----------------------------------------------------------- receive path

    private fun receiveLoop() {
        val buf = ByteArray(65535)
        val dp = DatagramPacket(buf, buf.size)
        while (running.get()) {
            try {
                dp.setLength(buf.size) // CRITICAL: DatagramPacket length shrinks after each receive
                socket.receive(dp)
                trace("<<< ", buf, dp.length)
                val msg = SipMessage.parse(buf, dp.length) ?: continue
                if (msg.isRequest) handleRequest(msg, dp.socketAddress as InetSocketAddress)
                else handleResponse(msg)
            } catch (_: Exception) {
                if (!running.get()) return
            }
        }
    }

    private fun handleResponse(msg: SipMessage) {
        val method = msg.cseqMethod()
        val callId = msg.header("Call-ID") ?: return

        if (method == "REGISTER") {
            when {
                msg.statusCode == 401 || msg.statusCode == 407 -> {
                    val ch = DigestAuth.parseChallenge(msg) ?: return
                    regChallenge = ch; regAuthNc = 0
                    register()
                }
                msg.statusCode in 200..299 -> listener.onRegistered()
                msg.statusCode >= 400 -> listener.onRegistrationFailed("${msg.statusCode} ${msg.reason}")
            }
            return
        }

        val d = dialogs[callId] ?: return
        when (method) {
            "INVITE" -> when {
                msg.statusCode in 100..199 -> {
                    msg.tagOf("To")?.let { d.remoteTag = it }
                    if (msg.statusCode == 180 || msg.statusCode == 183) listener.onCallRinging(d)
                }
                msg.statusCode == 401 || msg.statusCode == 407 -> {
                    ackNon2xx(d, msg)
                    val ch = DigestAuth.parseChallenge(msg) ?: return
                    val (h, v) = DigestAuth.authorizationHeader(ch, user, password, "INVITE", d.remoteTarget, 1)
                    sendInvite(d, h to v)
                }
                msg.statusCode in 200..299 -> {
                    d.remoteTag = msg.tagOf("To")
                    msg.uriOf("Contact")?.let { d.remoteTarget = it }
                    d.routeSet = msg.headerAll("Record-Route").reversed()
                    d.answered = true
                    ack2xx(d, msg)
                    val sdp = Sdp.parse(String(msg.body))
                    if (sdp != null) listener.onCallAnswered(d, sdp)
                    else { bye(d) }
                }
                msg.statusCode >= 300 -> {
                    ackNon2xx(d, msg)
                    endDialog(d, "${msg.statusCode} ${msg.reason}")
                }
            }
            "BYE", "CANCEL" -> { /* response to our request; nothing to do */ }
        }
    }

    private fun handleRequest(msg: SipMessage, from: InetSocketAddress) {
        val callId = msg.header("Call-ID") ?: return
        when (msg.method) {
            "INVITE" -> {
                val existing = dialogs[callId]
                if (existing != null) {
                    if (msg.cseqNumber() <= existing.remoteCseq) {
                        // Retransmission of an INVITE we already saw: resend our
                        // last response rather than treating it as a re-INVITE.
                        existing.lastResponse?.let { send(it) }
                        return
                    }
                    // Genuine re-INVITE (hold / session refresh): answer with current SDP.
                    existing.remoteCseq = msg.cseqNumber()
                    existing.pendingInvite = msg
                    val resp = response(msg, 200, "OK").apply {
                        set("Contact", "<sip:$user@$localIp:$localPort;transport=udp>")
                        set("Content-Type", "application/sdp")
                        body = existing.localSdp.toByteArray()
                    }
                    resp.set("To", "${msg.header("To")};tag=${existing.localTag}")
                    existing.lastResponse = resp
                    send(resp)
                    return
                }
                val offer = Sdp.parse(String(msg.body)) ?: run {
                    send(response(msg, 488, "Not Acceptable Here")); return
                }
                val d = Dialog(
                    callId = callId,
                    localTag = newToken(8),
                    remoteTag = msg.tagOf("From"),
                    remoteTarget = msg.uriOf("Contact") ?: msg.uriOf("From") ?: return,
                    routeSet = msg.headerAll("Record-Route"),
                    outgoing = false,
                    pendingInvite = msg,
                    remoteCseq = msg.cseqNumber(),
                )
                dialogs[callId] = d
                send(response(msg, 100, "Trying"))
                val ringing = response(msg, 180, "Ringing")
                ringing.set("To", "${msg.header("To")};tag=${d.localTag}")
                ringing.set("Contact", "<sip:$user@$localIp:$localPort;transport=udp>")
                d.lastResponse = ringing
                send(ringing)
                val fromUser = Regex("sip:([^@;>]+)").find(msg.header("From") ?: "")?.groupValues?.get(1) ?: "?"
                val display = Regex("^\"([^\"]+)\"").find((msg.header("From") ?: "").trim())?.groupValues?.get(1)
                listener.onIncomingCall(d, fromUser, display, offer, msg)
            }
            "ACK" -> { dialogs[callId]?.awaitingAck = false }
            "BYE" -> {
                send(response(msg, 200, "OK"))
                dialogs[callId]?.let { endDialog(it, "remote hangup") }
            }
            "CANCEL" -> {
                send(response(msg, 200, "OK"))
                dialogs[callId]?.let { d ->
                    d.pendingInvite?.let { inv ->
                        val term = response(inv, 487, "Request Terminated")
                        term.set("To", "${inv.header("To")};tag=${d.localTag}")
                        send(term)
                    }
                    endDialog(d, "remote cancelled")
                }
            }
            "OPTIONS" -> send(response(msg, 200, "OK").apply { add("Allow", ALLOW) })
            "INFO", "NOTIFY", "UPDATE" -> send(response(msg, 200, "OK"))
            else -> send(response(msg, 501, "Not Implemented"))
        }
    }

    // -------------------------------------------------------------- plumbing

    private fun ack2xx(d: Dialog, resp2xx: SipMessage) {
        val m = SipMessage().apply {
            isRequest = true; method = "ACK"
            requestUri = d.remoteTarget
            add("Via", via())
            add("Max-Forwards", "70")
            add("From", "\"$user\" <sip:$user@$domain>;tag=${d.localTag}")
            add("To", resp2xx.header("To")!!)
            add("Call-ID", d.callId)
            add("CSeq", "${resp2xx.cseqNumber()} ACK")
            for (r in d.routeSet) add("Route", r)
        }
        send(m)
    }

    private fun ackNon2xx(d: Dialog, resp: SipMessage) {
        val inv = d.pendingInvite ?: return
        val m = SipMessage().apply {
            isRequest = true; method = "ACK"
            requestUri = inv.requestUri
            add("Via", inv.header("Via")!!)
            add("Max-Forwards", "70")
            add("From", inv.header("From")!!)
            add("To", resp.header("To")!!)
            add("Call-ID", d.callId)
            add("CSeq", "${resp.cseqNumber()} ACK")
        }
        send(m)
    }

    private fun response(req: SipMessage, code: Int, reason: String): SipMessage =
        SipMessage().apply {
            isRequest = false; statusCode = code; this.reason = reason
            for (v in req.headerAll("Via")) add("Via", v)
            for (v in req.headerAll("Record-Route")) add("Record-Route", v)
            add("From", req.header("From") ?: "")
            add("To", req.header("To") ?: "")
            add("Call-ID", req.header("Call-ID") ?: "")
            add("CSeq", req.header("CSeq") ?: "")
            add("User-Agent", USER_AGENT)
        }

    private fun endDialog(d: Dialog, reason: String) {
        dialogs.remove(d.callId)
        listener.onCallEnded(d, reason)
    }

    private fun send(msg: SipMessage) {
        val addr = serverAddr ?: return
        val data = msg.serialize()
        sendExec.execute {
            try {
                socket.send(DatagramPacket(data, data.size, addr, serverPort))
                trace(">>> ", data, data.size)
            } catch (e: Exception) {
                trace("XXX SEND FAILED (${e.javaClass.simpleName}: ${e.message}) ", data, data.size)
            }
        }
    }

    private fun trace(dir: String, data: ByteArray, len: Int) {
        val t = tracer ?: return
        runCatching { t(dir + String(data, 0, len, Charsets.UTF_8)) }
    }

    private fun via(): String =
        "SIP/2.0/UDP $localIp:$localPort;branch=z9hG4bK${newToken(12)};rport"

    /** Pick the LAN IPv4 — a multi-homed PC was exactly what broke inbound to Linphone. */
    private fun detectLocalIp(): String {
        // Prefer the interface that routes to the SIP server.
        runCatching {
            DatagramSocket().use { probe ->
                probe.connect(InetAddress.getByName(server), serverPort)
                val ip = probe.localAddress?.hostAddress
                if (!ip.isNullOrBlank() && ip != "0.0.0.0") return ip
            }
        }
        val ifaces = NetworkInterface.getNetworkInterfaces()
        for (nif in ifaces) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in nif.inetAddresses) {
                val ip = addr.hostAddress ?: continue
                if (!addr.isLoopbackAddress && ip.count { it == '.' } == 3) return ip
            }
        }
        return "127.0.0.1"
    }

    companion object {
        private const val USER_AGENT = "UniFiPhone-Android/2.0"
        private const val ALLOW = "INVITE, ACK, CANCEL, BYE, OPTIONS, INFO, UPDATE, NOTIFY"
        private fun newToken(len: Int): String {
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            return (1..len).map { chars[Random.nextInt(chars.length)] }.joinToString("")
        }
    }
}
