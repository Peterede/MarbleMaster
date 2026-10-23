package com.edens.marblemaster.game

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Every one-shot sound the game can make. */
enum class SoundEffect {
    DROP,
    MATCH,
    MATCH_CHAIN,
    BLAST,
    LAUNCH,
    LAND,
    LEVEL_UP,
    GAME_OVER
}

/**
 * Synthesises the game's sound effects and its looping music into 16-bit mono PCM.
 *
 * Nothing is shipped as an audio file: the chiptune loop and every effect are generated
 * procedurally, which keeps the APK tiny and the result deterministic. This file is pure
 * Kotlin with no Android imports, so the JVM smoke test can exercise every buffer.
 */
object AudioSynth {

    const val SAMPLE_RATE = 22050

    /** Length of one full music loop, in seconds. */
    const val MUSIC_SECONDS = 16

    private const val TAU = 2.0 * PI
    private const val MAX_SHORT = 32767f

    fun effect(effect: SoundEffect): ShortArray = when (effect) {
        SoundEffect.DROP -> finish(renderDrop(), 0.45f)
        SoundEffect.MATCH -> finish(renderMatch(), 0.60f)
        SoundEffect.MATCH_CHAIN -> finish(renderChain(), 0.65f)
        SoundEffect.BLAST -> finish(renderBlast(), 0.90f)
        SoundEffect.LAUNCH -> finish(renderLaunch(), 0.60f)
        SoundEffect.LAND -> finish(renderLand(), 0.40f)
        SoundEffect.LEVEL_UP -> finish(renderLevelUp(), 0.65f)
        SoundEffect.GAME_OVER -> finish(renderGameOver(), 0.60f)
    }

    /** A seamless 16-second chip-tune loop (Am–F–C–G, twice) at 120 bpm. */
    fun musicLoop(): ShortArray {
        val beat = 0.25f
        val barSeconds = 2.0f
        val bars = 8
        val out = FloatArray(samples(MUSIC_SECONDS.toFloat()))
        for (bar in 0 until bars) {
            val chord = CHORDS[bar % CHORDS.size]
            val barStart = bar * barSeconds
            renderBass(out, barStart, barSeconds, chord.bass)
            renderPad(out, barStart, barSeconds, chord.pad)
            for (step in 0 until 8) {
                renderPluck(out, barStart + step * beat, chord.arp[ARP_STEPS[step]])
            }
            for (step in 0 until 8) {
                renderHat(out, barStart + step * beat + beat * 0.5f)
            }
        }
        return finish(out, 0.55f)
    }

    // ------------------------------------------------------------------ music

    private class Chord(val bass: Double, val pad: DoubleArray, val arp: DoubleArray)

    private val CHORDS = arrayOf(
        // A minor
        Chord(110.0, doubleArrayOf(220.0, 261.63, 329.63), doubleArrayOf(440.0, 523.25, 659.25, 523.25)),
        // F major
        Chord(87.31, doubleArrayOf(174.61, 220.0, 261.63), doubleArrayOf(349.23, 440.0, 523.25, 440.0)),
        // C major
        Chord(130.81, doubleArrayOf(261.63, 329.63, 392.0), doubleArrayOf(523.25, 659.25, 783.99, 659.25)),
        // G major
        Chord(98.0, doubleArrayOf(196.0, 246.94, 293.66), doubleArrayOf(392.0, 493.88, 587.33, 493.88))
    )

    private val ARP_STEPS = intArrayOf(0, 1, 2, 3, 2, 1, 0, 2)

    private fun renderBass(out: FloatArray, startSec: Float, dur: Float, freq: Double) {
        val from = min(out.size, samples(startSec))
        val to = min(out.size, samples(startSec + dur))
        var phase = 0.0
        for (i in from until to) {
            val t = (i - from).toFloat() / SAMPLE_RATE
            val env = min(1f, t / 0.02f) * min(1f, (dur - t) / 0.10f).coerceAtLeast(0f)
            phase += TAU * freq / SAMPLE_RATE
            val v = sin(phase) + 0.30 * sin(phase * 2.0) + 0.15 * sin(phase * 0.5)
            out[i] += (v * env * 0.16).toFloat()
        }
    }

    private fun renderPad(out: FloatArray, startSec: Float, dur: Float, freqs: DoubleArray) {
        val from = min(out.size, samples(startSec))
        val to = min(out.size, samples(startSec + dur))
        for (freq in freqs) {
            var phase = 0.0
            for (i in from until to) {
                val t = (i - from).toFloat() / SAMPLE_RATE
                val env = min(1f, t / 0.30f) * min(1f, (dur - t) / 0.30f).coerceAtLeast(0f)
                phase += TAU * freq / SAMPLE_RATE
                out[i] += (sin(phase) * env * 0.04).toFloat()
            }
        }
    }

    private fun renderPluck(out: FloatArray, startSec: Float, freq: Double) {
        val length = 0.22f
        val from = min(out.size, samples(startSec))
        val to = min(out.size, samples(startSec + length))
        var phase = 0.0
        for (i in from until to) {
            val t = (i - from).toFloat() / SAMPLE_RATE
            val env = min(1f, t / 0.004f) *
                exp(-t / 0.075) *
                min(1f, (length - t) / 0.02f).coerceAtLeast(0f)
            phase += TAU * freq / SAMPLE_RATE
            val v = sin(phase) + 0.30 * sin(phase * 2.0) + 0.08 * sin(phase * 3.0)
            out[i] += (v * env * 0.085).toFloat()
        }
    }

    private fun renderHat(out: FloatArray, startSec: Float) {
        val from = min(out.size, samples(startSec))
        val to = min(out.size, from + samples(0.03f))
        val rng = Random(11)
        var lp = 0.0
        for (i in from until to) {
            val t = (i - from).toFloat() / SAMPLE_RATE
            val noise = rng.nextDouble() * 2.0 - 1.0
            lp += (noise - lp) * 0.6
            val env = min(1f, t / 0.001f) * exp(-t / 0.012)
            out[i] += ((noise - lp) * env * 0.03).toFloat()
        }
    }

    // ------------------------------------------------------------------ effects

    private fun renderDrop(): FloatArray {
        val out = FloatArray(samples(0.13f))
        var phase = 0.0
        for (i in out.indices) {
            val t = i.toFloat() / SAMPLE_RATE
            val freq = 760.0 - 2300.0 * t
            phase += TAU * freq / SAMPLE_RATE
            val env = min(1f, t / 0.004f) * exp(-t / 0.06)
            out[i] = ((sin(phase) * 0.8 + sin(phase * 2.0) * 0.15) * env).toFloat()
        }
        return out
    }

    private fun renderMatch(): FloatArray {
        val out = FloatArray(samples(0.42f))
        addBell(out, 0f, 880.0, 0.60f, 0.10f)
        addBell(out, 0.055f, 1318.51, 0.42f, 0.09f)
        return out
    }

    private fun renderChain(): FloatArray {
        val out = FloatArray(samples(0.50f))
        addBell(out, 0f, 1046.50, 0.50f, 0.11f)
        addBell(out, 0.05f, 1567.98, 0.45f, 0.11f)
        addBell(out, 0.10f, 2093.00, 0.40f, 0.12f)
        return out
    }

    private fun renderBlast(): FloatArray {
        val out = FloatArray(samples(0.45f))
        val rng = Random(42)
        var phase = 0.0
        var lp = 0.0
        for (i in out.indices) {
            val t = i.toFloat() / SAMPLE_RATE
            val freq = max(30.0, 115.0 - 180.0 * t)
            phase += TAU * freq / SAMPLE_RATE
            val boomEnv = min(1f, t / 0.005f) * exp(-t / 0.16)
            val boom = sin(phase) * boomEnv * 0.9
            val noise = rng.nextFloat() * 2f - 1f
            lp += (noise - lp) * 0.12f
            val crackle = (noise - lp) * exp(-t / 0.06) * 0.6
            out[i] = (boom + crackle).toFloat()
        }
        return out
    }

    private fun renderLaunch(): FloatArray {
        val out = FloatArray(samples(0.50f))
        var phase = 0.0
        for (i in out.indices) {
            val t = i.toFloat() / SAMPLE_RATE
            val freq = 260.0 + 3000.0 * t
            phase += TAU * freq / SAMPLE_RATE
            val env = min(1f, t / 0.012f) * exp(-t / 0.20)
            val flutter = 0.8 + 0.2 * sin(TAU * 16.0 * t)
            val v = sin(phase) + 0.30 * sin(phase * 3.0)
            out[i] = (v * env * flutter * 0.5).toFloat()
        }
        return out
    }

    private fun renderLand(): FloatArray {
        val out = FloatArray(samples(0.13f))
        val rng = Random(3)
        var phase = 0.0
        for (i in out.indices) {
            val t = i.toFloat() / SAMPLE_RATE
            val freq = max(90.0, 230.0 - 900.0 * t)
            phase += TAU * freq / SAMPLE_RATE
            val env = min(1f, t / 0.002f) * exp(-t / 0.035)
            val thud = sin(phase) * env * 0.8
            val tick = (rng.nextFloat() * 2f - 1f) * exp(-t / 0.01) * 0.15
            out[i] = (thud + tick).toFloat()
        }
        return out
    }

    private fun renderLevelUp(): FloatArray {
        val out = FloatArray(samples(0.95f))
        addBell(out, 0f, 523.25, 0.50f, 0.18f)
        addBell(out, 0.09f, 659.25, 0.50f, 0.18f)
        addBell(out, 0.18f, 783.99, 0.50f, 0.18f)
        addBell(out, 0.27f, 1046.50, 0.55f, 0.26f)
        return out
    }

    private fun renderGameOver(): FloatArray {
        val out = FloatArray(samples(2.0f))
        addDread(out, 0f, 220.00)
        addDread(out, 0.22f, 196.00)
        addDread(out, 0.44f, 174.61)
        addDread(out, 0.66f, 164.81)
        return out
    }

    /** Bell-like voice: a few inharmonic partials under an exponential envelope. */
    private fun addBell(out: FloatArray, startSec: Float, freq: Double, amp: Float, tau: Float) {
        val from = min(out.size, samples(startSec))
        val to = min(out.size, from + samples(tau * 5f).coerceAtLeast(1))
        var phase = 0.0
        for (i in from until to) {
            val t = (i - from).toFloat() / SAMPLE_RATE
            val env = min(1f, t / 0.003f) * exp(-t / tau)
            phase += TAU * freq / SAMPLE_RATE
            val v = sin(phase) + 0.30 * sin(phase * 2.0) + 0.12 * sin(phase * 3.01) + 0.05 * sin(phase * 4.2)
            out[i] += (v * env * amp).toFloat()
        }
    }

    /** Detuned low voice used for the game-over cadence. */
    private fun addDread(out: FloatArray, startSec: Float, freq: Double) {
        val from = min(out.size, samples(startSec))
        val to = min(out.size, from + samples(1.4f))
        var p1 = 0.0
        var p2 = 0.0
        for (i in from until to) {
            val t = (i - from).toFloat() / SAMPLE_RATE
            val env = min(1f, t / 0.02f) * exp(-t / 0.6)
            p1 += TAU * freq / SAMPLE_RATE
            p2 += TAU * (freq * 1.006) / SAMPLE_RATE
            val v = (sin(p1) + sin(p2)) * 0.5 + 0.30 * sin(p1 * 0.5)
            out[i] += (v * env * 0.4).toFloat()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun samples(seconds: Float): Int = (seconds * SAMPLE_RATE).toInt()

    /** Fades the edges, normalises to [peak] and converts to 16-bit PCM. */
    private fun finish(buf: FloatArray, peak: Float): ShortArray {
        fadeEdges(buf, samples(0.006f))
        var maxAbs = 0f
        for (v in buf) maxAbs = max(maxAbs, abs(v))
        val scale = if (maxAbs > 1e-5f) peak / maxAbs else 0f
        val pcm = ShortArray(buf.size)
        for (i in buf.indices) {
            pcm[i] = (buf[i] * scale * MAX_SHORT).toInt().coerceIn(-32768, 32767).toShort()
        }
        return pcm
    }

    /** A few milliseconds of fade stops loop seams and hard cut-offs from clicking. */
    private fun fadeEdges(buf: FloatArray, fade: Int) {
        if (buf.isEmpty() || fade <= 0) return
        val n = min(fade, buf.size / 2)
        for (i in 0 until n) {
            val gain = i.toFloat() / n
            buf[i] *= gain
            buf[buf.size - 1 - i] *= gain
        }
    }
}
