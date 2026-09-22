package com.ali.assistant.notifications

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

class AssistantNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val extras = sbn.notification.extras
        val item = JSONObject().put("package", sbn.packageName).put("title", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()).put("text", extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()).put("time", sbn.postTime)
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        val fresh = JSONArray().put(item)
        for (i in 0 until minOf(old.length(), 79)) fresh.put(old.get(i))
        prefs.edit().putString(KEY, fresh.toString()).apply()
    }
    companion object {
        private const val PREFS = "assistant_notifications"
        private const val KEY = "recent"
        fun recent(context: Context, query: String?, limit: Int): JSONArray {
            val arr = runCatching { JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")) }.getOrDefault(JSONArray())
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val haystack = "${item.optString("title")} ${item.optString("text")} ${item.optString("package")}".lowercase()
                if (query.isNullOrBlank() || haystack.contains(query.lowercase())) out.put(item)
                if (out.length() >= limit.coerceIn(1, 100)) break
            }
            return out
        }
    }
}
