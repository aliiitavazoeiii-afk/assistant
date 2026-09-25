package com.ali.assistant.wake

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ali.assistant.MainActivity
import com.ali.assistant.network.AssistantApi
import com.ali.assistant.voice.AudioReplyPlayer
import com.ali.assistant.voice.LocalTts
import java.util.Locale
import java.util.concurrent.Executors

class HotwordService : Service(), RecognitionListener {
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var api: AssistantApi
    private lateinit var audioPlayer: AudioReplyPlayer
    private lateinit var localTts: LocalTts

    @Volatile private var wakeAckAudio: ByteArray? = null
    private var stopping = false
    private var listening = false
    private var speaking = false
    private var usingOnDevice = false
    private var wakeCooldownUntil = 0L

    private val restartRunnable = Runnable { startRecognizerSession() }

    override fun onCreate() {
        super.onCreate()
        api = AssistantApi(this)
        audioPlayer = AudioReplyPlayer(this)
        localTts = LocalTts(this)

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Wake word", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, buildNotification("بیوک فعال است • در حال آماده‌سازی…"))

        initRecognizer(preferOnDevice = true)
        preloadWakeAck()
        scheduleRestart(350)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        stopping = false
        if (recognizer == null) initRecognizer(preferOnDevice = true)
        scheduleRestart(250)
        return START_STICKY
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Assistant • بیوک")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "خاموش",
            PendingIntent.getService(
                this,
                1,
                Intent(this, HotwordService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun initRecognizer(preferOnDevice: Boolean) {
        runCatching { recognizer?.destroy() }
        recognizer = null
        listening = false

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("Speech Recognition روی این گوشی در دسترس نیست")
            return
        }

        val canUseOnDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)

        recognizer = runCatching {
            if (preferOnDevice && canUseOnDevice) {
                usingOnDevice = true
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                usingOnDevice = false
                SpeechRecognizer.createSpeechRecognizer(this)
            }
        }.getOrElse {
            usingOnDevice = false
            runCatching { SpeechRecognizer.createSpeechRecognizer(this) }.getOrNull()
        }

        recognizer?.setRecognitionListener(this)
        updateNotification(
            if (usingOnDevice) "بیوک فعال • تشخیص تا جای ممکن روی خود گوشی"
            else "بیوک فعال • تشخیص با سرویس Speech گوشی"
        )
    }

    private fun recognizerIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        if (usingOnDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    private fun startRecognizerSession() {
        if (stopping || speaking || listening) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateNotification("اجازه میکروفن خاموش است • Wake Word متوقف شد")
            return
        }
        if (recognizer == null) initRecognizer(preferOnDevice = true)
        val r = recognizer ?: return
        listening = true
        runCatching { r.startListening(recognizerIntent()) }
            .onFailure {
                listening = false
                scheduleRestart(2500)
            }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (stopping || speaking) return
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, delayMs)
    }

    private fun preloadWakeAck() {
        worker.execute {
            val audio = runCatching { api.synthesizeSpeech(WAKE_ACK) }.getOrNull()
            if (audio != null && audio.isNotEmpty()) wakeAckAudio = audio
        }
    }

    private fun handleBundle(bundle: Bundle?): Boolean {
        val candidates = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        if (candidates.any(::containsWakeWord)) {
            triggerWake()
            return true
        }
        return false
    }

    private fun containsWakeWord(raw: String): Boolean {
        val normalized = raw
            .lowercase(Locale.ROOT)
            .replace('\u200c', ' ')
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
        val compact = normalized.replace(" ", "")
        return WAKE_VARIANTS.any { compact.contains(it) }
    }

    private fun triggerWake() {
        val now = System.currentTimeMillis()
        if (now < wakeCooldownUntil || speaking) return
        wakeCooldownUntil = now + 5_000
        speaking = true
        listening = false
        handler.removeCallbacks(restartRunnable)
        runCatching { recognizer?.cancel() }
        updateNotification("بیوک شنیده شد • در حال پاسخ…")

        val cached = wakeAckAudio
        if (cached != null && cached.isNotEmpty()) {
            playWakeAck(cached)
            return
        }

        worker.execute {
            val audio = runCatching { api.synthesizeSpeech(WAKE_ACK) }.getOrNull()
            if (audio != null && audio.isNotEmpty()) wakeAckAudio = audio
            handler.post {
                if (audio != null && audio.isNotEmpty()) playWakeAck(audio)
                else fallbackWakeAck()
            }
        }
    }

    private fun playWakeAck(audio: ByteArray) {
        val started = audioPlayer.play(audio) {
            speaking = false
            updateNotification(if (usingOnDevice) "بیوک فعال • تشخیص روی خود گوشی" else "بیوک فعال")
            scheduleRestart(600)
        }
        if (!started) fallbackWakeAck()
    }

    private fun fallbackWakeAck() {
        val started = localTts.speak(WAKE_ACK)
        speaking = false
        updateNotification(if (started) "بیوک فعال • پاسخ با صدای محلی" else "بیوک فعال • پاسخ صوتی در دسترس نبود")
        scheduleRestart(if (started) 1800 else 800)
    }

    private fun switchToDefaultRecognizer() {
        if (!usingOnDevice) return
        initRecognizer(preferOnDevice = false)
        scheduleRestart(700)
    }

    override fun onResults(results: Bundle?) {
        listening = false
        if (!handleBundle(results)) scheduleRestart(900)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        handleBundle(partialResults)
    }

    override fun onError(error: Int) {
        listening = false
        if (stopping || speaking) return

        if (usingOnDevice && (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
            updateNotification("مدل فارسی آفلاین موجود نیست • سوییچ به Speech گوشی")
            switchToDefaultRecognizer()
            return
        }

        when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                updateNotification("اجازه میکروفن خاموش است • از تنظیمات فعالش کن")
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_CLIENT -> scheduleRestart(2500)
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER -> scheduleRestart(3000)
            else -> scheduleRestart(1100)
        }
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onDestroy() {
        stopping = true
        listening = false
        handler.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        audioPlayer.stop()
        localTts.shutdown()
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "assistant_hotword"
        private const val NOTIFICATION_ID = 4301
        private const val ACTION_STOP = "com.ali.assistant.wake.STOP"
        private const val WAKE_ACK = "در خدمتم"
        private val WAKE_VARIANTS = listOf("بیوک", "بییوک", "بیاوک", "بایوک", "biyok", "biok")
    }
}
