package com.example.airforce

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri

/**
 * 背景音乐播放器。
 *
 * 曲目以 WAV 存放在 res/raw 下（WAV 在 aapt 的默认"不压缩"名单里，
 * 所以 MediaPlayer 能直接流式播放，且体积计入 APK）。
 *
 * 用 prepareAsync() 异步准备，避免主线程卡顿。
 */
class MusicManager(private val context: Context) {

    companion object {
        const val MENU = 0
        const val BATTLE = 1
        const val BOSS = 2
        const val VICTORY = 3
        const val GAMEOVER = 4

        private val RES = intArrayOf(
            R.raw.bgm_menu,
            R.raw.bgm_battle,
            R.raw.bgm_boss,
            R.raw.bgm_victory,
            R.raw.bgm_gameover
        )
    }

    var enabled = true
        set(v) {
            if (field == v) return
            field = v
            refresh()
        }

    var volume = 0.55f
        set(v) {
            field = v.coerceIn(0f, 1f)
            runCatching { player?.setVolume(field, field) }
        }

    private var player: MediaPlayer? = null
    private var current = -1
    private var playing = -1
    private var released = false
    private var wasPlayingBeforePause = false

    /** 播放指定曲目（若已在播放同一首则不打断） */
    fun play(track: Int) {
        if (released) return
        if (track !in RES.indices) return
        current = track
        if (!enabled) return
        // 注意：不能判断 isPlaying —— 异步准备期间它是 false，会导致每帧重启。
        // 只要 playing 已记录为该曲目（播放中或准备中），就直接返回。
        if (playing == track) return
        startInternal(track)
    }

    /** 重新播放当前曲目（用于从暂停恢复、或切换开关后） */
    fun refresh() {
        if (released) return
        if (!enabled) {
            stopInternal()
            return
        }
        if (current >= 0) startInternal(current)
    }

    private fun startInternal(track: Int) {
        stopInternal()
        val mp = try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(
                    context,
                    Uri.parse("android.resource://${context.packageName}/${RES[track]}")
                )
                isLooping = true
                setVolume(volume, volume)
                setOnPreparedListener { p ->
                    if (released || !enabled || current != track) {
                        runCatching { p.release() }
                        return@setOnPreparedListener
                    }
                    runCatching { p.start() }
                    player = p
                    playing = track
                }
                setOnErrorListener { _, _, _ -> true }
                prepareAsync()
            }
        } catch (e: Exception) {
            null
        }
        player = mp
        playing = track
    }

    private fun stopInternal() {
        player?.let { p ->
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
        player = null
        playing = -1
    }

    fun onPause() {
        val p = player ?: return
        wasPlayingBeforePause = runCatching { p.isPlaying }.getOrDefault(false)
        if (wasPlayingBeforePause) runCatching { p.pause() }
    }

    fun onResume() {
        if (!wasPlayingBeforePause) return
        wasPlayingBeforePause = false
        runCatching { player?.start() }
    }

    fun release() {
        if (released) return
        released = true
        stopInternal()
    }
}
