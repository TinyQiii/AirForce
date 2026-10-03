package com.example.airforce

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * 音效在首次启动时用代码合成成 WAV 写入 cacheDir，再交给 SoundPool 播放。
 * 好处：不占 APK 体积、不依赖任何音频资源文件、可实时调整音色。
 */
class SoundManager(private val context: Context) {

    companion object {
        private const val SR = 22050
        const val SHOOT = 0
        const val HIT = 1
        const val EXPLODE = 2
        const val BIG_BOOM = 3
        const val POWER = 4
        const val HURT = 5
        const val WARN = 6
        const val GAME_OVER = 7
        const val CLICK = 8
        const val LEVEL_CLEAR = 9      // 过关
        const val ACHIEVE = 10         // 成就解锁
        const val BUY = 11             // 商店购买
        const val COIN = 12            // 拾取金币
        const val REVIVE = 13          // 复活
        const val LASER = 14           // 激光发射
        private const val COUNT = 15
        private const val CACHE_VER = "v2"
    }

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(24)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val ids = IntArray(COUNT)
    private val ready = BooleanArray(COUNT)
    private var released = false

    var enabled = true

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status != 0) return@setOnLoadCompleteListener
            val idx = ids.indexOf(sampleId)
            if (idx >= 0) ready[idx] = true
        }
        val dir = File(context.cacheDir, "sfx")
        if (!dir.exists()) dir.mkdirs()
        val specs = buildSpecs()
        for (i in 0 until COUNT) {
            val f = File(dir, "${CACHE_VER}_s$i.wav")
            if (!f.exists() || f.length() < 128L) writeWav(f, specs[i])
            ids[i] = pool.load(f.absolutePath, 1)
        }
    }

    fun play(index: Int, volume: Float = 1f, rate: Float = 1f) {
        if (!enabled || released) return
        if (index < 0 || index >= COUNT || !ready[index]) return
        pool.play(ids[index], volume, volume, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    fun pause() {
        if (!released) pool.autoPause()
    }

    fun resume() {
        if (!released) pool.autoResume()
    }

    fun release() {
        if (released) return
        released = true
        pool.release()
    }

    // ---------- 波形合成 ----------

    private var seed = 987654321

    private fun noise(): Float {
        seed = seed * 1103515245 + 12345
        return ((seed shr 16) and 0x7FFF) / 16384f - 1f
    }

    private fun square(x: Float): Float =
        if (sin(x * 2f * PI.toFloat()) >= 0f) 1f else -1f

    private fun saw(x: Float): Float {
        val f = x - floor(x)
        return f * 2f - 1f
    }

    private inline fun tone(dur: Float, gen: (Float, Float) -> Float): ShortArray {
        val n = (SR * dur).toInt().coerceAtLeast(1)
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            val p = i.toFloat() / n
            val v = gen(t, p).coerceIn(-1f, 1f)
            out[i] = (v * 31000f).toInt().toShort()
        }
        return out
    }

    private fun buildSpecs(): Array<ShortArray> {
        val arr = Array(COUNT) { ShortArray(0) }

        arr[SHOOT] = tone(0.07f) { t, p ->
            val f = 1150f - 620f * p
            square(t * f) * (1f - p) * (1f - p) * 0.15f
        }

        arr[HIT] = tone(0.06f) { _, p -> noise() * (1f - p) * 0.20f }

        arr[EXPLODE] = tone(0.38f) { t, p ->
            val n = noise() * exp(-p * 4.5f)
            val low = sin(t * 2f * PI.toFloat() * (175f - 110f * p)) * exp(-p * 3.4f)
            n * 0.30f + low * 0.34f
        }

        arr[BIG_BOOM] = tone(0.72f) { t, p ->
            val n = noise() * exp(-p * 3.0f)
            val low = sin(t * 2f * PI.toFloat() * (125f - 80f * p)) * exp(-p * 2.2f)
            n * 0.30f + low * 0.46f
        }

        arr[POWER] = tone(0.26f) { t, p ->
            val f = if (p < 0.34f) 587f else if (p < 0.67f) 784f else 1046f
            sin(t * 2f * PI.toFloat() * f) * (1f - p * 0.55f) * 0.22f
        }

        arr[HURT] = tone(0.40f) { t, p ->
            val f = 420f - 300f * p
            saw(t * f) * exp(-p * 2.5f) * 0.26f
        }

        arr[WARN] = tone(0.60f) { t, p ->
            val on = p < 0.30f || (p > 0.45f && p < 0.75f)
            if (on) square(t * 233f) * 0.22f else 0f
        }

        arr[GAME_OVER] = tone(1.00f) { t, p ->
            val f = when {
                p < 0.25f -> 523f
                p < 0.50f -> 392f
                p < 0.75f -> 330f
                else -> 247f
            }
            sin(t * 2f * PI.toFloat() * f) * exp(-p * 1.6f) * 0.24f
        }

        arr[CLICK] = tone(0.05f) { t, p ->
            sin(t * 2f * PI.toFloat() * 1400f) * (1f - p) * 0.18f
        }

        // 过关：上行琶音，明亮有成就感
        arr[LEVEL_CLEAR] = tone(0.90f) { t, p ->
            val f = when {
                p < 0.20f -> 523f    // C5
                p < 0.40f -> 659f    // E5
                p < 0.60f -> 784f    // G5
                p < 0.80f -> 1046f   // C6
                else -> 1318f        // E6
            }
            val env = if (p < 0.80f) 1f else 1f - (p - 0.80f) * 5f
            sin(t * 2f * PI.toFloat() * f) * env * 0.22f
        }

        // 成就解锁：清脆的双音提示
        arr[ACHIEVE] = tone(0.45f) { t, p ->
            val f = if (p < 0.45f) 880f else 1174f
            val env = if (p < 0.45f) 1f - p * 0.5f else (1f - p) * 1.8f
            sin(t * 2f * PI.toFloat() * f) * env.coerceIn(0f, 1f) * 0.20f
        }

        // 购买：干脆的"叮"
        arr[BUY] = tone(0.18f) { t, p ->
            val f = 1320f + 300f * p
            sin(t * 2f * PI.toFloat() * f) * exp(-p * 5f) * 0.22f
        }

        // 金币：短促高音
        arr[COIN] = tone(0.09f) { t, p ->
            val f = if (p < 0.5f) 1568f else 2093f
            sin(t * 2f * PI.toFloat() * f) * (1f - p) * 0.16f
        }

        // 复活：上扬的能量感
        arr[REVIVE] = tone(0.60f) { t, p ->
            val f = 260f + 700f * p
            val saw = (t * f - floor(t * f)) * 2f - 1f
            val low = sin(t * 2f * PI.toFloat() * f)
            (saw * 0.12f + low * 0.24f) * exp(-p * 1.4f)
        }

        // 激光：低频嗡鸣（短促，靠高频播放形成持续感）
        arr[LASER] = tone(0.12f) { t, p ->
            val f = 180f + 60f * p
            val sq = if (sin(t * 2f * PI.toFloat() * f) >= 0f) 1f else -1f
            (sq * 0.10f + noise() * 0.06f) * (1f - p * 0.7f)
        }

        return arr
    }

    private fun writeWav(file: File, data: ShortArray) {
        val dataSize = data.size * 2
        val header = ByteArray(44)
        fun putStr(off: Int, s: String) {
            for (k in s.indices) header[off + k] = s[k].toByte()
        }
        fun putInt(off: Int, v: Int) {
            header[off] = (v and 0xFF).toByte()
            header[off + 1] = ((v shr 8) and 0xFF).toByte()
            header[off + 2] = ((v shr 16) and 0xFF).toByte()
            header[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun putShort(off: Int, v: Int) {
            header[off] = (v and 0xFF).toByte()
            header[off + 1] = ((v shr 8) and 0xFF).toByte()
        }
        putStr(0, "RIFF")
        putInt(4, 36 + dataSize)
        putStr(8, "WAVE")
        putStr(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, 1)
        putInt(24, SR)
        putInt(28, SR * 2)
        putShort(32, 2)
        putShort(34, 16)
        putStr(36, "data")
        putInt(40, dataSize)

        val body = ByteArray(dataSize)
        for (i in data.indices) {
            val s = data[i].toInt()
            body[i * 2] = (s and 0xFF).toByte()
            body[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }

        try {
            FileOutputStream(file).use { out ->
                out.write(header)
                out.write(body)
            }
        } catch (ignored: Exception) {
            // 音效写入失败不影响游戏运行
        }
    }
}