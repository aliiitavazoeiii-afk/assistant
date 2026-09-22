package com.ali.assistant.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.ali.assistant.R

class AlarmSoundService : Service() {
    private var player: MediaPlayer? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val label = intent?.getStringExtra("label") ?: "Wake up"
        val strict = intent?.getBooleanExtra("strict", false) ?: false
        val challenge = intent?.getStringExtra("challenge") ?: "NONE"
        val songQuery = intent?.getStringExtra("song_query")
        val scheduleId = intent?.getLongExtra("schedule_id", -1L) ?: -1L

        val activity = Intent(this, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("label", label)
            putExtra("strict", strict)
            putExtra("challenge", challenge)
            putExtra("schedule_id", scheduleId)
        }
        val fullScreen = PendingIntent.getActivity(
            this,
            scheduleId.toInt(),
            activity,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Assistant alarm")
            .setContentText(label)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(fullScreen, true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        startSound(songQuery)
        runCatching { startActivity(activity) }
        return START_NOT_STICKY
    }

    private fun startSound(query: String?) {
        player?.release()
        val uri = runCatching { findSong(query) }.getOrNull() ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setDataSource(this@AlarmSoundService, uri)
            isLooping = true
            prepare()
            start()
        }
    }

    private fun findSong(query: String?): android.net.Uri? {
        if (query.isNullOrBlank()) return null
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST)
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC}=1 AND (${MediaStore.Audio.Media.TITLE} LIKE ? OR ${MediaStore.Audio.Media.ARTIST} LIKE ?)",
            arrayOf("%$query%", "%$query%"),
            "${MediaStore.Audio.Media.TITLE} ASC",
        ).use { c ->
            if (c != null && c.moveToFirst()) {
                return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
            }
        }
        return null
    }

    override fun onDestroy() {
        player?.stop()
        player?.release()
        player = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Exact alarms from Assistant"
                setSound(null, null)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
    }

    companion object {
        const val ACTION_STOP = "com.ali.assistant.STOP_ALARM"
        private const val CHANNEL = "assistant_alarm"
        private const val NOTIFICATION_ID = 4101
    }
}
