package com.example.airforce

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 把所有会重复绘制的东西（飞机、子弹、道具、星星、星云）预先烘焙成 Bitmap。
 * 游戏中每帧只做 drawBitmap，不再重建 Path、不再做描边计算。
 *
 * 所有造型都是代码画出来的（矢量），项目里没有任何图片资源。
 */
internal fun planePath(path: Path, cx: Float, cy: Float, s: Float) {
    path.reset()
    path.moveTo(cx, cy - s * 1.45f)
    path.lineTo(cx + s * 0.30f, cy + s * 0.15f)
    path.lineTo(cx + s * 1.45f, cy + s * 0.85f)
    path.lineTo(cx + s * 0.34f, cy + s * 0.72f)
    path.lineTo(cx + s * 0.52f, cy + s * 1.30f)
    path.lineTo(cx, cy + s * 1.02f)
    path.lineTo(cx - s * 0.52f, cy + s * 1.30f)
    path.lineTo(cx - s * 0.34f, cy + s * 0.72f)
    path.lineTo(cx - s * 1.45f, cy + s * 0.85f)
    path.lineTo(cx - s * 0.30f, cy + s * 0.15f)
    path.close()
}

/** 正多边形（用于分裂机 / 护盾机） */
internal fun polygonPath(path: Path, cx: Float, cy: Float, s: Float, sides: Int, startDeg: Float) {
    path.reset()
    val start = Math.toRadians(startDeg.toDouble())
    for (i in 0 until sides) {
        val a = start + i * 2.0 * Math.PI / sides
        val x = cx + cos(a).toFloat() * s * 1.35f
        val y = cy + sin(a).toFloat() * s * 1.35f
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
}

/** 细长箭头（狙击机） */
internal fun needlePath(path: Path, cx: Float, cy: Float, s: Float) {
    path.reset()
    path.moveTo(cx, cy - s * 1.75f)
    path.lineTo(cx + s * 0.40f, cy + s * 0.20f)
    path.lineTo(cx + s * 1.00f, cy + s * 1.25f)
    path.lineTo(cx + s * 0.28f, cy + s * 0.95f)
    path.lineTo(cx, cy + s * 1.40f)
    path.lineTo(cx - s * 0.28f, cy + s * 0.95f)
    path.lineTo(cx - s * 1.00f, cy + s * 1.25f)
    path.lineTo(cx - s * 0.40f, cy + s * 0.20f)
    path.close()
}

/** 尖锐飞镖（自爆机） */
internal fun dartPath(path: Path, cx: Float, cy: Float, s: Float) {
    path.reset()
    path.moveTo(cx, cy - s * 1.55f)
    path.lineTo(cx + s * 1.25f, cy + s * 1.15f)
    path.lineTo(cx + s * 0.26f, cy + s * 0.55f)
    path.lineTo(cx, cy + s * 1.30f)
    path.lineTo(cx - s * 0.26f, cy + s * 0.55f)
    path.lineTo(cx - s * 1.25f, cy + s * 1.15f)
    path.close()
}

/** 四角星（瞬移机 / 新星 BOSS） */
internal fun starPath(path: Path, cx: Float, cy: Float, s: Float) {
    path.reset()
    val points = 4
    for (i in 0 until points * 2) {
        val r = if (i % 2 == 0) s * 1.62f else s * 0.60f
        val a = Math.toRadians(-90.0 + i * 180.0 / points)
        val x = cx + cos(a).toFloat() * r
        val y = cy + sin(a).toFloat() * r
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
}

class Sprites(density: Float) {

    private val d = density
    private fun dp(v: Float) = v * d

    val planeBase = dp(16f)
    val bulletBase = dp(6f)
    val powerBase = dp(22f)

    lateinit var player: Bitmap
    val enemies = arrayOfNulls<Bitmap>(K_COUNT)
    val bossSprites = arrayOfNulls<Bitmap>(BOSS_SPECS.size)
    lateinit var bulletFriendly: Bitmap
    lateinit var bulletEnemy: Bitmap
    lateinit var bulletMissile: Bitmap
    lateinit var bulletLaser: Bitmap
    val powerups = arrayOfNulls<Bitmap>(P_COUNT)
    lateinit var starBmp: Bitmap
    lateinit var nebulaA: Bitmap
    lateinit var nebulaB: Bitmap
    lateinit var nebulaC: Bitmap
    lateinit var shieldRing: Bitmap

    private val owned = ArrayList<Bitmap>(28)

    fun build() {
        player = plane(0xFFE3F2FD.toInt(), 0xFF29B6F6.toInt(), false)

        // ---- 敌机 ----
        enemies[K_SCOUT] = plane(0xFFE57373.toInt(), 0xFFFFCDD2.toInt(), true)
        enemies[K_ZIGZAG] = plane(0xFFBA68C8.toInt(), 0xFFE1BEE7.toInt(), true)
        enemies[K_TANK] = plane(0xFFFF8A65.toInt(), 0xFFFFE0B2.toInt(), true)
        enemies[K_DIVER] = plane(0xFFFFD54F.toInt(), 0xFFFFF9C4.toInt(), true)
        enemies[K_ELITE] = plane(0xFF64B5F6.toInt(), 0xFFBBDEFB.toInt(), true)
        enemies[K_ORBITER] = orbiter(0xFF26A69A.toInt(), 0xFFB2DFDB.toInt())
        enemies[K_SPLITTER] = polygon(0xFF9CCC65.toInt(), 0xFFDCEDC8.toInt(), 6, -90f)
        enemies[K_SHIELDED] = polygon(0xFF78909C.toInt(), 0xFFCFD8DC.toInt(), 8, -112.5f)
        enemies[K_SNIPER] = shaped(0xFF7E57C2.toInt(), 0xFFD1C4E9.toInt(), true, true, ::needlePath)
        enemies[K_KAMIKAZE] = shaped(0xFFFF7043.toInt(), 0xFFFFCCBC.toInt(), true, true, ::dartPath)
        enemies[K_BOMBER] = polygon(0xFFEF6C00.toInt(), 0xFFFFE0B2.toInt(), 6, -90f)
        enemies[K_WARP] = shaped(0xFF7E57C2.toInt(), 0xFFD1C4E9.toInt(), false, true, ::starPath)
        enemies[K_BOSS] = plane(0xFFD32F2F.toInt(), 0xFFFFCDD2.toInt(), true)

        // ---- BOSS 变体 ----
        for (i in BOSS_SPECS.indices) {
            val b = BOSS_SPECS[i]
            bossSprites[i] = when (b.type) {
                BOSS_OMEGA -> polygon(b.bodyColor, b.accentColor, 8, -112.5f)
                BOSS_NOVA -> shaped(b.bodyColor, b.accentColor, false, true, ::starPath)
                else -> plane(b.bodyColor, b.accentColor, true)
            }
        }

        // ---- 子弹 ----
        bulletFriendly = bullet(0xFFFFF176.toInt(), 0xFFFFFFFF.toInt())
        bulletEnemy = bullet(0xFFFF5252.toInt(), 0xFFFFCDD2.toInt())
        bulletMissile = bullet(0xFF69F0AE.toInt(), 0xFFB9F6CA.toInt())
        bulletLaser = bullet(0xFFE040FB.toInt(), 0xFFFFD6FF.toInt())

        // ---- 道具 ----
        powerups[P_WEAPON] = power(0xFF4FC3F7.toInt(), "P")
        powerups[P_BOMB] = power(0xFFFFD54F.toInt(), "B")
        powerups[P_LIFE] = power(0xFFFF8A80.toInt(), "+")
        powerups[P_SHIELD] = power(0xFF69F0AE.toInt(), "S")
        powerups[P_COIN] = power(0xFFFFB300.toInt(), "$")
        powerups[P_MAGNET] = power(0xFFBA68C8.toInt(), "M")
        powerups[P_SLOW] = power(0xFF4DD0E1.toInt(), "T")

        starBmp = starSprite()
        nebulaA = nebula(0x553B1E6E, 0x2A1B2A5E)
        nebulaB = nebula(0x55204E6E, 0x2212384E)
        nebulaC = nebula(0x554A1030, 0x221F0820)
        shieldRing = ringSprite()
    }

    private fun own(b: Bitmap): Bitmap {
        owned.add(b)
        return b
    }

    private fun plane(body: Int, accent: Int, flip: Boolean): Bitmap =
        shaped(body, accent, flip, true, ::planePath)

    /** 用任意路径生成一个造型 */
    private fun shaped(
        body: Int, accent: Int, flip: Boolean,
        cockpit: Boolean,
        gen: (Path, Float, Float, Float) -> Unit
    ): Bitmap {
        val s = planeBase
        val n = ceil(s * 3.8f).toInt()
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = n / 2f
        val cy = n / 2f
        if (flip) c.scale(1f, -1f, cx, cy)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path()
        gen(path, cx, cy, s)
        p.style = Paint.Style.FILL
        p.color = body
        c.drawPath(path, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(1.5f, s * 0.13f)
        p.color = accent
        c.drawPath(path, p)
        if (cockpit) {
            p.style = Paint.Style.FILL
            p.color = accent
            c.drawCircle(cx, cy - s * 0.38f, s * 0.24f, p)
        }
        return own(bmp)
    }

    /** 正多边形敌机（分裂机 / 护盾机） */
    private fun polygon(body: Int, accent: Int, sides: Int, startDeg: Float): Bitmap =
        shaped(body, accent, false, false) { path, cx, cy, s ->
            polygonPath(path, cx, cy, s, sides, startDeg)
        }

    /** 绕圈机：中心球 + 外环 */
    private fun orbiter(body: Int, accent: Int): Bitmap {
        val s = planeBase
        val n = ceil(s * 3.8f).toInt()
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = n / 2f
        val cy = n / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.FILL
        p.color = body
        c.drawCircle(cx, cy, s * 0.72f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(1.5f, s * 0.17f)
        p.color = accent
        c.drawCircle(cx, cy, s * 1.18f, p)
        p.style = Paint.Style.FILL
        c.drawCircle(cx, cy, s * 0.30f, p)
        return own(bmp)
    }

    private fun bullet(core: Int, inner: Int): Bitmap {
        val r = bulletBase
        val n = ceil(r * 5f).toInt()
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = n / 2f
        val cy = n / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.FILL
        p.color = (core and 0x00FFFFFF) or 0x55000000
        c.drawCircle(cx, cy, r * 2.2f, p)
        p.color = core
        c.drawCircle(cx, cy, r, p)
        p.color = inner
        c.drawCircle(cx, cy, r * 0.45f, p)
        return own(bmp)
    }

    private fun power(col: Int, label: String): Bitmap {
        val r = powerBase
        val n = ceil(r * 2.2f).toInt()
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = n / 2f
        val cy = n / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.FILL
        p.color = (col and 0x00FFFFFF) or 0x55000000
        c.drawCircle(cx, cy, r, p)
        p.color = col
        c.drawCircle(cx, cy, r * 0.68f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(2.5f)
        p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(cx, cy, r * 0.68f, p)
        p.style = Paint.Style.FILL
        p.color = 0xFF0A1030.toInt()
        p.textAlign = Paint.Align.CENTER
        p.textSize = dp(17f)
        p.typeface = Typeface.DEFAULT_BOLD
        val fm = p.fontMetrics
        c.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2f, p)
        return own(bmp)
    }

    private fun starSprite(): Bitmap {
        val n = max(4, ceil(dp(9f)).toInt())
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = n / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(
            cx, cx, cx,
            intArrayOf(0xFFFFFFFF.toInt(), 0x88FFFFFF.toInt(), 0x00FFFFFF),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cx, cx, p)
        return own(bmp)
    }

    private fun nebula(inner: Int, outer: Int): Bitmap {
        val n = 256
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = n / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(
            cx, cx, cx,
            intArrayOf(inner, outer, 0x00000000),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cx, cx, p)
        return own(bmp)
    }

    /** 护盾光环（中空的环） */
    private fun ringSprite(): Bitmap {
        val n = 128
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = n / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(
            cx, cx, cx,
            intArrayOf(0x00FFFFFF, 0xAAFFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.72f, 0.88f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cx, cx, p)
        return own(bmp)
    }

    fun release() {
        for (b in owned) if (!b.isRecycled) b.recycle()
        owned.clear()
    }
}
