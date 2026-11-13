package io.github.ratr2.repack.dimension

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Seeded noise for terrain, with the same knobs as FastNoise (and so SkNoise): a base type, an optional fractal on top, frequency, octaves, lacunarity and
 * gain. Values come out in -1..1. Everything is a pure function of the seed and the position, so it is safe on the generator threads.
 */
class Noise(
    val type: Type = Type.PERLIN,
    val fractal: Fractal = Fractal.FBM,
    val frequency: Double = 0.01,
    val octaves: Int = 4,
    val lacunarity: Double = 2.0,
    val gain: Double = 0.5,
    val seed: Long = 0,
) {
    enum class Type { PERLIN, VALUE, CELLULAR }
    enum class Fractal { NONE, FBM, RIDGED, PING_PONG }

    private val octaveCount = octaves.coerceIn(1, 8)
    private val amplitudeSum: Double = run {
        var sum = 0.0
        var amp = 1.0
        repeat(if (fractal == Fractal.NONE) 1 else octaveCount) { sum += amp; amp *= gain }
        sum
    }

    fun at(x: Double, z: Double): Double = fractal(x, 0.0, z, false)

    fun at(x: Double, y: Double, z: Double): Double = fractal(x, y, z, true)

    private fun fractal(x: Double, y: Double, z: Double, three: Boolean): Double {
        if (fractal == Fractal.NONE) return base(x * frequency, y * frequency, z * frequency, 0, three)
        var sum = 0.0
        var amp = 1.0
        var f = frequency
        for (octave in 0 until octaveCount) {
            val n = base(x * f, y * f, z * f, octave, three)
            sum += amp * when (fractal) {
                Fractal.RIDGED -> (1.0 - abs(n)) * 2.0 - 1.0
                Fractal.PING_PONG -> pingPong((n + 1.0) * 2.0) - 1.0
                else -> n
            }
            amp *= gain
            f *= lacunarity
        }
        return (sum / amplitudeSum).coerceIn(-1.0, 1.0)
    }

    private fun pingPong(value: Double): Double {
        val t = value - floor(value / 2.0) * 2.0
        return if (t < 1.0) t else 2.0 - t
    }

    private fun base(x: Double, y: Double, z: Double, octave: Int, three: Boolean): Double = when (type) {
        Type.PERLIN -> perlin(x, y, z, seed + octave * 1013L)
        Type.VALUE -> value(x, y, z, seed + octave * 1013L)
        Type.CELLULAR -> cellular(x, y, z, seed + octave * 1013L, three)
    }

    private fun hash(x: Int, y: Int, z: Int, s: Long): Long {
        var h = s * 0x9E3779B97F4A7C15uL.toLong() xor (x * 0x632BE5ABL) xor (y * 0x85157AF5L) xor (z * 0x6C8E9CF5L)
        h = (h xor (h ushr 30)) * -0x40a7b892e31b1a47L
        h = (h xor (h ushr 27)) * -0x6b2fb644ecceee15L
        return h xor (h ushr 31)
    }

    private fun fade(t: Double) = t * t * t * (t * (t * 6 - 15) + 10)

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

    private fun grad(h: Long, x: Double, y: Double, z: Double): Double = when ((h and 15).toInt()) {
        0, 12 -> x + y
        1, 13 -> -x + y
        2 -> x - y
        3 -> -x - y
        4 -> x + z
        5 -> -x + z
        6 -> x - z
        7 -> -x - z
        8 -> y + z
        9, 14 -> -y + z
        10 -> y - z
        else -> -y - z
    }

    private fun perlin(x: Double, y: Double, z: Double, s: Long): Double {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val zi = floor(z).toInt()
        val xf = x - xi
        val yf = y - yi
        val zf = z - zi
        val u = fade(xf)
        val v = fade(yf)
        val w = fade(zf)
        fun g(dx: Int, dy: Int, dz: Int) = grad(hash(xi + dx, yi + dy, zi + dz, s), xf - dx, yf - dy, zf - dz)
        val x1 = lerp(lerp(g(0, 0, 0), g(1, 0, 0), u), lerp(g(0, 1, 0), g(1, 1, 0), u), v)
        val x2 = lerp(lerp(g(0, 0, 1), g(1, 0, 1), u), lerp(g(0, 1, 1), g(1, 1, 1), u), v)
        return (lerp(x1, x2, w) * 0.5).coerceIn(-1.0, 1.0)
    }

    private fun unit(h: Long): Double = ((h ushr 11).toDouble() / (1L shl 53).toDouble()) * 2.0 - 1.0

    private fun value(x: Double, y: Double, z: Double, s: Long): Double {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val zi = floor(z).toInt()
        val u = fade(x - xi)
        val v = fade(y - yi)
        val w = fade(z - zi)
        fun c(dx: Int, dy: Int, dz: Int) = unit(hash(xi + dx, yi + dy, zi + dz, s))
        val x1 = lerp(lerp(c(0, 0, 0), c(1, 0, 0), u), lerp(c(0, 1, 0), c(1, 1, 0), u), v)
        val x2 = lerp(lerp(c(0, 0, 1), c(1, 0, 1), u), lerp(c(0, 1, 1), c(1, 1, 1), u), v)
        return lerp(x1, x2, w)
    }

    /** Distance to the nearest of jittered points, one per cell, scaled to -1..1. */
    private fun cellular(x: Double, y: Double, z: Double, s: Long, three: Boolean): Double {
        val xi = floor(x).toInt()
        val yi = if (three) floor(y).toInt() else 0
        val zi = floor(z).toInt()
        var best = Double.MAX_VALUE
        for (dx in -1..1) for (dy in (if (three) -1..1 else 0..0)) for (dz in -1..1) {
            val h = hash(xi + dx, yi + dy, zi + dz, s)
            val px = xi + dx + (unit(h) * 0.5 + 0.5)
            val py = if (three) yi + dy + (unit(h * 31 + 7) * 0.5 + 0.5) else 0.0
            val pz = zi + dz + (unit(h * 131 + 11) * 0.5 + 0.5)
            val d = (x - px) * (x - px) + (if (three) (y - py) * (y - py) else 0.0) + (z - pz) * (z - pz)
            if (d < best) best = d
        }
        return (sqrt(best) * 1.2 - 1.0).coerceIn(-1.0, 1.0)
    }
}
