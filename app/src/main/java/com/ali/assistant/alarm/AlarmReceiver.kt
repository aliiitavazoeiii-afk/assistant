package com.ali.assistant.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra("kind") ?: "ALARM"
        val target = if (kind == "REMINDER") ReminderService::class.java else AlarmSoundService::class.java
        val service = Intent(context, target).apply {
            putExtras(intent.extras ?: return)
        }
        ContextCompat.startForegroundService(context, service)
    }
}
