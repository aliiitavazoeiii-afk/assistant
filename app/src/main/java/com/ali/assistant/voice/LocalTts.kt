package com.ali.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class LocalTts(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile private var ready = false
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) { tts.language = Locale("fa", "IR"); tts.setSpeechRate(1.0f); ready = true }
    }
    fun speak(text: String) { if (ready && text.isNotBlank()) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant") }
    fun shutdown() { tts.stop(); tts.shutdown() }
}
