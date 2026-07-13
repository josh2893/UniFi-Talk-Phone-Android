package au.josh.unifiphone.core.engine

import java.security.MessageDigest
import kotlin.random.Random

/**
 * RFC 2617 digest auth (MD5, with and without qop=auth), which is what
 * sofia/FreeSWITCH challenges with on REGISTER and INVITE.
 */
object DigestAuth {

    data class Challenge(
        val realm: String,
        val nonce: String,
        val qop: String?,       // "auth" or null
        val opaque: String?,
        val algorithm: String,  // "MD5"
        val isProxy: Boolean,
    )

    fun parseChallenge(msg: SipMessage): Challenge? {
        val (value, proxy) = when {
            msg.header("WWW-Authenticate") != null -> msg.header("WWW-Authenticate")!! to false
            msg.header("Proxy-Authenticate") != null -> msg.header("Proxy-Authenticate")!! to true
            else -> return null
        }
        fun param(name: String): String? {
            val quoted = Regex("$name\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(value)
            if (quoted != null) return quoted.groupValues[1]
            return Regex("$name\\s*=\\s*([^,\\s]+)", RegexOption.IGNORE_CASE)
                .find(value)?.groupValues?.get(1)
        }
        return Challenge(
            realm = param("realm") ?: return null,
            nonce = param("nonce") ?: return null,
            qop = param("qop")?.split(",")?.map { it.trim() }?.firstOrNull { it == "auth" },
            opaque = param("opaque"),
            algorithm = param("algorithm") ?: "MD5",
            isProxy = proxy,
        )
    }

    fun authorizationHeader(
        ch: Challenge, user: String, password: String, method: String, uri: String, nc: Int,
    ): Pair<String, String> {
        val ha1 = md5("$user:${ch.realm}:$password")
        val ha2 = md5("$method:$uri")
        val ncStr = "%08x".format(nc)
        val cnonce = List(8) { "0123456789abcdef"[Random.nextInt(16)] }.joinToString("")
        val response = if (ch.qop == "auth")
            md5("$ha1:${ch.nonce}:$ncStr:$cnonce:auth:$ha2")
        else
            md5("$ha1:${ch.nonce}:$ha2")

        val sb = StringBuilder("Digest username=\"$user\", realm=\"${ch.realm}\", ")
        sb.append("nonce=\"${ch.nonce}\", uri=\"$uri\", response=\"$response\", algorithm=MD5")
        if (ch.qop == "auth") sb.append(", qop=auth, nc=$ncStr, cnonce=\"$cnonce\"")
        ch.opaque?.let { sb.append(", opaque=\"$it\"") }
        val headerName = if (ch.isProxy) "Proxy-Authorization" else "Authorization"
        return headerName to sb.toString()
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
