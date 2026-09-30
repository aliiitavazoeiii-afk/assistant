package com.ali.assistant.wake

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlin.math.sqrt

class WakeEnrollmentRecorder(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var recording = false

    @SuppressLint("MissingPermission")
    fun recordOnce(onState: (String) -> Unit, onDone: (Boolean, String) -> Unit) {
        if (recording) return
        recording = true
        onState("فقط یک بار واضح بگو: بیوک")
        Thread {
            var recorder: AudioRecord? = null
            try {
                val min = AudioRecord.getMinBufferSize(WakeFeatures.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeFeatures.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096))
                if (recorder.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("میکروفن آماده نشد")
                val data = captureUtterance(recorder)
                val features = WakeFeatures.extract(data)
                if (features.size < 8) throw IllegalStateException("صدا خیلی کوتاه بود؛ دوباره واضح بگو")
                val store = WakeTemplateStore(context)
                val count = store.add(features)
                main.post { onDone(true, if (count >= WakeTemplateStore.REQUIRED_TEMPLATES) "آموزش بیوک کامل شد" else "نمونه $count از ${WakeTemplateStore.REQUIRED_TEMPLATES} ثبت شد") }
            } catch (t: Throwable) {
                main.post { onDone(false, t.message ?: "ثبت نمونه ناموفق بود") }
            } finally {
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
                recording = false
            }
        }.start()
    }

    fun isRecording() = recording

    private fun captureUtterance(recorder: AudioRecord): ShortArray {
        val chunk = ShortArray(320)
        val all = ShortArray(WakeFeatures.SAMPLE_RATE * 3)
        var pos = 0
        var heardSpeech = false
        var speechFrames = 0
        var silenceFrames = 0
        var frames = 0
        recorder.startRecording()
        while (frames++ < 200 && pos + chunk.size <= all.size) {
            val n = recorder.read(chunk, 0, chunk.size)
            if (n <= 0) continue
            val rms = rms(chunk, n)
            if (rms > 520) {
                heardSpeech = true
                speechFrames++
                silenceFrames = 0
            } else if (heardSpeech) silenceFrames++
            if (heardSpeech || frames < 18) {
                System.arraycopy(chunk, 0, all, pos, n)
                pos += n
            }
            if (heardSpeech && speechFrames >= 3 && silenceFrames >= 14) break
            if (heardSpeech && pos >= WakeFeatures.SAMPLE_RATE * 2) break
        }
        if (!heardSpeech) throw IllegalStateException("صدای بیوک شنیده نشد")
        return all.copyOf(pos)
    }

    private fun rms(x: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) { val v = x[i].toDouble(); sum += v * v }
        return sqrt(sum / n.coerceAtLeast(1))
    }
}
