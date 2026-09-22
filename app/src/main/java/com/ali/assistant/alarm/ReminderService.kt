package com.ali.assistant.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import java.util.Locale

class ReminderService : Service(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var label: String = ""

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        label = intent?.getStringExtra("label") ?: "یادآوری"
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Assistant")
            .setContentText(label)
            .setAutoCancel(true)
            .build()
        startForeground(4201, notification)
        tts = TextToSpeech(this, this)
        return START_NOT_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("fa", "IR")
            tts?.speak("علی، الان وقتشه. $label", TextToSpeech.QUEUE_FLUSH, null, "reminder")
        }
        android.os.Handler(mainLooper).postDelayed({ stopSelf() }, 12_000)
    }

    override fun onDestroy() {
        tts?.stop(); tts?.shutdown(); tts = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object { private const val CHANNEL = "assistant_reminders" }
}
