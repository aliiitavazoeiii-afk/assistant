package com.ali.assistant.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.ali.assistant.MainActivity

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        val id = intent.getLongExtra("id", System.currentTimeMillis()).toInt()
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("biyok_reminders", "یادآوری‌ها", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(id, NotificationCompat.Builder(context, "biyok_reminders").setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle("بیوک یادآوری می‌کند").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(open).setPriority(NotificationCompat.PRIORITY_HIGH).build())
    }
}
