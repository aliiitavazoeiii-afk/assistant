package com.ali.assistant.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.ali.assistant.data.AssistantDb
import com.ali.assistant.data.ScheduleRecord
import java.time.OffsetDateTime

class AlarmScheduler(private val context: Context) {
    private val db = AssistantDb(context)

    fun scheduleAlarm(timestampIso: String, label: String, songQuery: String?, strict: Boolean, challenge: String): Long {
        val triggerAt = OffsetDateTime.parse(timestampIso).toInstant().toEpochMilli()
        require(triggerAt > System.currentTimeMillis()) { "Alarm time must be in the future" }
        val id = db.saveSchedule("ALARM", triggerAt, label, songQuery, strict, challenge)
        scheduleRecord(ScheduleRecord(id, "ALARM", triggerAt, label, songQuery, strict, challenge))
        return id
    }

    fun scheduleReminder(timestampIso: String, label: String): Long {
        val triggerAt = OffsetDateTime.parse(timestampIso).toInstant().toEpochMilli()
        require(triggerAt > System.currentTimeMillis()) { "Reminder time must be in the future" }
        val id = db.saveSchedule("REMINDER", triggerAt, label, null, false, "NONE")
        scheduleRecord(ScheduleRecord(id, "REMINDER", triggerAt, label, null, false, "NONE"))
        return id
    }

    fun rescheduleAll() = db.futureSchedules().forEach { runCatching { scheduleRecord(it) } }

    fun scheduleRecord(record: ScheduleRecord) {
        val manager = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms()) {
            throw SecurityException("Exact alarm access is disabled. Open Settings > Special app access > Alarms & reminders.")
        }
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("schedule_id", record.id)
            putExtra("kind", record.kind)
            putExtra("label", record.label)
            putExtra("song_query", record.songQuery)
            putExtra("strict", record.strict)
            putExtra("challenge", record.challenge)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            record.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val info = AlarmManager.AlarmClockInfo(record.triggerAt, pending)
        manager.setAlarmClock(info, pending)
    }

    fun exactAlarmSettingsIntent(): Intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
        data = android.net.Uri.parse("package:${context.packageName}")
    }
}
