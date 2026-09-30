package com.ali.assistant.wake

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class WakeFeaturesTest {
    @Test fun extractsAndSelfMatches() {
        val samples = ShortArray(WakeFeatures.SAMPLE_RATE) { i ->
            val envelope = if (i in 2500..12000) 1.0 else 0.0
            (sin(2.0 * PI * 330.0 * i / WakeFeatures.SAMPLE_RATE) * 9000.0 * envelope).toInt().toShort()
        }
        val f = WakeFeatures.extract(samples)
        assertTrue(f.isNotEmpty())
        assertTrue(f.size >= 8)
        assertEquals(0f, WakeFeatures.distance(f, f), 0.0001f)
    }

    @Test fun veryShortAudioIsRejected() {
        assertTrue(WakeFeatures.extract(ShortArray(1000)).isEmpty())
    }
}
