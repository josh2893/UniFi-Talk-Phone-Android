package au.josh.unifiphone.core

import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object DeliveryWebhookClient {
    suspend fun send(
        webhookUrl: String,
        recipient: String,
        doorName: String,
        address: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(webhookUrl.trim())
            require(url.protocol == "http" || url.protocol == "https") {
                "Webhook must use HTTP or HTTPS"
            }
            val payload = JSONObject()
                .put("event", "doorbell_delivery")
                .put("recipient", recipient)
                .put("door", doorName)
                .put("address", address)
                .put("sentAt", Instant.now().toString())
                .toString()

            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            try {
                connection.outputStream.use { output ->
                    output.write(payload.toByteArray(Charsets.UTF_8))
                }
                val code = connection.responseCode
                require(code in 200..299) { "Webhook returned HTTP $code" }
            } finally {
                connection.disconnect()
            }
        }
    }
}
