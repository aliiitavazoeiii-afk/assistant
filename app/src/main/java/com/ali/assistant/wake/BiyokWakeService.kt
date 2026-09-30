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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ali.assistant.MainActivity
import com.ali.assistant.core.ReminderStore
import com.ali.assistant.reminders.ReminderScheduler
import com.ali.assistant.server.BiyokRemoteConfig
import com.ali.assistant.server.BiyokServerClient
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import kotlin.math.sqrt

class BiyokWakeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var reminders: ReminderStore
    private lateinit var templates: WakeTemplateStore
    private lateinit var server: BiyokServerClient
    private var wakeRecorder: AudioRecord? = null
    private var wakeThread: Thread? = null
    private var commandThread: Thread? = null
    @Volatile private var wakeRunning = false
    @Volatile private var capturingCommand = false
    @Volatile private var config = BiyokRemoteConfig()
    private var lastWakeAt = 0L
    private var lastConfigFetchAt = 0L

    override fun onCreate() {
        super.onCreate()
        reminders = ReminderStore(this)
        templates = WakeTemplateStore(this)
        server = BiyokServerClient(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "بیوک", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTIFICATION_ID, notification("در حال آماده‌سازی…"))
        refreshConfigAsync(force = true)
        startWakeLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refreshConfigAsync()
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

    private fun refreshConfigAsync(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastConfigFetchAt < 5 * 60_000) return
        lastConfigFetchAt = now
        Thread {
            runCatching { server.getConfig() }.onSuccess { config = it }
        }.apply { name = "biyok-config"; start() }
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
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    WakeFeatures.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(min, 4096)
                )
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
        val chunk = ShortArray(CHUNK_SAMPLES)
        val pre = ShortArray((WakeFeatures.SAMPLE_RATE * 240) / 1000)
        var prePos = 0
        var segment = ShortArray(samplesForMs(config.wakeMaxSegmentMs))
        var segPos = 0
        var inSpeech = false
        var loudFrames = 0
        var silentFrames = 0
        var noiseFloor = 80.0
        var ambientFrames = 0

        while (wakeRunning && !capturingCommand) {
            val c = config
            val wantedSegment = samplesForMs(c.wakeMaxSegmentMs)
            if (!inSpeech && segment.size != wantedSegment) segment = ShortArray(wantedSegment)
            val n = recorder.read(chunk, 0, chunk.size)
            if (n <= 0) continue
            for (i in 0 until n) {
                pre[prePos] = chunk[i]
                prePos = (prePos + 1) % pre.size
            }
            val level = rms(chunk, n)
            if (!inSpeech) {
                if (ambientFrames < 60 || loudFrames == 0) {
                    noiseFloor = noiseFloor * 0.97 + level * 0.03
                    ambientFrames++
                }
                val startThreshold = maxOf(c.wakeMinStartRms, noiseFloor * c.wakeStartNoiseMultiplier)
                if (level > startThreshold) loudFrames++ else loudFrames = (loudFrames - 1).coerceAtLeast(0)
                if (loudFrames >= 2) {
                    inSpeech = true
                    segPos = 0
                    for (i in pre.indices) {
                        if (segPos >= segment.size) break
                        segment[segPos++] = pre[(prePos + i) % pre.size]
                    }
                    silentFrames = 0
                }
            } else {
                val canCopy = minOf(n, segment.size - segPos)
                if (canCopy > 0) {
                    System.arraycopy(chunk, 0, segment, segPos, canCopy)
                    segPos += canCopy
                }
                val endThreshold = maxOf(c.wakeMinEndRms, noiseFloor * c.wakeEndNoiseMultiplier)
                if (level > endThreshold) silentFrames = 0 else silentFrames++
                val silenceNeeded = (c.wakeSilenceMs / CHUNK_MS).coerceAtLeast(4)
                if (silentFrames >= silenceNeeded || segPos >= segment.size) {
                    val candidate = segment.copyOf(segPos)
                    inSpeech = false
                    loudFrames = 0
                    silentFrames = 0
                    segPos = 0
                    if (checkWakeCandidate(candidate, c)) return
                }
            }
        }
    }

    private fun checkWakeCandidate(candidate: ShortArray, c: BiyokRemoteConfig): Boolean {
        val features = WakeFeatures.extract(candidate)
        if (features.isEmpty()) return false
        val score = templates.score(features)
        if (!score.isFinite()) return false
        val sensitivityShift = (templates.sensitivity() - 0.55f) * 0.28f
        val strongThreshold = c.wakeLocalStrongScore + sensitivityShift
        val verifyThreshold = c.wakeServerVerifyMaxScore + sensitivityShift

        if (score <= strongThreshold) {
            onWakeDetected()
            return true
        }
        if (c.wakeServerVerification && score <= verifyThreshold && server.settings.isReady()) {
            val accepted = runCatching { server.verifyWake(candidate, WakeFeatures.SAMPLE_RATE).accepted }.getOrDefault(false)
            if (accepted) {
                onWakeDetected()
                return true
            }
        }
        return false
    }

    private fun onWakeDetected() {
        val now = System.currentTimeMillis()
        if (now - lastWakeAt < 2500 || capturingCommand) return
        lastWakeAt = now
        capturingCommand = true
        wakeRunning = false
        runCatching { wakeRecorder?.stop() }
        main.post {
            playTone(ToneGenerator.TONE_PROP_ACK, 180, 88)
            update("شنیدم • راحت حرف بزن")
            main.postDelayed({ startCommandCapture() }, 260)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCommandCapture() {
        if (!server.settings.isReady()) {
            finishCommand("توکن سرور تنظیم نشده • داخل اپ بخش سرور رو کامل کن")
            return
        }
        commandThread = Thread {
            var recorder: AudioRecord? = null
            try {
                val c = config
                val min = AudioRecord.getMinBufferSize(WakeFeatures.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    WakeFeatures.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(min, 4096)
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("میکروفن برای دستور آماده نشد")
                val audio = captureCommand(recorder, c) ?: run {
                    main.post { finishCommand("صدایی نشنیدم • دوباره بگو بیوک") }
                    return@Thread
                }
                main.post { update("دارم می‌فهمم…") }
                val result = server.understand(audio, WakeFeatures.SAMPLE_RATE)
                main.post { applyServerCommand(result.command.action, result.command.title, result.command.remindAt, result.transcript) }
            } catch (t: Throwable) {
                main.post { finishCommand("ارتباط با سرور ناموفق بود • ${t.message ?: "دوباره امتحان کن"}") }
            } finally {
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
            }
        }.apply { name = "biyok-command"; start() }
    }

    private fun captureCommand(recorder: AudioRecord, c: BiyokRemoteConfig): ShortArray? {
        val chunk = ShortArray(CHUNK_SAMPLES)
        val preSize = samplesForMs(c.commandPreRollMs).coerceAtLeast(CHUNK_SAMPLES)
        val pre = ShortArray(preSize)
        var prePos = 0
        val maxSamples = samplesForMs(c.commandMaxMs)
        val out = ShortArray(maxSamples + preSize)
        var pos = 0
        var heardSpeech = false
        var loudFrames = 0
        var silentFrames = 0
        var noiseFloor = 75.0
        var totalFrames = 0
        val startTimeoutFrames = (c.commandStartTimeoutMs / CHUNK_MS).coerceAtLeast(20)
        val silenceNeeded = (c.commandSilenceMs / CHUNK_MS).coerceAtLeast(20)

        recorder.startRecording()
        while (pos < out.size) {
            val n = recorder.read(chunk, 0, chunk.size)
            if (n <= 0) continue
            totalFrames++
            for (i in 0 until n) {
                pre[prePos] = chunk[i]
                prePos = (prePos + 1) % pre.size
            }
            val level = rms(chunk, n)
            if (!heardSpeech) {
                noiseFloor = noiseFloor * 0.97 + level * 0.03
                val startThreshold = maxOf(c.commandMinStartRms, noiseFloor * c.commandStartNoiseMultiplier)
                if (level > startThreshold) loudFrames++ else loudFrames = (loudFrames - 1).coerceAtLeast(0)
                if (loudFrames >= 2) {
                    heardSpeech = true
                    for (i in pre.indices) {
                        if (pos >= out.size) break
                        out[pos++] = pre[(prePos + i) % pre.size]
                    }
                    silentFrames = 0
                } else if (totalFrames >= startTimeoutFrames) {
                    return null
                }
            } else {
                val canCopy = minOf(n, out.size - pos)
                if (canCopy > 0) {
                    System.arraycopy(chunk, 0, out, pos, canCopy)
                    pos += canCopy
                }
                val endThreshold = maxOf(c.commandMinEndRms, noiseFloor * c.commandEndNoiseMultiplier)
                if (level > endThreshold) silentFrames = 0 else silentFrames++
                if (silentFrames >= silenceNeeded) break
            }
        }
        if (!heardSpeech || pos < WakeFeatures.SAMPLE_RATE / 5) return null
        val silenceSamples = silentFrames * CHUNK_SAMPLES
        val keepTail = samplesForMs(220)
        val trimmedSize = (pos - (silenceSamples - keepTail).coerceAtLeast(0)).coerceAtLeast(WakeFeatures.SAMPLE_RATE / 5)
        return out.copyOf(trimmedSize)
    }

    private fun applyServerCommand(action: String, title: String, remindAt: String, transcript: String) {
        when (action) {
            "list_reminders" -> {
                val count = reminders.listOpen().size
                playTone(ToneGenerator.TONE_PROP_ACK, 120, 70)
                finishCommand(if (count == 0) "کاری در لیست نیست" else "$count کار باز داری • داخل اپ می‌بینیشون")
            }
            "create_reminder" -> {
                val cleanTitle = title.trim().ifBlank { transcript.trim() }
                if (cleanTitle.isBlank()) {
                    finishCommand("متوجه یادآوری نشدم • دوباره بگو بیوک")
                    return
                }
                val atMillis = parseTime(remindAt)
                val id = reminders.add(cleanTitle, atMillis)
                ReminderScheduler.schedule(this, id, cleanTitle, atMillis)
                playTone(ToneGenerator.TONE_PROP_BEEP2, 130, 72)
                finishCommand(if (atMillis == null) "ذخیره شد • $cleanTitle" else "یادآوری تنظیم شد • $cleanTitle")
            }
            else -> finishCommand("متوجه منظورت نشدم • دوباره بگو بیوک")
        }
    }

    private fun parseTime(value: String): Long? {
        if (value.isBlank()) return null
        return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .recoverCatching { ZonedDateTime.parse(value).toInstant().toEpochMilli() }
            .getOrNull()
    }

    private fun finishCommand(message: String) {
        capturingCommand = false
        update(message)
        refreshConfigAsync()
        main.postDelayed({ startWakeLoop() }, 1000)
    }

    private fun playTone(tone: Int, duration: Int, volume: Int) {
        val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, volume)
        tg.startTone(tone, duration)
        main.postDelayed({ runCatching { tg.release() } }, duration.toLong() + 80)
    }

    private fun samplesForMs(ms: Int): Int = ((WakeFeatures.SAMPLE_RATE.toLong() * ms) / 1000L).toInt().coerceAtLeast(CHUNK_SAMPLES)

    private fun rms(x: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) {
            val v = x[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / n.coerceAtLeast(1))
    }

    override fun onDestroy() {
        wakeRunning = false
        capturingCommand = false
        runCatching { wakeRecorder?.stop() }
        runCatching { wakeRecorder?.release() }
        wakeRecorder = null
        wakeThread?.interrupt()
        wakeThread = null
        commandThread?.interrupt()
        commandThread = null
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "biyok_wake"
        private const val NOTIFICATION_ID = 4101
        private const val CHUNK_SAMPLES = 320
        private const val CHUNK_MS = 20
    }
}
