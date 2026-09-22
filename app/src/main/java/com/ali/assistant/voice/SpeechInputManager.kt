package com.ali.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class SpeechInputManager(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    fun listen(onState: (String) -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit) {
        stop()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { onError("Speech recognition is not available on this device"); return }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = onState("گوش می‌دم…")
                override fun onBeginningOfSpeech() = onState("دارم می‌شنوم…")
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = onState("دارم فکر می‌کنم…")
                override fun onError(error: Int) { onError("Speech error: $error"); stop() }
                override fun onResults(results: Bundle?) { val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty(); if (best.isBlank()) onError("چیزی متوجه نشدم") else onResult(best); stop() }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            })
        }
    }
    fun stop() { recognizer?.stopListening(); recognizer?.cancel(); recognizer?.destroy(); recognizer = null }
}
