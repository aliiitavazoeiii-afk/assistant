package com.ali.assistant.biyok

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ali.assistant.MainActivity

object BiyokNotifications {
    const val REMINDER_CHANNEL = "biyok_reminders"
    const val SERVICE_CHANNEL = "biyok_wake"
    const val SETUP_CHANNEL = "biyok_setup"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(REMINDER_CHANNEL, "یادآوری‌های بیوک", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "نوتیفیکیشن یادآوری‌هایی که برای بیوک ثبت می‌کنی"
                enableVibration(true)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(SERVICE_CHANNEL, "گوش‌دادن بیوک", NotificationManager.IMPORTANCE_LOW).apply {
                description = "نشان می‌دهد Wake Word بیوک در پس‌زمینه روشن است"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(SETUP_CHANNEL, "وضعیت بیوک", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    fun postReminder(context: Context, item: ReminderItem) {
        ensureChannels(context)
        if (!canNotify(context)) return
        val open = PendingIntent.getActivity(
            context,
            item.id.toRequestCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_reminder_id", item.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val done = PendingIntent.getBroadcast(
            context,
            (item.id + 100_000).toRequestCode(),
            Intent(context, ReminderDoneReceiver::class.java).apply {
                data = Uri.parse("biyok://done/${item.id}")
                putExtra("id", item.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("یادآوری بیوک")
            .setContentText(item.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.checkbox_on_background, "انجام شد", done)
            .build()
        NotificationManagerCompat.from(context).notify(item.id.toRequestCode(), notification)
    }

    fun postWakeNeedsEnable(context: Context) {
        ensureChannels(context)
        if (!canNotify(context)) return
        val open = PendingIntent.getActivity(
            context, 7701,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        NotificationManagerCompat.from(context).notify(
            7701,
            NotificationCompat.Builder(context, SETUP_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("بیوک بعد از روشن‌شدن گوشی")
                .setContentText("برای فعال‌شدن میکروفن، یک بار بیوک را باز کن و Wake Word را روشن کن.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun Long.toRequestCode(): Int = (this % Int.MAX_VALUE).toInt().coerceAtLeast(1)
}

object ReminderScheduler {
    fun schedule(context: Context, item: ReminderItem) {
        val due = item.dueAt ?: return
        if (item.completed || due <= System.currentTimeMillis()) return
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pending = pendingIntent(context, item.id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending)
        }
    }

    fun cancel(context: Context, id: Long) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, id))
    }

    fun rescheduleAll(context: Context) {
        ReminderStore.get(context).pendingScheduled().forEach { schedule(context, it) }
    }

    fun hasExactAlarmAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    private fun pendingIntent(context: Context, id: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        (id % Int.MAX_VALUE).toInt().coerceAtLeast(1),
        Intent(context, ReminderReceiver::class.java).apply {
            data = Uri.parse("biyok://reminder/$id")
            putExtra("id", id)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1L)
        if (id <= 0) return
        val item = ReminderStore.get(context).get(id) ?: return
        if (!item.completed) BiyokNotifications.postReminder(context, item)
    }
}

class ReminderDoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1L)
        if (id <= 0) return
        ReminderStore.get(context).setCompleted(id, true)
        ReminderScheduler.cancel(context, id)
        NotificationManagerCompat.from(context).cancel((id % Int.MAX_VALUE).toInt().coerceAtLeast(1))
    }
}

class BiyokBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        BiyokNotifications.ensureChannels(context)
        ReminderScheduler.rescheduleAll(context)
        if (WakePreferences.isEnabled(context)) BiyokNotifications.postWakeNeedsEnable(context)
    }
}

object WakePreferences {
    private const val PREFS = "biyok_wake_preferences"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
