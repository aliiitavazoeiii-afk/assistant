package com.ali.assistant.wake

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ali.assistant.MainActivity
import com.ali.assistant.core.PersianReminderParser
import com.ali.assistant.core.ReminderStore
import com.ali.assistant.reminders.ReminderScheduler
import com.openwakeword.OpenWakeWord

class BiyokWakeService : Service() {
    private var detector: OpenWakeWord? = null
    private var recognizer: SpeechRecognizer? = null
    private lateinit var store: ReminderStore
    private var capturing = false

    override fun onCreate() {
        super.onCreate()
        store = ReminderStore(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "بیوک", NotificationManager.IMPORTANCE_LOW))
        startForeground(4101, notification("بیوک آماده است"))
        startWakeDetector()
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("بیوک")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()

    private fun update(text: String) { getSystemService(NotificationManager::class.java).notify(4101, notification(text)) }

    private fun startWakeDetector() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            update("اجازه میکروفن لازم است")
            return
        }
        val hasModel = assets.list("")?.contains("biyok.onnx") == true
        if (!hasModel) {
            update("مدل wake word بیوک نصب نشده")
            return
        }
        runCatching {
            detector = OpenWakeWord.Builder(this)
                .setModelAsset("biyok.onnx")
                .setThreshold(0.52f)
                .setDebounceMs(2500)
                .build()
            detector?.start { onWakeDetected() }
            update("گوش‌به‌فرمان • بیوک")
        }.onFailure { update("خطای مدل بیوک: ${it.message}") }
    }

    private fun onWakeDetected() {
        if (capturing) return
        capturing = true
        detector?.stop()
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85).apply { startTone(ToneGenerator.TONE_PROP_ACK, 180); release() }
        update("شنیدم • یادآوری‌ات را بگو")
        startCommandCapture()
    }

    private fun startCommandCapture() {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onError(error: Int) { finishCapture("متوجه نشدم؛ دوباره بگو بیوک") }
                override fun onResults(results: android.os.Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isBlank()) finishCapture("متوجه نشدم؛ دوباره بگو بیوک") else saveCommand(text)
                }
                override fun onPartialResults(partialResults: android.os.Bundle?) = Unit
                override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            })
        }
    }

    private fun saveCommand(text: String) {
        val p = PersianReminderParser.parse(text)
        val id = store.add(p.text, p.remindAt)
        ReminderScheduler.schedule(this, id, p.text, p.remindAt)
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75).apply { startTone(ToneGenerator.TONE_PROP_BEEP2, 120); release() }
        finishCapture(if (p.remindAt == null) "ذخیره شد: ${p.text}" else "یادآوری تنظیم شد: ${p.text}")
    }

    private fun finishCapture(message: String) {
        recognizer?.destroy(); recognizer = null
        capturing = false
        update(message)
        android.os.Handler(mainLooper).postDelayed({
            if (!capturing) {
                runCatching { detector?.start { onWakeDetected() } }
                update("گوش‌به‌فرمان • بیوک")
            }
        }, 1200)
    }

    override fun onDestroy() {
        detector?.stop(); detector?.release(); detector = null
        recognizer?.destroy(); recognizer = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { private const val CHANNEL = "biyok_wake" }
}
