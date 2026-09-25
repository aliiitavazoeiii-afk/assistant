package com.ali.assistant.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

class SpeechInputManager(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    fun listen(onState: (String) -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit) {
        stop()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError("خطای گفتار 9: اجازه میکروفن داده نشده")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("سرویس تشخیص گفتار روی این گوشی در دسترس نیست")
            return
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = onState("گوش می‌دم…")
                override fun onBeginningOfSpeech() = onState("دارم می‌شنوم…")
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = onState("دارم فکر می‌کنم…")
                override fun onError(error: Int) {
                    onError(errorMessage(error))
                    stop()
                }
                override fun onResults(results: Bundle?) {
                    val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (best.isBlank()) onError("چیزی متوجه نشدم") else onResult(best)
                    stop()
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            })
        }
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "خطای گفتار 1: ارتباط سرویس تشخیص صدا timeout شد"
        SpeechRecognizer.ERROR_NETWORK -> "خطای گفتار 2: مشکل شبکه در سرویس تشخیص صدا"
        SpeechRecognizer.ERROR_AUDIO -> "خطای گفتار 3: میکروفن یا ضبط صدا در دسترس نیست"
        SpeechRecognizer.ERROR_SERVER -> "خطای گفتار 4: سرویس تشخیص صدا پاسخ نداد"
        SpeechRecognizer.ERROR_CLIENT -> "خطای گفتار 5: خطای داخلی کلاینت تشخیص صدا"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "صدایی شنیده نشد؛ دوباره صحبت کن"
        SpeechRecognizer.ERROR_NO_MATCH -> "صحبتت تشخیص داده نشد؛ دوباره امتحان کن"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "سرویس تشخیص صدا مشغول است؛ یک‌بار دیگر بزن"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "خطای گفتار 9: اجازه Microphone برای Assistant فعال نیست"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "درخواست‌های تشخیص صدا زیاد شده؛ دوباره امتحان کن"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "ارتباط با سرویس تشخیص صدا قطع شد"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "تشخیص گفتار فارسی توسط سرویس فعلی گوشی پشتیبانی نمی‌شود"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "مدل تشخیص گفتار فارسی روی گوشی در دسترس/دانلودشده نیست"
        else -> "خطای تشخیص گفتار: $error"
    }

    fun stop() {
        recognizer?.stopListening()
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }
}
