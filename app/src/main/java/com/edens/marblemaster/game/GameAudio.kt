package com.edens.marblemaster.game

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Plays [AudioSynth]'s procedural effects and looping music.
 *
 * Each effect gets its own static [AudioTrack], so different sounds mix naturally and a
 * repeated effect simply restarts. Music is a static track with an infinite loop point,
 * generated once on a background thread so the first frame is never blocked by synthesis.
 */
class GameAudio {

    private class Sfx(val track: AudioTrack) {
        fun play(volume: Float) {
            try {
                track.stop()
                track.reloadStaticData()
                track.setVolume(volume)
                track.play()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "audio effect could not be played", e)
            }
        }
    }

    private val effectAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val musicAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val effects = HashMap<SoundEffect, Sfx>()

    @Volatile
    private var music: AudioTrack? = null

    @Volatile
    private var musicWanted = false

    @Volatile
    private var musicLoading = false

    @Volatile
    private var released = false

    private var lastLaunchNanos = 0L
    private var lastLandNanos = 0L

    /** Starts (or resumes) the music loop. Safe to call repeatedly. */
    fun startMusic() {
        if (released) return
        musicWanted = true
        music?.let { track ->
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) playSafely(track)
            return
        }
        if (musicLoading) return
        musicLoading = true
        Thread({
            val loop = AudioSynth.musicLoop()
            val built = try {
                makeTrack(loop, musicAttributes, MUSIC_VOLUME)
            } catch (e: Exception) {
                Log.w(TAG, "music track could not be created", e)
                null
            }
            synchronized(this) {
                musicLoading = false
                if (built == null) return@synchronized
                if (released) {
                    built.release()
                    return@synchronized
                }
                built.setLoopPoints(0, loop.size, -1)
                music = built
                if (musicWanted) playSafely(built)
            }
        }, "swing-music").start()
    }

    /** Silences the music but leaves one-shot effects running (used on game over). */
    fun pauseMusic() {
        musicWanted = false
        music?.let { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.pause() }
    }

    /** Pauses everything; [startMusic] resumes the loop on the next activation. */
    fun pause() {
        pauseMusic()
        for (sfx in effects.values) {
            if (sfx.track.playState == AudioTrack.PLAYSTATE_PLAYING) sfx.track.pause()
        }
    }

    fun play(effect: SoundEffect) {
        if (released) return
        toGameOverIfNeeded(effect)
        if (isThrottled(effect)) return
        val volume = volumeFor(effect)
        val existing = effects[effect]
        if (existing != null) {
            existing.play(volume)
            return
        }
        val track = try {
            makeTrack(AudioSynth.effect(effect), effectAttributes, volume)
        } catch (e: Exception) {
            Log.w(TAG, "audio track could not be created for $effect", e)
            return
        }
        val sfx = Sfx(track)
        effects[effect] = sfx
        sfx.play(volume)
    }

    fun release() {
        synchronized(this) {
            if (released) return
            released = true
            musicWanted = false
            music?.release()
            music = null
            for (sfx in effects.values) sfx.track.release()
            effects.clear()
        }
    }

    // ------------------------------------------------------------------ internal

    /** Several marbles can leave a pan or land in the same frame; one sound is enough. */
    private fun isThrottled(effect: SoundEffect): Boolean {
        val now = System.nanoTime()
        when (effect) {
            SoundEffect.LAUNCH -> {
                if (now - lastLaunchNanos < LAUNCH_THROTTLE_NS) return true
                lastLaunchNanos = now
            }
            SoundEffect.LAND -> {
                if (now - lastLandNanos < LAND_THROTTLE_NS) return true
                lastLandNanos = now
            }
            else -> Unit
        }
        return false
    }

    private fun playSafely(track: AudioTrack) {
        try {
            track.play()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "audio track could not start", e)
        }
    }

    private fun makeTrack(samples: ShortArray, attributes: AudioAttributes, volume: Float): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AudioSynth.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
        val written = track.write(samples, 0, samples.size)
        if (written != samples.size) {
            Log.w(TAG, "audio buffer only partially written: $written/${samples.size}")
        }
        track.setVolume(volume)
        return track
    }

    private fun volumeFor(effect: SoundEffect): Float = when (effect) {
        SoundEffect.DROP -> 0.45f
        SoundEffect.MATCH -> 0.70f
        SoundEffect.MATCH_CHAIN -> 0.75f
        SoundEffect.BLAST -> 0.85f
        SoundEffect.LAUNCH -> 0.65f
        SoundEffect.LAND -> 0.40f
        SoundEffect.LEVEL_UP -> 0.80f
        SoundEffect.GAME_OVER -> 0.70f
    }

    private fun toGameOverIfNeeded(effect: SoundEffect) {
        if (effect == SoundEffect.GAME_OVER) pauseMusic()
    }

    private companion object {
        const val TAG = "GameAudio"
        const val MUSIC_VOLUME = 0.28f
        const val LAUNCH_THROTTLE_NS = 120_000_000L
        const val LAND_THROTTLE_NS = 90_000_000L
    }
}
