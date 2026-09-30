package com.ali.assistant.wake

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ali.assistant.MainActivity
import com.ali.assistant.core.PersianReminderParser
import com.ali.assistant.core.ReminderStore
import com.ali.assistant.reminders.ReminderScheduler
import kotlin.math.sqrt

class BiyokWakeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var reminders: ReminderStore
    private lateinit var templates: WakeTemplateStore
    private var wakeRecorder: AudioRecord? = null
    private var wakeThread: Thread? = null
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var wakeRunning = false
    @Volatile private var capturingCommand = false
    private var lastWakeAt = 0L

    override fun onCreate() {
        super.onCreate()
        reminders = ReminderStore(this)
        templates = WakeTemplateStore(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "بیوک", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTIFICATION_ID, notification("در حال آماده‌سازی…"))
        startWakeLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!wakeRunning && !capturingCommand) startWakeLoop()
        return START_STICKY
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("بیوک")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()

    private fun update(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    @SuppressLint("MissingPermission")
    private fun startWakeLoop() {
        if (capturingCommand || wakeRunning) return
        if (templates.count() < WakeTemplateStore.REQUIRED_TEMPLATES) {
            update("اول داخل اپ ۳ بار «بیوک» را آموزش بده")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            update("اجازه میکروفن لازم است")
            return
        }
        wakeRunning = true
        wakeThread = Thread {
            var recorder: AudioRecord? = null
            try {
                val min = AudioRecord.getMinBufferSize(WakeFeatures.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeFeatures.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096))
                wakeRecorder = recorder
                if (recorder.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("میکروفن آماده نشد")
                recorder.startRecording()
                main.post { update("گوش‌به‌فرمان • بیوک") }
                monitorWake(recorder)
            } catch (t: Throwable) {
                if (wakeRunning) main.post { update("خطای میکروفن: ${t.message ?: "نامشخص"}") }
            } finally {
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
                if (wakeRecorder === recorder) wakeRecorder = null
                wakeRunning = false
            }
        }.apply { name = "biyok-wake"; start() }
    }

    private fun monitorWake(recorder: AudioRecord) {
        val chunk = ShortArray(320)
        val pre = ShortArray(3200)
        var prePos = 0
        val segment = ShortArray(WakeFeatures.SAMPLE_RATE * 2)
        var segPos = 0
        var inSpeech = false
        var loudFrames = 0
        var silentFrames = 0
        var noiseFloor = 120.0
        var ambientFrames = 0

        while (wakeRunning && !capturingCommand) {
            val n = recorder.read(chunk, 0, chunk.size)
            if (n <= 0) continue
            for (i in 0 until n) { pre[prePos] = chunk[i]; prePos = (prePos + 1) % pre.size }
            val level = rms(chunk, n)
            if (!inSpeech) {
                if (ambientFrames < 25 || loudFrames == 0) {
                    noiseFloor = noiseFloor * 0.96 + level * 0.04
                    ambientFrames++
                }
                val startThreshold = maxOf(270.0, noiseFloor * 2.25)
                if (level > startThreshold) loudFrames++ else loudFrames = (loudFrames - 1).coerceAtLeast(0)
                if (loudFrames >= 2) {
                    inSpeech = true
                    segPos = 0
                    for (i in pre.indices) segment[segPos++] = pre[(prePos + i) % pre.size]
                    silentFrames = 0
                }
            } else {
                val canCopy = minOf(n, segment.size - segPos)
                if (canCopy > 0) { System.arraycopy(chunk, 0, segment, segPos, canCopy); segPos += canCopy }
                val endThreshold = maxOf(220.0, noiseFloor * 1.48)
                if (level > endThreshold) silentFrames = 0 else silentFrames++
                if (silentFrames >= 12 || segPos >= segment.size) {
                    val candidate = segment.copyOf(segPos)
                    inSpeech = false; loudFrames = 0; silentFrames = 0; segPos = 0
                    val features = WakeFeatures.extract(candidate)
                    if (features.isNotEmpty() && templates.matches(features)) {
                        onWakeDetected()
                        return
                    }
                }
            }
        }
    }

    private fun onWakeDetected() {
        val now = System.currentTimeMillis()
        if (now - lastWakeAt < 3500 || capturingCommand) return
        lastWakeAt = now
        capturingCommand = true
        wakeRunning = false
        runCatching { wakeRecorder?.stop() }
        main.post {
            playTone(ToneGenerator.TONE_PROP_ACK, 180, 88)
            update("شنیدم • یادآوری‌ات را بگو")
            main.postDelayed({ startCommandCapture() }, 260)
        }
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
                override fun onError(error: Int) { finishCommand("متوجه نشدم • دوباره بگو بیوک") }
                override fun onResults(results: android.os.Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                    if (text.isBlank()) finishCommand("متوجه نشدم • دوباره بگو بیوک") else handleCommand(text)
                }
                override fun onPartialResults(partialResults: android.os.Bundle?) = Unit
                override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            })
        }
    }

    private fun handleCommand(text: String) {
        val normalized = text.replace('‌', ' ')
        val wantsList = listOf("چه کارهایی", "کارهایی که", "کارهای من", "یادآوری هام", "یادآوری های من").any { normalized.contains(it) }
        if (wantsList) {
            val count = reminders.listOpen().size
            playTone(ToneGenerator.TONE_PROP_ACK, 120, 70)
            finishCommand(if (count == 0) "کاری در لیست نیست" else "$count کار باز داری • برای دیدن، بیوک را باز کن")
            return
        }
        val parsed = PersianReminderParser.parse(text)
        val id = reminders.add(parsed.text, parsed.remindAt)
        ReminderScheduler.schedule(this, id, parsed.text, parsed.remindAt)
        playTone(ToneGenerator.TONE_PROP_BEEP2, 130, 72)
        finishCommand(if (parsed.remindAt == null) "ذخیره شد • ${parsed.text}" else "یادآوری تنظیم شد • ${parsed.text}")
    }

    private fun finishCommand(message: String) {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        capturingCommand = false
        update(message)
        main.postDelayed({ startWakeLoop() }, 900)
    }

    private fun playTone(tone: Int, duration: Int, volume: Int) {
        val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, volume)
        tg.startTone(tone, duration)
        main.postDelayed({ runCatching { tg.release() } }, duration.toLong() + 80)
    }

    private fun rms(x: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) { val v = x[i].toDouble(); sum += v * v }
        return sqrt(sum / n.coerceAtLeast(1))
    }

    override fun onDestroy() {
        wakeRunning = false
        runCatching { wakeRecorder?.stop() }
        runCatching { wakeRecorder?.release() }
        wakeRecorder = null
        wakeThread?.interrupt(); wakeThread = null
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "biyok_wake"
        private const val NOTIFICATION_ID = 4101
    }
}
