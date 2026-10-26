package com.edens.marblemaster.game

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Plays [AudioSynth]'s procedural effects and looping music.
 *
 * Each effect gets its own static [AudioTrack], so different sounds mix naturally and a
 * repeated effect simply restarts. Music is a static track with an infinite loop point,
 * generated once on a background thread so the first frame is never blocked by synthesis.
 *
 * The game holds audio focus while its soundtrack is playing: a notification or another app
 * that needs the speakers briefly pauses the loop, and the loop is resumed as soon as that
 * interruption is over. A short watchdog also restarts the loop on devices that stop the
 * track without ever reporting the focus change.
 */
class GameAudio(context: Context) {

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

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val effectAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val musicAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val handler = Handler(Looper.getMainLooper())

    private val effects = HashMap<SoundEffect, Sfx>()

    @Volatile
    private var music: AudioTrack? = null

    @Volatile
    private var musicWanted = false

    @Volatile
    private var musicLoading = false

    @Volatile
    private var released = false

    /** Player setting: the soundtrack is silenced while true. */
    @Volatile
    private var musicMuted = false

    /** Player setting: one-shot effects are silenced while true. */
    @Volatile
    private var effectsMuted = false

    /** True while this app holds audio focus, so the platform reports interruptions. */
    @Volatile
    private var hasFocus = false

    /** The music was paused by an interruption and must restart once focus comes back. */
    @Volatile
    private var resumeAfterFocusGain = false

    /** The music is playing quietly under a shorter interruption instead of stopping. */
    @Volatile
    private var ducked = false

    @Volatile
    private var watchdogPending = false

    private val focusListener =
        AudioManager.OnAudioFocusChangeListener { change -> onFocusChange(change) }

    private val focusRequest: AudioFocusRequest? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(musicAttributes)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
        } else {
            null
        }

    private var lastLaunchNanos = 0L
    private var lastLandNanos = 0L

    /**
     * Some devices silence a track when a notification tone plays and never deliver a focus
     * change, leaving the soundtrack dead. This cheap poll notices that and restarts the loop.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            watchdogPending = false
            if (released || !musicWanted) return
            ensureWatchdog()
            if (resumeAfterFocusGain) return // waiting for the interrupting app to finish
            if (!hasFocus && !requestFocus()) return
            val track = music ?: return
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) playSafely(track)
        }
    }

    /** Starts (or resumes) the music loop. Safe to call repeatedly. */
    fun startMusic() {
        if (released || musicMuted) return
        musicWanted = true
        resumeAfterFocusGain = false
        ensureWatchdog()
        if (!hasFocus && !requestFocus()) return
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
                if (musicWanted && hasFocus) playSafely(built)
            }
        }, "swing-music").start()
    }

    /** Silences the music but leaves one-shot effects running (used on game over). */
    fun pauseMusic() {
        musicWanted = false
        resumeAfterFocusGain = false
        pauseMusicTrack()
    }

    /** Pauses everything; [startMusic] resumes the loop on the next activation. */
    fun pause() {
        abandonFocus()
        pauseMusic()
        pauseEffectTracks()
    }

    /** Player setting: turns the looping soundtrack on or off. */
    fun setMusicEnabled(enabled: Boolean) {
        musicMuted = !enabled
        if (enabled) startMusic() else pauseMusic()
    }

    /** Player setting: turns the one-shot effects on or off. */
    fun setEffectsEnabled(enabled: Boolean) {
        effectsMuted = !enabled
    }

    fun play(effect: SoundEffect) {
        if (released) return
        toGameOverIfNeeded(effect)
        if (effectsMuted) return
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
            handler.removeCallbacks(watchdog)
            watchdogPending = false
            abandonFocus()
            music?.release()
            music = null
            for (sfx in effects.values) sfx.track.release()
            effects.clear()
        }
    }

    // ------------------------------------------------------------------ audio focus

    /**
     * Notification sounds, calls and other apps all announce themselves through audio focus.
     * Holding it lets the game be a good citizen: duck or pause for the interruption and pick
     * the loop back up when the speakers are free again.
     */
    private fun onFocusChange(change: Int) {
        if (released) return
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                if (ducked) {
                    ducked = false
                    applyMusicVolume()
                }
                if (resumeAfterFocusGain) {
                    resumeAfterFocusGain = false
                    if (musicWanted) music?.let { playSafely(it) }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                ducked = true
                applyMusicVolume()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasFocus = false
                resumeAfterFocusGain = musicWanted
                pauseMusicTrack()
                pauseEffectTracks()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // another app is playing for good: stay quiet until the game is opened again,
                // but keep the loop ready in case it hands the speakers straight back
                hasFocus = false
                resumeAfterFocusGain = musicWanted
                pauseMusicTrack()
                pauseEffectTracks()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun requestFocus(): Boolean {
        if (hasFocus) return true
        val manager = audioManager
        if (manager == null) {
            hasFocus = true
            return true
        }
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = focusRequest ?: return false
            manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            manager.requestAudioFocus(
                focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        hasFocus = granted
        return granted
    }

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        val manager = audioManager ?: return
        if (!hasFocus) return
        hasFocus = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        } else {
            manager.abandonAudioFocus(focusListener)
        }
    }

    // ------------------------------------------------------------------ internal

    private fun ensureWatchdog() {
        if (watchdogPending) return
        watchdogPending = true
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    private fun pauseMusicTrack() {
        music?.let { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.pause() }
    }

    private fun pauseEffectTracks() {
        for (sfx in effects.values) {
            if (sfx.track.playState == AudioTrack.PLAYSTATE_PLAYING) sfx.track.pause()
        }
    }

    private fun applyMusicVolume() {
        music?.let { it.setVolume(if (ducked) MUSIC_VOLUME * DUCK_VOLUME else MUSIC_VOLUME) }
    }

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
        const val DUCK_VOLUME = 0.2f
        const val WATCHDOG_INTERVAL_MS = 500L
        const val LAUNCH_THROTTLE_NS = 120_000_000L
        const val LAND_THROTTLE_NS = 90_000_000L
    }
}
