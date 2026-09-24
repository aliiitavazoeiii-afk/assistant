package com.ali.assistant.network

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class AssistantApi(private val context: Context) {
    private val prefs = context.getSharedPreferences("assistant_settings", Context.MODE_PRIVATE)
    private val secrets = SecureSecretStore(context)

    fun serverUrl(): String = prefs.getString("server_url", "http://10.0.2.2:8787")!!.trimEnd('/')
    fun setServerUrl(value: String) { prefs.edit().putString("server_url", value.trim().trimEnd('/')).apply() }
    fun appToken(): String = secrets.getAppToken()
    fun setAppToken(value: String) = secrets.setAppToken(value.trim())
    fun sessionId(): String {
        prefs.getString("session_id", null)?.takeIf { it.isNotBlank() }?.let { return it }
        val id = UUID.randomUUID().toString(); prefs.edit().putString("session_id", id).apply(); return id
    }

    fun startTurn(message: String, deviceContext: JSONObject): AgentReply = post(
        "/v1/agent/turn",
        JSONObject().put("message", message).put("sessionId", sessionId()).put("deviceContext", deviceContext)
    )

    fun continueTurn(previousResponseId: String, outputs: List<ToolOutput>, deviceContext: JSONObject): AgentReply {
        val arr = JSONArray(); outputs.forEach { arr.put(JSONObject().put("callId", it.callId).put("output", it.output)) }
        return post(
            "/v1/agent/continue",
            JSONObject().put("previousResponseId", previousResponseId).put("sessionId", sessionId()).put("toolOutputs", arr).put("deviceContext", deviceContext)
        )
    }

    private fun post(path: String, body: JSONObject): AgentReply {
        val conn = (URL(serverUrl() + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 90_000
            setRequestProperty("Content-Type", "application/json")
            appToken().takeIf { it.isNotBlank() }?.let { setRequestProperty("X-Assistant-Token", it) }
            doOutput = true
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode; val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(InputStreamReader(stream)).use { it.readText() }
        if (code !in 200..299) throw IllegalStateException("Server $code: $text")
        val json = JSONObject(text); val tools = mutableListOf<ToolCall>(); val calls = json.optJSONArray("toolCalls") ?: JSONArray()
        for (i in 0 until calls.length()) { val c = calls.getJSONObject(i); tools += ToolCall(c.getString("callId"), c.getString("name"), c.optString("arguments", "{}")) }
        return AgentReply(json.getString("responseId"), json.optString("text", ""), tools)
    }
}
