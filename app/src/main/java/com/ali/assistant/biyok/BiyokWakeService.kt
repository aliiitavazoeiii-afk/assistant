package com.ali.assistant.biyok

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ali.assistant.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.util.concurrent.atomic.AtomicBoolean

enum class WakePhase { OFF, LOADING, WAKE_LISTENING, COMMAND_LISTENING, SAVED, ERROR }

data class WakeUiState(
    val enabled: Boolean = false,
    val phase: WakePhase = WakePhase.OFF,
    val message: String = "خاموش",
    val lastHeard: String = "",
)

object WakeStateBus {
    private val _state = MutableStateFlow(WakeUiState())
    val state: StateFlow<WakeUiState> = _state
    fun update(value: WakeUiState) { _state.value = value }
}

class BiyokWakeService : Service(), RecognitionListener {
    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null
    private var mode = Mode.WAKE
    private var lastCommandPartial = ""
    private val transitioning = AtomicBoolean(false)
    private lateinit var tone: ToneGenerator

    override fun onCreate() {
        super.onCreate()
        BiyokNotifications.ensureChannels(this)
        tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
        WakePreferences.setEnabled(this, true)
        startForeground(NOTIFICATION_ID, serviceNotification("در حال آماده‌سازی مدل فارسی…"))
        setState(WakePhase.LOADING, "در حال آماده‌سازی مدل فارسی…")
        loadModel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            WakePreferences.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun loadModel() {
        if (!hasMicPermission()) {
            fail("اجازه میکروفن داده نشده")
            return
        }
        StorageService.unpack(
            this,
            MODEL_ASSET,
            MODEL_STORAGE,
            { ready ->
                model = ready
                startWakeListening()
            },
            { error -> fail("مدل فارسی باز نشد: ${error.message ?: "خطای ناشناخته"}") }
        )
    }

    private fun startWakeListening() {
        if (!hasMicPermission()) {
            fail("اجازه میکروفن داده نشده")
            return
        }
        val readyModel = model ?: run {
            fail("مدل فارسی هنوز آماده نیست")
            return
        }
        if (transitioning.getAndSet(true)) return
        stopRecognition()
        mode = Mode.WAKE
        lastCommandPartial = ""
        try {
            recognizer = Recognizer(readyModel, SAMPLE_RATE, WAKE_GRAMMAR)
            speech = SpeechService(recognizer, SAMPLE_RATE).also { it.startListening(this) }
            setState(WakePhase.WAKE_LISTENING, "آماده • منتظر «بیوک»")
            updateServiceNotification("آماده • منتظر «بیوک»")
        } catch (t: Throwable) {
            fail("شروع Wake Word ناموفق بود: ${t.message ?: t.javaClass.simpleName}")
            main.postDelayed({ transitioning.set(false); startWakeListening() }, 1800)
            return
        } finally {
            transitioning.set(false)
        }
    }

    private fun triggerWake() {
        val readyModel = model ?: return
        if (transitioning.getAndSet(true) || mode != Mode.WAKE) return
        stopRecognition()
        mode = Mode.COMMAND
        lastCommandPartial = ""
        try {
            recognizer = Recognizer(readyModel, SAMPLE_RATE)
            speech = SpeechService(recognizer, SAMPLE_RATE).also { it.startListening(this, COMMAND_TIMEOUT_MS) }
            setState(WakePhase.COMMAND_LISTENING, "بگو چه چیزی یادت بماند…")
            updateServiceNotification("گوش می‌دهم • یادآوری‌ات را بگو")
            wakeTone()
        } catch (t: Throwable) {
            fail("گوش‌دادن به دستور شروع نشد: ${t.message ?: t.javaClass.simpleName}")
            main.postDelayed({ transitioning.set(false); startWakeListening() }, 1500)
            return
        } finally {
            transitioning.set(false)
        }
    }

    private fun handleCommand(raw: String) {
        val text = raw.trim()
        if (text.isBlank() || transitioning.getAndSet(true)) return
        stopRecognition()
        try {
            val parsed = PersianReminderParser.parse(text)
            when (parsed.kind) {
                VoiceCommandKind.SHOW_LIST -> {
                    val count = ReminderStore.get(this).openCount()
                    BiyokNotifications.postListSummary(this, count)
                    successTone()
                    setState(WakePhase.SAVED, "${count.toPersianDigits()} کار باز داری")
                }
                VoiceCommandKind.ADD -> {
                    val item = ReminderStore.get(this).add(
                        text = parsed.text,
                        sourceText = parsed.rawText,
                        dueAt = parsed.dueAtMillis,
                        byVoice = true,
                    )
                    ReminderScheduler.schedule(this, item)
                    successTone()
                    setState(
                        WakePhase.SAVED,
                        if (item.dueAt == null) "در Inbox ذخیره شد" else "یادآوری زمان‌دار ذخیره شد",
                        text
                    )
                }
            }
        } catch (t: Throwable) {
            failureTone()
            setState(WakePhase.ERROR, "ذخیره نشد: ${t.message ?: "خطا"}", text)
        } finally {
            main.postDelayed({ transitioning.set(false); startWakeListening() }, 650)
        }
    }

    override fun onPartialResult(hypothesis: String?) {
        val text = hypothesis.extractVoskText("partial")
        if (text.isBlank()) return
        if (mode == Mode.WAKE) {
            if (isWakePhrase(text)) triggerWake()
        } else {
            lastCommandPartial = text
            setState(WakePhase.COMMAND_LISTENING, "دارم می‌شنوم…", text)
        }
    }

    override fun onResult(hypothesis: String?) {
        val text = hypothesis.extractVoskText("text")
        if (text.isBlank()) return
        if (mode == Mode.WAKE) {
            if (isWakePhrase(text)) triggerWake()
        } else {
            handleCommand(text)
        }
    }

    override fun onFinalResult(hypothesis: String?) {
        val text = hypothesis.extractVoskText("text")
        if (mode == Mode.COMMAND && text.isNotBlank()) handleCommand(text)
    }

    override fun onTimeout() {
        if (mode != Mode.COMMAND || transitioning.get()) return
        val fallback = lastCommandPartial.trim()
        if (fallback.isNotBlank()) {
            handleCommand(fallback)
        } else {
            failureTone()
            setState(WakePhase.ERROR, "چیزی نشنیدم؛ دوباره «بیوک» بگو")
            main.postDelayed(::startWakeListening, 900)
        }
    }

    override fun onError(exception: Exception?) {
        if (transitioning.get()) return
        failureTone()
        setState(WakePhase.ERROR, "میکروفن: ${exception?.message ?: "خطا"}")
        main.postDelayed(::startWakeListening, 1800)
    }

    private fun isWakePhrase(value: String): Boolean {
        val compact = PersianReminderParser.normalize(value).replace(" ", "")
        return WAKE_VARIANTS.any { compact.contains(it) }
    }

    private fun stopRecognition() {
        runCatching { speech?.cancel() }
        runCatching { speech?.shutdown() }
        speech = null
        runCatching { recognizer?.close() }
        recognizer = null
    }

    private fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun serviceNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 4301,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 4302,
            Intent(this, BiyokWakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, BiyokNotifications.SERVICE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("بیوک")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "خاموش", stop)
            .build()
    }

    private fun updateServiceNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, serviceNotification(text))
    }

    private fun setState(phase: WakePhase, message: String, heard: String = "") {
        WakeStateBus.update(WakeUiState(true, phase, message, heard))
    }

    private fun fail(message: String) {
        setState(WakePhase.ERROR, message)
        updateServiceNotification(message)
    }

    private fun wakeTone() {
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
    }

    private fun successTone() {
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 70)
        main.postDelayed({ tone.startTone(ToneGenerator.TONE_PROP_BEEP, 70) }, 130)
    }

    private fun failureTone() {
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 280)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        stopRecognition()
        runCatching { model?.close() }
        model = null
        tone.release()
        WakeStateBus.update(WakeUiState(false, WakePhase.OFF, "خاموش"))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun String?.extractVoskText(key: String): String {
        if (this.isNullOrBlank()) return ""
        return runCatching { JSONObject(this).optString(key, "").trim() }.getOrDefault("")
    }

    private fun Int.toPersianDigits(): String = toString().map { c ->
        if (c in '0'..'9') "۰۱۲۳۴۵۶۷۸۹"[c - '0'] else c
    }.joinToString("")

    private enum class Mode { WAKE, COMMAND }

    companion object {
        const val ACTION_STOP = "com.ali.assistant.biyok.STOP_WAKE"
        private const val NOTIFICATION_ID = 4301
        private const val MODEL_ASSET = "vosk-fa"
        private const val MODEL_STORAGE = "vosk-fa-runtime"
        private const val SAMPLE_RATE = 16_000.0f
        private const val COMMAND_TIMEOUT_MS = 10_000
        private val WAKE_VARIANTS = listOf("بیوک", "بویوک", "بؤیوک", "بیوگ", "بیاک", "بیاوک")
        private const val WAKE_GRAMMAR = "[\"بیوک\",\"بویوک\",\"بؤیوک\",\"بیوگ\",\"بیاک\",\"بیاوک\",\"[unk]\"]"
    }
}
