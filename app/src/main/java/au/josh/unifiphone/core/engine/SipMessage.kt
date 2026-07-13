package au.josh.unifiphone.core.engine

/**
 * Minimal SIP message model. Enough for a UA talking to FreeSWITCH (sofia)
 * over UDP on a LAN: requests, responses, headers, body. No multipart, no
 * compact header forms except the common ones sofia may emit.
 */
class SipMessage {
    var isRequest = true
    var method: String = ""        // requests
    var requestUri: String = ""    // requests
    var statusCode: Int = 0        // responses
    var reason: String = ""        // responses
    // Ordered header list; SIP allows repeats (Via, Record-Route).
    val headers = mutableListOf<Pair<String, String>>()
    var body: ByteArray = ByteArray(0)

    fun header(name: String): String? =
        headers.firstOrNull { it.first.equals(name, true) }?.second

    fun headerAll(name: String): List<String> =
        headers.filter { it.first.equals(name, true) }.map { it.second }

    fun add(name: String, value: String) { headers.add(name to value) }

    fun set(name: String, value: String) {
        headers.removeAll { it.first.equals(name, true) }
        headers.add(name to value)
    }

    fun cseqNumber(): Long =
        header("CSeq")?.trim()?.split(" ")?.firstOrNull()?.toLongOrNull() ?: 0L

    fun cseqMethod(): String =
        header("CSeq")?.trim()?.split(" ")?.getOrNull(1) ?: ""

    /** Extract tag=... parameter from a From/To header value. */
    fun tagOf(headerName: String): String? {
        val v = header(headerName) ?: return null
        val m = Regex(";tag=([^;>\\s]+)").find(v) ?: return null
        return m.groupValues[1]
    }

    /** Extract the bare sip: URI from a header like `"Name" <sip:x@y>;tag=z`. */
    fun uriOf(headerName: String): String? {
        val v = header(headerName) ?: return null
        val angle = Regex("<([^>]+)>").find(v)
        if (angle != null) return angle.groupValues[1]
        return v.split(";").firstOrNull()?.trim()
    }

    fun serialize(): ByteArray {
        val sb = StringBuilder()
        if (isRequest) sb.append("$method $requestUri SIP/2.0\r\n")
        else sb.append("SIP/2.0 $statusCode $reason\r\n")
        for ((n, v) in headers) sb.append("$n: $v\r\n")
        sb.append("Content-Length: ${body.size}\r\n\r\n")
        val head = sb.toString().toByteArray(Charsets.UTF_8)
        return head + body
    }

    companion object {
        private val COMPACT = mapOf(
            "v" to "Via", "f" to "From", "t" to "To", "i" to "Call-ID",
            "m" to "Contact", "c" to "Content-Type", "l" to "Content-Length",
            "e" to "Content-Encoding", "s" to "Subject", "k" to "Supported"
        )

        fun parse(data: ByteArray, length: Int): SipMessage? {
            val text = String(data, 0, length, Charsets.UTF_8)
            val headerEnd = text.indexOf("\r\n\r\n")
            if (headerEnd < 0) return null
            val lines = text.substring(0, headerEnd).split("\r\n")
            if (lines.isEmpty()) return null
            val msg = SipMessage()
            val start = lines[0]
            if (start.startsWith("SIP/2.0 ")) {
                msg.isRequest = false
                val parts = start.split(" ", limit = 3)
                msg.statusCode = parts.getOrNull(1)?.toIntOrNull() ?: return null
                msg.reason = parts.getOrNull(2) ?: ""
            } else {
                val parts = start.split(" ")
                if (parts.size < 3 || parts.last() != "SIP/2.0") return null
                msg.isRequest = true
                msg.method = parts[0]
                msg.requestUri = parts.subList(1, parts.size - 1).joinToString(" ")
            }
            var i = 1
            while (i < lines.size) {
                var line = lines[i]
                // Header folding (rare but legal)
                while (i + 1 < lines.size && (lines[i + 1].startsWith(" ") || lines[i + 1].startsWith("\t"))) {
                    line += " " + lines[i + 1].trim(); i++
                }
                val idx = line.indexOf(':')
                if (idx > 0) {
                    var name = line.substring(0, idx).trim()
                    COMPACT[name.lowercase()]?.let { name = it }
                    msg.headers.add(name to line.substring(idx + 1).trim())
                }
                i++
            }
            val contentLen = msg.header("Content-Length")?.trim()?.toIntOrNull() ?: 0
            val bodyStart = headerEnd + 4
            val bodyStartBytes = text.substring(0, bodyStart).toByteArray(Charsets.UTF_8).size
            val avail = length - bodyStartBytes
            val take = minOf(contentLen, avail).coerceAtLeast(0)
            msg.body = data.copyOfRange(bodyStartBytes, bodyStartBytes + take)
            return msg
        }
    }
}
