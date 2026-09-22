package com.ali.assistant.network

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class AssistantApi(private val context: Context) {
    private val prefs = context.getSharedPreferences("assistant_settings", Context.MODE_PRIVATE)

    fun serverUrl(): String = prefs.getString("server_url", "http://10.0.2.2:8787")!!
        .trimEnd('/')

    fun setServerUrl(value: String) {
        prefs.edit().putString("server_url", value.trim().trimEnd('/')).apply()
    }

    fun appToken(): String = prefs.getString("app_token", "") ?: ""

    fun setAppToken(value: String) {
        prefs.edit().putString("app_token", value.trim()).apply()
    }

    fun startTurn(message: String, deviceContext: JSONObject): AgentReply {
        return post(
            "/v1/agent/turn",
            JSONObject()
                .put("message", message)
                .put("deviceContext", deviceContext)
        )
    }

    fun continueTurn(previousResponseId: String, outputs: List<ToolOutput>, deviceContext: JSONObject): AgentReply {
        val arr = JSONArray()
        outputs.forEach {
            arr.put(JSONObject().put("callId", it.callId).put("output", it.output))
        }
        return post(
            "/v1/agent/continue",
            JSONObject()
                .put("previousResponseId", previousResponseId)
                .put("toolOutputs", arr)
                .put("deviceContext", deviceContext)
        )
    }

    private fun post(path: String, body: JSONObject): AgentReply {
        val url = URL(serverUrl() + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 90_000
            setRequestProperty("Content-Type", "application/json")
            appToken().takeIf { it.isNotBlank() }?.let { setRequestProperty("X-Assistant-Token", it) }
            doOutput = true
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(InputStreamReader(stream)).use { it.readText() }
        if (code !in 200..299) throw IllegalStateException("Server $code: $text")
        val json = JSONObject(text)
        val tools = mutableListOf<ToolCall>()
        val calls = json.optJSONArray("toolCalls") ?: JSONArray()
        for (i in 0 until calls.length()) {
            val c = calls.getJSONObject(i)
            tools += ToolCall(
                callId = c.getString("callId"),
                name = c.getString("name"),
                argumentsJson = c.optString("arguments", "{}"),
            )
        }
        return AgentReply(
            responseId = json.getString("responseId"),
            text = json.optString("text", ""),
            toolCalls = tools,
        )
    }
}
