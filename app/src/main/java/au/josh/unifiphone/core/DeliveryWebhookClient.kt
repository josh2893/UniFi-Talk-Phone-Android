package au.josh.unifiphone.core

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Instant
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object DeliveryWebhookClient {
    suspend fun send(
        webhookUrl: String,
        recipient: String,
        doorName: String,
        address: String,
        apiKeyHeader: String,
        apiKey: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(webhookUrl.trim())
            require(url.protocol == "http" || url.protocol == "https") {
                "Webhook must use HTTP or HTTPS"
            }
            val isProtectAlarmTrigger = url.path
                .contains("/proxy/protect/integration/v1/alarm-manager/webhook/")
            val payload = if (isProtectAlarmTrigger) {
                null
            } else {
                JSONObject()
                    .put("event", "doorbell_delivery")
                    .put("recipient", recipient)
                    .put("door", doorName)
                    .put("address", address)
                    .put("sentAt", Instant.now().toString())
                    .toString()
            }

            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                doOutput = payload != null
                if (payload != null) {
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                setRequestProperty("Accept", "application/json")
                if (apiKey.isNotBlank()) {
                    val header = apiKeyHeader.trim().ifBlank { "X-API-Key" }
                    require(header.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) {
                        "Invalid API key header name"
                    }
                    setRequestProperty(header, apiKey.trim())
                }
            }
            if (isProtectAlarmTrigger && connection is HttpsURLConnection && url.isPrivateNetworkHost()) {
                // UniFi consoles commonly use a self-signed or hostname-mismatched
                // certificate on private IPs. Scope the exception to this exact API.
                connection.sslSocketFactory = localProtectSslContext.socketFactory
                connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }
            try {
                if (payload != null) {
                    connection.outputStream.use { output ->
                        output.write(payload.toByteArray(Charsets.UTF_8))
                    }
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val detail = connection.errorStream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?.trim()
                        ?.take(240)
                    error(
                        if (detail.isNullOrBlank()) "Webhook returned HTTP $code"
                        else "Webhook returned HTTP $code: $detail"
                    )
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun URL.isPrivateNetworkHost(): Boolean = runCatching {
        InetAddress.getAllByName(host).any { address ->
            address.isSiteLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress
        }
    }.getOrDefault(false)

    private val localProtectSslContext: SSLContext by lazy {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
    }
}
