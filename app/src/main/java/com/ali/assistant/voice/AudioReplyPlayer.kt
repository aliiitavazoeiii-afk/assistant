package com.ali.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

class AudioReplyPlayer(private val context: Context) {
    private var player: MediaPlayer? = null
    private var currentFile: File? = null

    fun play(mp3: ByteArray): Boolean {
        if (mp3.isEmpty()) return false
        stop()
        return runCatching {
            val file = File.createTempFile("assistant-reply-", ".mp3", context.cacheDir)
            file.writeBytes(mp3)
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .build()
            )
            p.setDataSource(file.absolutePath)
            p.setOnPreparedListener { it.start() }
            p.setOnCompletionListener { cleanup(it, file) }
            p.setOnErrorListener { mp, _, _ -> cleanup(mp, file); true }
            player = p
            currentFile = file
            p.prepareAsync()
            true
        }.getOrElse {
            stop()
            false
        }
    }

    fun stop() {
        player?.let { p ->
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        player = null
        currentFile?.delete()
        currentFile = null
    }

    private fun cleanup(mp: MediaPlayer, file: File) {
        runCatching { mp.release() }
        if (player === mp) player = null
        file.delete()
        if (currentFile == file) currentFile = null
    }
}
