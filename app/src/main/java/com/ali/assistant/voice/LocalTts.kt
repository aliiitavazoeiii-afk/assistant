package com.ali.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class LocalTts(context: Context) : TextToSpeech.OnInitListener {
    private enum class State { INITIALIZING, READY, UNSUPPORTED, FAILED }

    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile private var state = State.INITIALIZING
    @Volatile private var pendingText: String? = null

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            state = State.FAILED
            pendingText = null
            return
        }

        val supported = runCatching {
            val persianVoices = tts.voices?.filter { it.locale.language.equals("fa", ignoreCase = true) }.orEmpty()
            val persianVoice = persianVoices.firstOrNull { it.locale.toLanguageTag().equals("fa-IR", ignoreCase = true) }
                ?: persianVoices.firstOrNull()

            if (persianVoice != null) {
                tts.voice = persianVoice
                true
            } else {
                listOf(Locale("fa", "IR"), Locale("fa")).any { locale ->
                    val available = tts.isLanguageAvailable(locale)
                    if (available >= TextToSpeech.LANG_AVAILABLE) {
                        tts.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE
                    } else false
                }
            }
        }.getOrDefault(false)

        if (!supported) {
            state = State.UNSUPPORTED
            pendingText = null
            return
        }

        tts.setSpeechRate(0.98f)
        state = State.READY
        pendingText?.also {
            pendingText = null
            speakNow(it)
        }
    }

    fun speak(text: String): Boolean {
        val clean = text.trim()
        if (clean.isBlank()) return false
        return when (state) {
            State.READY -> speakNow(clean)
            State.INITIALIZING -> {
                pendingText = clean
                true
            }
            State.UNSUPPORTED, State.FAILED -> false
        }
    }

    private fun speakNow(text: String): Boolean =
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant") != TextToSpeech.ERROR

    fun shutdown() {
        pendingText = null
        tts.stop()
        tts.shutdown()
    }
}
