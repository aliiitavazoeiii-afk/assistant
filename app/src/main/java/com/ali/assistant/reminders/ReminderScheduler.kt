package com.ali.assistant.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object ReminderScheduler {
    fun schedule(context: Context, id: Long, text: String, atMillis: Long?) {
        if (atMillis == null) return
        val alarm = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, ReminderReceiver::class.java).putExtra("id", id).putExtra("text", text)
        val pi = PendingIntent.getBroadcast(context, id.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (android.os.Build.VERSION.SDK_INT >= 31 && !alarm.canScheduleExactAlarms()) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        } else alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
    }
}
