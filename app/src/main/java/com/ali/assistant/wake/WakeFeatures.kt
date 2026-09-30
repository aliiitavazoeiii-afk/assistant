package com.ali.assistant.wake

import kotlin.math.*

object WakeFeatures {
    const val SAMPLE_RATE = 16000
    private const val FRAME = 400
    private const val HOP = 160
    private const val FFT = 512
    private const val BANDS = 20

    fun extract(input: ShortArray): Array<FloatArray> {
        if (input.isEmpty()) return emptyArray()
        val trimmed = trimSilence(input)
        if (trimmed.size < 2400) return emptyArray()
        val frames = max(1, 1 + (trimmed.size - FRAME).coerceAtLeast(0) / HOP)
        val out = Array(frames) { FloatArray(BANDS) }
        for (fi in 0 until frames) {
            val start = fi * HOP
            val re = DoubleArray(FFT)
            val im = DoubleArray(FFT)
            for (i in 0 until FRAME) {
                val idx = start + i
                val x = if (idx < trimmed.size) trimmed[idx] / 32768.0 else 0.0
                val prev = if (idx > 0 && idx - 1 < trimmed.size) trimmed[idx - 1] / 32768.0 else 0.0
                val emphasized = x - 0.97 * prev
                val window = 0.54 - 0.46 * cos(2.0 * Math.PI * i / (FRAME - 1))
                re[i] = emphasized * window
            }
            fft(re, im)
            val power = DoubleArray(FFT / 2 + 1) { k -> re[k] * re[k] + im[k] * im[k] }
            val points = melBins()
            for (b in 0 until BANDS) {
                val left = points[b]
                val center = points[b + 1]
                val right = points[b + 2]
                var energy = 0.0
                for (k in left until center) if (center > left) energy += power[k] * (k - left).toDouble() / (center - left)
                for (k in center until right) if (right > center) energy += power[k] * (right - k).toDouble() / (right - center)
                out[fi][b] = ln(energy + 1e-10).toFloat()
            }
        }
        normalize(out)
        return out
    }

    fun distance(a: Array<FloatArray>, b: Array<FloatArray>): Float {
        if (a.isEmpty() || b.isEmpty()) return Float.POSITIVE_INFINITY
        val ratio = a.size.toFloat() / b.size.toFloat()
        if (ratio < 0.48f || ratio > 2.1f) return Float.POSITIVE_INFINITY
        val prev = FloatArray(b.size + 1) { Float.POSITIVE_INFINITY }
        val cur = FloatArray(b.size + 1) { Float.POSITIVE_INFINITY }
        prev[0] = 0f
        for (i in a.indices) {
            cur.fill(Float.POSITIVE_INFINITY)
            for (j in b.indices) {
                var sum = 0f
                val dims = min(a[i].size, b[j].size)
                for (d in 0 until dims) {
                    val v = a[i][d] - b[j][d]
                    sum += v * v
                }
                val cost = sqrt(sum / dims.coerceAtLeast(1))
                cur[j + 1] = cost + minOf(prev[j + 1], cur[j], prev[j])
            }
            for (j in prev.indices) prev[j] = cur[j]
        }
        return prev[b.size] / (a.size + b.size)
    }

    private fun trimSilence(x: ShortArray): ShortArray {
        var maxAbs = 1
        for (v in x) maxAbs = max(maxAbs, abs(v.toInt()))
        val threshold = max(280, (maxAbs * 0.10).toInt())
        var first = 0
        while (first < x.size && abs(x[first].toInt()) < threshold) first++
        var last = x.lastIndex
        while (last > first && abs(x[last].toInt()) < threshold) last--
        val pad = 1200
        first = (first - pad).coerceAtLeast(0)
        last = (last + pad).coerceAtMost(x.lastIndex)
        return if (last > first) x.copyOfRange(first, last + 1) else x
    }

    private fun normalize(x: Array<FloatArray>) {
        if (x.isEmpty()) return
        for (d in 0 until BANDS) {
            var mean = 0.0
            for (f in x) mean += f[d]
            mean /= x.size
            var variance = 0.0
            for (f in x) variance += (f[d] - mean).pow(2)
            val std = sqrt(variance / x.size).coerceAtLeast(0.35)
            for (f in x) f[d] = ((f[d] - mean) / std).toFloat()
        }
    }

    private fun melBins(): IntArray {
        val lowMel = hzToMel(80.0)
        val highMel = hzToMel(7600.0)
        return IntArray(BANDS + 2) { i ->
            val mel = lowMel + (highMel - lowMel) * i / (BANDS + 1)
            val hz = melToHz(mel)
            floor((FFT + 1) * hz / SAMPLE_RATE).toInt().coerceIn(0, FFT / 2)
        }
    }

    private fun hzToMel(hz: Double) = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wLenR = cos(ang); val wLenI = sin(ang)
            var i = 0
            while (i < n) {
                var wr = 1.0; var wi = 0.0
                for (k in 0 until len / 2) {
                    val uR = re[i + k]; val uI = im[i + k]
                    val vR = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                    val vI = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                    re[i + k] = uR + vR; im[i + k] = uI + vI
                    re[i + k + len / 2] = uR - vR; im[i + k + len / 2] = uI - vI
                    val nextWr = wr * wLenR - wi * wLenI
                    wi = wr * wLenI + wi * wLenR; wr = nextWr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
