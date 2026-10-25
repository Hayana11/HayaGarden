package xyz.lovestyle.home.canary

import android.webkit.CookieManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * P2H-3C: blocking HTTP for the native call engine, on the engine's own threads
 * (never the main thread). The URLs come from NativeCallProtocol (fixed origin);
 * the owner session is the WebView's own cookie, attached here, exactly as
 * CallAudioBridge does for his audio. Nothing here is reachable from JS.
 */
class NativeCallApi {
    class ApiException(val status: Int, message: String) : Exception(message)

    fun ring(language: String?): JSONObject {
        val code = NativeCallProtocol.ringLanguage(language)
        val body = if (code == null) null else JSONObject().put("language", code).toString().toByteArray()
        return post(NativeCallProtocol.ringUrl(), body, "application/json").getJSONObject("call")
    }

    fun answer(callId: String): JSONObject = post(need(NativeCallProtocol.answerUrl(callId))).getJSONObject("call")

    fun decline(callId: String): JSONObject = post(need(NativeCallProtocol.declineUrl(callId)))

    fun hangup(callId: String): JSONObject = post(need(NativeCallProtocol.hangupUrl(callId)))

    /** Barge-in: short timeout, so a stalled server never holds the barge-in lane for long. */
    fun interrupt(callId: String, seq: Int, prev: Int?) {
        post(need(NativeCallProtocol.interruptUrl(callId, seq, prev)), readTimeout = INTERRUPT_TIMEOUT_MS,
            connectTimeout = INTERRUPT_TIMEOUT_MS)
    }

    /** One utterance (WAV PCM16); returns the server's result (her line, or stale/duplicate). */
    fun hear(callId: String, seq: Int, prev: Int?, wav: ByteArray): JSONObject =
        post(need(NativeCallProtocol.hearUrl(callId, seq, prev)), wav, "audio/wav", HEAR_TIMEOUT_MS)

    fun discard(callId: String, seq: Int, prev: Int?) {
        post(need(NativeCallProtocol.discardUrl(callId, seq, prev)))
    }

    /** One stream window: an open connection the caller reads line by line and disconnects. */
    fun openStream(callId: String, after: Int): HttpURLConnection {
        val connection = open(need(NativeCallProtocol.streamUrl(callId, after)), "GET", STREAM_READ_TIMEOUT_MS)
        connection.setRequestProperty("Accept", "text/event-stream")
        val status = connection.responseCode
        if (status !in 200..299) {
            connection.disconnect()
            throw ApiException(status, "stream HTTP $status")
        }
        return connection
    }

    fun cookie(): String? = CookieManager.getInstance().getCookie(CallAudioUrls.ORIGIN)

    private fun post(url: String, body: ByteArray? = null, type: String = "application/json",
                     readTimeout: Int = READ_TIMEOUT_MS, connectTimeout: Int = CONNECT_TIMEOUT_MS): JSONObject {
        val connection = open(url, "POST", readTimeout, connectTimeout)
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", type)
            connection.outputStream.use { it.write(body ?: ByteArray(0)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { input -> BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText() } ?: ""
            val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
            if (status !in 200..299) {
                throw ApiException(status, json.optString("error", json.optString("detail", "HTTP $status")))
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, method: String, readTimeout: Int,
                     connectTimeout: Int = CONNECT_TIMEOUT_MS): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeout
        connection.readTimeout = readTimeout
        cookie()?.takeIf { it.isNotEmpty() }?.let { connection.setRequestProperty("Cookie", it) }
        return connection
    }

    private fun need(url: String?): String = url ?: throw ApiException(0, "invalid call id or seq")

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
        const val INTERRUPT_TIMEOUT_MS = 5_000
        private const val HEAR_TIMEOUT_MS = 60_000
        // the server holds a stream window open ~25s, then sends its state and closes
        private const val STREAM_READ_TIMEOUT_MS = 60_000
    }
}
