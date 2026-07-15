package au.josh.unifiphone.core.engine

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * One RTP session = RTP socket (even port) + RTCP socket (port+1).
 * Plain RTP/AVP, exactly as the Talk handsets use (no SRTP).
 *
 * RTCP handling is deliberately minimal:
 *  - inbound: detect PSFB PLI / FIR -> onKeyframeRequest (encoder must send IDR)
 *  - outbound: sendPli() when our decoder needs a keyframe
 */
class RtpSession(
    val localRtpPort: Int,
    private val onPacket: (payloadType: Int, marker: Boolean, seq: Int, timestamp: Long, payload: ByteArray) -> Unit,
    private val onKeyframeRequest: () -> Unit = {},
) {
    val ssrc: Long = Random.nextLong(1, 0xFFFFFFFFL)

    // Diagnostics: packet counters, readable for the debug panel.
    @Volatile var rxPackets = 0L; private set
    @Volatile var txPackets = 0L; private set
    @Volatile var rxBytes = 0L; private set

    private val rtpSocket = DatagramSocket(localRtpPort)
    private val rtcpSocket = DatagramSocket(localRtpPort + 1)
    private val running = AtomicBoolean(true)

    private var remoteAddr: InetAddress? = null
    private var remoteRtpPort = 0
    private var remoteRtcpPort = 0

    private var seq = Random.nextInt(0, 0x7FFF)
    private var remoteSsrc = 0L

    /**
     * [rtcpPort] from the SDP a=rtcp: attribute when present. Talk handsets use
     * NON-adjacent RTCP ports (e.g. m=video 53378 / a=rtcp:36768) — assuming
     * rtp+1 sends keyframe requests into a void, which shows up as video that
     * freezes on the first lost packet and never recovers.
     */
    fun setRemote(ip: String, rtpPort: Int, rtcpPort: Int? = null) {
        remoteAddr = InetAddress.getByName(ip)
        remoteRtpPort = rtpPort
        remoteRtcpPort = rtcpPort ?: (rtpPort + 1)
    }

    fun start() {
        Thread({ receiveLoop(rtpSocket, rtcp = false) }, "rtp-recv-$localRtpPort").start()
        Thread({ receiveLoop(rtcpSocket, rtcp = true) }, "rtcp-recv-$localRtpPort").start()
        // RTCP keepalive: empty Receiver Report every 2 s so the far end knows
        // our RTCP path is alive (some stacks stop sending without it).
        Thread({
            while (running.get()) {
                runCatching { sendEmptyRr() }
                Thread.sleep(2000)
            }
        }, "rtcp-keepalive-$localRtpPort").start()
    }

    private fun sendEmptyRr() {
        val addr = remoteAddr ?: return
        val pkt = ByteArray(8)
        pkt[0] = 0x80.toByte()                 // V=2, RC=0
        pkt[1] = 201.toByte()                  // PT=RR
        pkt[2] = 0; pkt[3] = 1                 // length = 1 word after header
        pkt[4] = (ssrc shr 24).toByte(); pkt[5] = (ssrc shr 16).toByte()
        pkt[6] = (ssrc shr 8).toByte(); pkt[7] = ssrc.toByte()
        rtcpSocket.send(DatagramPacket(pkt, pkt.size, addr, remoteRtcpPort))
    }

    fun send(payloadType: Int, marker: Boolean, timestamp: Long, payload: ByteArray) {
        val addr = remoteAddr ?: return
        seq = (seq + 1) and 0xFFFF
        val pkt = ByteArray(12 + payload.size)
        pkt[0] = 0x80.toByte()
        pkt[1] = ((if (marker) 0x80 else 0) or (payloadType and 0x7F)).toByte()
        pkt[2] = (seq shr 8).toByte(); pkt[3] = seq.toByte()
        val ts = timestamp and 0xFFFFFFFFL
        pkt[4] = (ts shr 24).toByte(); pkt[5] = (ts shr 16).toByte()
        pkt[6] = (ts shr 8).toByte(); pkt[7] = ts.toByte()
        pkt[8] = (ssrc shr 24).toByte(); pkt[9] = (ssrc shr 16).toByte()
        pkt[10] = (ssrc shr 8).toByte(); pkt[11] = ssrc.toByte()
        System.arraycopy(payload, 0, pkt, 12, payload.size)
        txPackets++
        runCatching { rtpSocket.send(DatagramPacket(pkt, pkt.size, addr, remoteRtpPort)) }
    }

    /** RTCP PSFB PLI (RFC 4585): ask the far end for a keyframe. */
    fun sendPli() {
        val addr = remoteAddr ?: return
        if (remoteSsrc == 0L) return
        val pkt = ByteArray(12)
        pkt[0] = (0x80 or 1).toByte()          // V=2, FMT=1 (PLI)
        pkt[1] = 206.toByte()                  // PT=PSFB
        pkt[2] = 0; pkt[3] = 2                 // length = 2 (3 words - 1)
        pkt[4] = (ssrc shr 24).toByte(); pkt[5] = (ssrc shr 16).toByte()
        pkt[6] = (ssrc shr 8).toByte(); pkt[7] = ssrc.toByte()
        pkt[8] = (remoteSsrc shr 24).toByte(); pkt[9] = (remoteSsrc shr 16).toByte()
        pkt[10] = (remoteSsrc shr 8).toByte(); pkt[11] = remoteSsrc.toByte()
        runCatching { rtcpSocket.send(DatagramPacket(pkt, pkt.size, addr, remoteRtcpPort)) }
    }

    private fun receiveLoop(socket: DatagramSocket, rtcp: Boolean) {
        val buf = ByteArray(4096)
        val dp = DatagramPacket(buf, buf.size)
        while (running.get()) {
            try {
                dp.setLength(buf.size) // CRITICAL: DatagramPacket length shrinks after each receive
                socket.receive(dp)
                if (rtcp) handleRtcp(buf, dp.length) else handleRtp(buf, dp.length)
            } catch (_: Exception) {
                if (!running.get()) return
            }
        }
    }

    private fun handleRtp(buf: ByteArray, len: Int) {
        if (len < 12) return
        val ver = (buf[0].toInt() shr 6) and 0x3
        if (ver != 2) return
        val cc = buf[0].toInt() and 0x0F
        val hasExt = buf[0].toInt() and 0x10 != 0
        val marker = buf[1].toInt() and 0x80 != 0
        val pt = buf[1].toInt() and 0x7F
        val seqNum = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
        val ts = ((buf[4].toLong() and 0xFF) shl 24) or ((buf[5].toLong() and 0xFF) shl 16) or
            ((buf[6].toLong() and 0xFF) shl 8) or (buf[7].toLong() and 0xFF)
        remoteSsrc = ((buf[8].toLong() and 0xFF) shl 24) or ((buf[9].toLong() and 0xFF) shl 16) or
            ((buf[10].toLong() and 0xFF) shl 8) or (buf[11].toLong() and 0xFF)
        var offset = 12 + cc * 4
        if (hasExt) {
            if (len < offset + 4) return
            val extLen = ((buf[offset + 2].toInt() and 0xFF) shl 8) or (buf[offset + 3].toInt() and 0xFF)
            offset += 4 + extLen * 4
        }
        if (offset >= len) return
        rxPackets++; rxBytes += (len - offset)
        onPacket(pt, marker, seqNum, ts, buf.copyOfRange(offset, len))
    }

    private fun handleRtcp(buf: ByteArray, len: Int) {
        // Walk compound RTCP; look for PSFB(206) PLI(fmt=1)/FIR(fmt=4) and RTPFB FIR-ish requests.
        var off = 0
        while (off + 4 <= len) {
            val fmt = buf[off].toInt() and 0x1F
            val pt = buf[off + 1].toInt() and 0xFF
            val words = ((buf[off + 2].toInt() and 0xFF) shl 8) or (buf[off + 3].toInt() and 0xFF)
            val pktLen = (words + 1) * 4
            if (pt == 206 && (fmt == 1 || fmt == 4)) onKeyframeRequest()
            off += pktLen
            if (pktLen <= 0) break
        }
    }

    fun close() {
        running.set(false)
        rtpSocket.close()
        rtcpSocket.close()
    }

    companion object {
        /** Find a free even port pair (RTP even, RTCP odd) in the dynamic range. */
        fun allocatePortPair(): Int {
            repeat(50) {
                val port = (Random.nextInt(16000, 32000) / 2) * 2
                try {
                    DatagramSocket(port).close()
                    DatagramSocket(port + 1).close()
                    return port
                } catch (_: Exception) { }
            }
            throw IllegalStateException("No free RTP port pair")
        }
    }
}
