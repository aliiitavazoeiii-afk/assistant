package com.ali.assistant.server

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.OffsetDateTime
import java.time.ZoneId


data class BiyokRemoteConfig(
    val version: Int = 1,
    val wakeLocalStrongScore: Float = 0.64f,
    val wakeServerVerifyMaxScore: Float = 1.08f,
    val wakeMinStartRms: Double = 95.0,
    val wakeStartNoiseMultiplier: Double = 1.55,
    val wakeMinEndRms: Double = 75.0,
    val wakeEndNoiseMultiplier: Double = 1.22,
    val wakeSilenceMs: Int = 260,
    val wakeMaxSegmentMs: Int = 2800,
    val wakeServerVerification: Boolean = true,
    val commandMinStartRms: Double = 85.0,
    val commandStartNoiseMultiplier: Double = 1.45,
    val commandMinEndRms: Double = 70.0,
    val commandEndNoiseMultiplier: Double = 1.18,
    val commandStartTimeoutMs: Int = 8000,
    val commandSilenceMs: Int = 2200,
    val commandMaxMs: Int = 30000,
    val commandPreRollMs: Int = 260
)

data class WakeCheckResult(val accepted: Boolean, val transcript: String)

data class BiyokCommand(
    val action: String,
    val title: String,
    val remindAt: String,
    val confidence: Double
)

data class BiyokCommandResult(val transcript: String, val command: BiyokCommand)

class BiyokServerSettings(context: Context) {
    private val prefs = context.getSharedPreferences("biyok_server", Context.MODE_PRIVATE)

    fun baseUrl(): String = prefs.getString("base_url", DEFAULT_URL).orEmpty().trimEnd('/').ifBlank { DEFAULT_URL }
    fun setBaseUrl(value: String) { prefs.edit().putString("base_url", value.trim().trimEnd('/')).apply() }

    fun token(): String = prefs.getString("token", "").orEmpty().trim()
    fun setToken(value: String) { prefs.edit().putString("token", value.trim()).apply() }
    fun isReady(): Boolean = token().isNotBlank()

    companion object { const val DEFAULT_URL = "https://assistant.filmjadiid.ir" }
}

class BiyokServerClient(context: Context) {
    val settings = BiyokServerSettings(context.applicationContext)

    fun getConfig(): BiyokRemoteConfig {
        val root = request("GET", "/v1/biyok/config", auth = false)
        val wake = root.optJSONObject("wake") ?: JSONObject()
        val command = root.optJSONObject("command") ?: JSONObject()
        return BiyokRemoteConfig(
            version = root.optInt("version", 1),
            wakeLocalStrongScore = wake.optDouble("localStrongScore", 0.64).toFloat(),
            wakeServerVerifyMaxScore = wake.optDouble("serverVerifyMaxScore", 1.08).toFloat(),
            wakeMinStartRms = wake.optDouble("minStartRms", 95.0),
            wakeStartNoiseMultiplier = wake.optDouble("startNoiseMultiplier", 1.55),
            wakeMinEndRms = wake.optDouble("minEndRms", 75.0),
            wakeEndNoiseMultiplier = wake.optDouble("endNoiseMultiplier", 1.22),
            wakeSilenceMs = wake.optInt("silenceMs", 260),
            wakeMaxSegmentMs = wake.optInt("maxSegmentMs", 2800),
            wakeServerVerification = wake.optBoolean("serverVerification", true),
            commandMinStartRms = command.optDouble("minStartRms", 85.0),
            commandStartNoiseMultiplier = command.optDouble("startNoiseMultiplier", 1.45),
            commandMinEndRms = command.optDouble("minEndRms", 70.0),
            commandEndNoiseMultiplier = command.optDouble("endNoiseMultiplier", 1.18),
            commandStartTimeoutMs = command.optInt("startTimeoutMs", 8000),
            commandSilenceMs = command.optInt("silenceMs", 2200),
            commandMaxMs = command.optInt("maxMs", 30000),
            commandPreRollMs = command.optInt("preRollMs", 260)
        )
    }

    fun ping(): Boolean = request("GET", "/v1/biyok/ping", auth = true).optBoolean("ok", false)

    fun verifyWake(samples: ShortArray, sampleRate: Int): WakeCheckResult {
        val body = JSONObject()
            .put("audioBase64", encodePcm(samples))
            .put("sampleRate", sampleRate)
        val root = request("POST", "/v1/biyok/wake-check", body, auth = true, readTimeoutMs = 20_000)
        return WakeCheckResult(root.optBoolean("accepted", false), root.optString("transcript", ""))
    }

    fun understand(samples: ShortArray, sampleRate: Int): BiyokCommandResult {
        val zone = ZoneId.systemDefault()
        val body = JSONObject()
            .put("audioBase64", encodePcm(samples))
            .put("sampleRate", sampleRate)
            .put("deviceNow", OffsetDateTime.now(zone).toString())
            .put("timeZone", zone.id)
        val root = request("POST", "/v1/biyok/command", body, auth = true, readTimeoutMs = 45_000)
        val c = root.optJSONObject("command") ?: JSONObject()
        return BiyokCommandResult(
            transcript = root.optString("transcript", ""),
            command = BiyokCommand(
                action = c.optString("action", "unknown"),
                title = c.optString("title", ""),
                remindAt = c.optString("remindAt", ""),
                confidence = c.optDouble("confidence", 0.0)
            )
        )
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        auth: Boolean,
        readTimeoutMs: Int = 12_000
    ): JSONObject {
        val url = URL(settings.baseUrl() + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = readTimeoutMs
            useCaches = false
            setRequestProperty("Accept", "application/json")
            if (auth) {
                val token = settings.token()
                if (token.isBlank()) throw IllegalStateException("توکن سرور بیوک تنظیم نشده")
                setRequestProperty("X-Assistant-Token", token)
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) {
            val message = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
            throw IllegalStateException(if (message.isBlank()) "خطای سرور $code" else message)
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun encodePcm(samples: ShortArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putShort(it) }
        return Base64.encodeToString(bytes.array(), Base64.NO_WRAP)
    }
}
