package com.ali.assistant.tools

import android.content.Context
import com.ali.assistant.alarm.AlarmScheduler
import com.ali.assistant.data.AssistantDb
import com.ali.assistant.device.ContactsSmsTools
import com.ali.assistant.device.DeviceTools
import com.ali.assistant.network.ToolCall
import com.ali.assistant.network.ToolOutput
import com.ali.assistant.notifications.AssistantNotificationListener
import org.json.JSONArray
import org.json.JSONObject

class ToolExecutor(private val context: Context) {
    private val db = AssistantDb(context)
    private val alarmScheduler = AlarmScheduler(context)
    private val contactsSms = ContactsSmsTools(context)
    private val device = DeviceTools(context)

    fun confirmationMessage(call: ToolCall): String? = runCatching {
        val a = JSONObject(call.argumentsJson.ifBlank { "{}" })
        when (call.name) {
            "send_sms" -> "ارسال پیامک به ${a.optString("recipient")}:\n${a.optString("message")}"
            "call_contact" -> "تماس با ${a.optString("name")}?"
            else -> null
        }
    }.getOrNull()

    fun execute(call: ToolCall): ToolOutput {
        val output = runCatching {
            val a = JSONObject(call.argumentsJson.ifBlank { "{}" })
            when (call.name) {
                "save_note" -> JSONObject().put("ok", true).put("id", db.saveNote(a.optString("category", "ideas"), a.optString("title", "Idea"), a.getString("content")))
                "list_notes" -> JSONArray(db.listNotes(a.optString("category").takeIf { it.isNotBlank() }, a.optString("query").takeIf { it.isNotBlank() }, a.optInt("limit", 20)))
                "set_alarm" -> JSONObject().put("ok", true).put("id", alarmScheduler.scheduleAlarm(a.getString("timestamp"), a.optString("label", "Wake up"), a.optString("song_query").takeIf { it.isNotBlank() }, a.optBoolean("strict", false), if (a.optBoolean("strict", false)) a.optString("challenge", "SELFIE") else "NONE"))
                "set_reminder" -> JSONObject().put("ok", true).put("id", alarmScheduler.scheduleReminder(a.getString("timestamp"), a.getString("label")))
                "call_contact" -> contactsSms.callContact(a.getString("name"))
                "send_sms" -> contactsSms.sendSms(a.getString("recipient"), a.getString("message"))
                "read_sms" -> contactsSms.readSms(a.optString("start_iso").takeIf { it.isNotBlank() }, a.optString("end_iso").takeIf { it.isNotBlank() }, a.optString("query").takeIf { it.isNotBlank() }, a.optInt("limit", 50))
                "open_maps" -> device.openMaps(a.getString("destination"))
                "get_current_location" -> device.currentLocation()
                "open_app" -> device.openApp(a.getString("name"))
                "recent_notifications" -> AssistantNotificationListener.recent(context, a.optString("query").takeIf { it.isNotBlank() }, a.optInt("limit", 40))
                else -> throw IllegalArgumentException("Unknown device tool: ${call.name}")
            }
        }.fold(onSuccess = { JSONObject().put("success", true).put("result", it).toString() }, onFailure = { JSONObject().put("success", false).put("error", it.message ?: it.javaClass.simpleName).toString() })
        return ToolOutput(call.callId, output)
    }
}
