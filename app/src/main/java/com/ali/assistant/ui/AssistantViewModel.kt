package com.ali.assistant.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ali.assistant.network.AssistantApi
import com.ali.assistant.network.ToolCall
import com.ali.assistant.network.ToolOutput
import com.ali.assistant.tools.ToolExecutor
import com.ali.assistant.voice.LocalTts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone

data class PendingConfirmation(val title: String, val detail: String)

class AssistantViewModel(app: Application) : AndroidViewModel(app) {
    private val api = AssistantApi(app)
    private val tools = ToolExecutor(app)
    private val tts = LocalTts(app)
    private var pendingDecision: CompletableDeferred<Boolean>? = null

    var status by mutableStateOf("آماده"); private set
    var lastUserText by mutableStateOf(""); private set
    var lastAssistantText by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var serverUrl by mutableStateOf(api.serverUrl()); private set
    var appToken by mutableStateOf(api.appToken()); private set
    var pendingConfirmation by mutableStateOf<PendingConfirmation?>(null); private set

    fun saveServerUrl(value: String) { serverUrl = value; api.setServerUrl(value); status = "آدرس سرور ذخیره شد" }
    fun saveAppToken(value: String) { appToken = value; api.setAppToken(value); status = "توکن اتصال امن ذخیره شد" }

    fun submit(text: String) {
        val clean = text.trim(); if (clean.isBlank() || busy) return
        lastUserText = clean; busy = true; status = "دارم فکر می‌کنم…"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var reply = api.startTurn(clean, deviceContext()); var rounds = 0
                while (reply.toolCalls.isNotEmpty()) {
                    if (++rounds > 16) throw IllegalStateException("Tool loop exceeded 16 rounds")
                    withContext(Dispatchers.Main) { status = "دارم انجامش می‌دم… (${reply.toolCalls.joinToString { it.name }})" }
                    val outputs = mutableListOf<ToolOutput>()
                    for (call in reply.toolCalls) {
                        if (confirmIfNeeded(call)) outputs += tools.execute(call)
                        else outputs += ToolOutput(call.callId, JSONObject().put("success", false).put("error", "user_declined").toString())
                    }
                    reply = api.continueTurn(reply.responseId, outputs, deviceContext())
                }
                val finalText = reply.text.ifBlank { "انجام شد." }
                withContext(Dispatchers.Main) { lastAssistantText = finalText; status = "آماده"; tts.speak(finalText) }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { lastAssistantText = "خطا: ${t.message ?: t.javaClass.simpleName}"; status = "خطا" }
            } finally {
                withContext(Dispatchers.Main) { busy = false; pendingConfirmation = null; pendingDecision = null }
            }
        }
    }

    private suspend fun confirmIfNeeded(call: ToolCall): Boolean {
        val detail = tools.confirmationMessage(call) ?: return true
        val decision = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) { pendingDecision = decision; pendingConfirmation = PendingConfirmation("تأیید عملیات", detail); status = "منتظر تأیید تو هستم" }
        val approved = decision.await()
        withContext(Dispatchers.Main) { pendingDecision = null; pendingConfirmation = null; status = if (approved) "در حال اجرا…" else "لغو شد" }
        return approved
    }

    fun resolveConfirmation(approved: Boolean) { pendingDecision?.complete(approved) }
    fun setListeningStatus(value: String) { status = value }
    private fun deviceContext(): JSONObject = JSONObject().put("now", ZonedDateTime.now().toOffsetDateTime().toString()).put("timeZone", TimeZone.getDefault().id).put("locale", Locale.getDefault().toLanguageTag()).put("platform", "android").put("assistantVersion", "0.2.0")
    override fun onCleared() { pendingDecision?.cancel(); tts.shutdown(); super.onCleared() }
}
