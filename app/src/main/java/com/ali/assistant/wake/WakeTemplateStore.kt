package com.ali.assistant.wake

import android.content.Context
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class WakeTemplateStore(context: Context) {
    private val prefs = context.getSharedPreferences("biyok_wake_templates", Context.MODE_PRIVATE)

    fun count(): Int = (0 until MAX_TEMPLATES).count { prefs.contains(key(it)) }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun add(features: Array<FloatArray>): Int {
        require(features.isNotEmpty())
        var index = (0 until MAX_TEMPLATES).firstOrNull { !prefs.contains(key(it)) } ?: 0
        if (count() >= MAX_TEMPLATES) {
            clear()
            index = 0
        }
        prefs.edit().putString(key(index), encode(features)).apply()
        return count()
    }

    fun templates(): List<Array<FloatArray>> = (0 until MAX_TEMPLATES).mapNotNull { i ->
        prefs.getString(key(i), null)?.let(::decode)
    }

    fun score(features: Array<FloatArray>): Float {
        val scores = templates().map { WakeFeatures.distance(features, it) }.filter { it.isFinite() }.sorted()
        if (scores.isEmpty()) return Float.POSITIVE_INFINITY
        return if (scores.size == 1) scores[0] else (scores[0] + scores[1]) / 2f
    }

    fun matches(features: Array<FloatArray>): Boolean {
        if (count() < REQUIRED_TEMPLATES) return false
        return score(features) <= MATCH_THRESHOLD
    }

    private fun encode(features: Array<FloatArray>): String {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeInt(features.size)
            d.writeInt(features.firstOrNull()?.size ?: 0)
            for (frame in features) for (v in frame) d.writeFloat(v)
        }
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun decode(raw: String): Array<FloatArray>? = runCatching {
        DataInputStream(ByteArrayInputStream(Base64.decode(raw, Base64.NO_WRAP))).use { d ->
            val frames = d.readInt().coerceIn(1, 300)
            val dims = d.readInt().coerceIn(1, 64)
            Array(frames) { FloatArray(dims) { d.readFloat() } }
        }
    }.getOrNull()

    private fun key(i: Int) = "template_$i"

    companion object {
        const val REQUIRED_TEMPLATES = 3
        private const val MAX_TEMPLATES = 3
        private const val MATCH_THRESHOLD = 0.74f
    }
}
