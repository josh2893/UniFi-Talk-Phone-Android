package au.josh.unifiphone.core.engine

/**
 * SDP offer/answer matching what UniFi Talk handsets actually put on the
 * wire (captured from fs_cli, handset<->handset video call):
 *
 *   m=audio <port> RTP/AVP 0 8 101
 *   m=video <port> RTP/AVP 96
 *   a=rtpmap:96 H265/90000
 *   a=rtcp-fb:96 nack pli
 *   a=rtcp-fb:96 ccm fir
 *
 * Plain RTP/AVP (no SRTP), no ICE, no DTLS. Talk sets bypass_media=true on
 * the video route so this SDP travels end-to-end to the peer handset.
 */
data class SdpMedia(
    val type: String,            // "audio" / "video"
    val port: Int,               // 0 = declined
    val payloadTypes: List<Int>,
    val rtpmap: Map<Int, String>, // pt -> "PCMU/8000" etc
    /** Explicit a=rtcp: port. Talk handsets use NON-adjacent RTCP ports. */
    val rtcpPort: Int? = null,
) {
    /** Offered payload type whose rtpmap is telephone-event at 8 kHz. */
    fun dtmfPt8k(): Int? = rtpmap.entries
        .firstOrNull { it.value.equals("telephone-event/8000", true) }?.key
}

data class SdpSession(
    val remoteIp: String,
    val media: List<SdpMedia>,
) {
    fun audio(): SdpMedia? = media.firstOrNull { it.type == "audio" && it.port > 0 }
    fun video(): SdpMedia? = media.firstOrNull { it.type == "video" && it.port > 0 }

    /** First payload type in [m] whose rtpmap encoding matches [codec] (case-insensitive). */
    fun payloadFor(m: SdpMedia, codec: String): Int? =
        m.payloadTypes.firstOrNull { pt ->
            m.rtpmap[pt]?.substringBefore('/')?.equals(codec, true) == true ||
                (pt == 0 && codec.equals("PCMU", true)) ||
                (pt == 8 && codec.equals("PCMA", true))
        }
}

object Sdp {

    const val PT_PCMU = 0
    const val PT_PCMA = 8
    const val PT_DTMF = 101
    const val PT_H265 = 96

    fun build(
        localIp: String,
        user: String,
        audioPort: Int,
        videoPort: Int?,          // null = no video m-line; 0 = declined video m-line
        sessionId: Long,
        sessionVersion: Long,
        /**
         * Audio codecs for the m-line. Offers list both; ANSWERS must be a
         * subset of the offer (answering with a codec the offer lacked is an
         * SDP violation — FreeSWITCH responds by dropping your media).
         */
        audioPayloads: List<Int> = listOf(PT_PCMU, PT_PCMA),
        /** telephone-event payload type; answers must reuse the OFFER's 8 kHz PT. */
        dtmfPt: Int = PT_DTMF,
    ): String {
        val sb = StringBuilder()
        sb.append("v=0\r\n")
        sb.append("o=$user $sessionId $sessionVersion IN IP4 $localIp\r\n")
        sb.append("s=Talk\r\n")
        sb.append("c=IN IP4 $localIp\r\n")
        sb.append("t=0 0\r\n")
        sb.append("m=audio $audioPort RTP/AVP ${audioPayloads.joinToString(" ")} $dtmfPt\r\n")
        if (PT_PCMU in audioPayloads) sb.append("a=rtpmap:$PT_PCMU PCMU/8000\r\n")
        if (PT_PCMA in audioPayloads) sb.append("a=rtpmap:$PT_PCMA PCMA/8000\r\n")
        sb.append("a=rtpmap:$dtmfPt telephone-event/8000\r\n")
        sb.append("a=fmtp:$dtmfPt 0-15\r\n")
        if (videoPort != null) {
            sb.append("m=video $videoPort RTP/AVP $PT_H265\r\n")
            if (videoPort > 0) {
                sb.append("a=rtpmap:$PT_H265 H265/90000\r\n")
                sb.append("a=rtcp-fb:$PT_H265 nack pli\r\n")
                sb.append("a=rtcp-fb:$PT_H265 ccm fir\r\n")
            }
        }
        return sb.toString()
    }

    fun parse(body: String): SdpSession? {
        var sessionIp: String? = null
        val media = mutableListOf<SdpMedia>()
        var curType: String? = null
        var curPort = 0
        var curPts = listOf<Int>()
        var curMap = mutableMapOf<Int, String>()
        var curIp: String? = null
        var curRtcp: Int? = null

        fun flush() {
            curType?.let {
                media.add(SdpMedia(it, curPort, curPts, curMap.toMap(), curRtcp))
            }
            curType = null; curMap = mutableMapOf(); curIp = null; curRtcp = null
        }

        for (raw in body.split("\n")) {
            val line = raw.trim()
            when {
                line.startsWith("c=IN IP4 ") -> {
                    val ip = line.removePrefix("c=IN IP4 ").trim()
                    if (curType == null) sessionIp = ip else curIp = ip
                }
                line.startsWith("m=") -> {
                    flush()
                    val parts = line.removePrefix("m=").split(" ")
                    if (parts.size >= 4) {
                        curType = parts[0]
                        curPort = parts[1].toIntOrNull() ?: 0
                        curPts = parts.drop(3).mapNotNull { it.toIntOrNull() }
                    }
                }
                line.startsWith("a=rtcp:") -> {
                    curRtcp = line.removePrefix("a=rtcp:").trim().split(" ").firstOrNull()?.toIntOrNull()
                }
                line.startsWith("a=rtpmap:") -> {
                    val rest = line.removePrefix("a=rtpmap:")
                    val sp = rest.indexOf(' ')
                    if (sp > 0) {
                        val pt = rest.substring(0, sp).toIntOrNull()
                        if (pt != null) curMap[pt] = rest.substring(sp + 1).trim()
                    }
                }
            }
        }
        flush()
        val ip = sessionIp ?: return null
        return SdpSession(ip, media)
    }
}
