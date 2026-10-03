package com.example.airforce

import java.util.Calendar

// ============================================================
//  统计项
// ============================================================
internal const val S_KILLS = 0        // 累计击落
internal const val S_COINS = 1        // 累计获得金币
internal const val S_BOSSES = 2       // 累计击败 BOSS
internal const val S_BOMBS = 3        // 累计使用炸弹
internal const val S_BEST_COMBO = 4   // 最高连击
internal const val S_MAX_WEAPON = 5   // 最高火力等级
internal const val S_LEVELS = 6       // 最远到达关卡
internal const val S_GAMES = 7        // 游戏局数
internal const val S_NOHIT = 8        // 无伤过关次数
internal const val S_BEST_TIME = 9    // 最长存活秒数
internal const val S_COUNT = 10

internal val STAT_NAMES: Array<String> = arrayOf(
    "累计击落", "累计金币", "击败 BOSS", "使用炸弹", "最高连击",
    "最高火力", "最远关卡", "游戏局数", "无伤过关", "最长存活"
)

// ============================================================
//  成就定义
// ============================================================
internal class Achievement(
    val id: Int,
    val name: String,
    val desc: String,
    val stat: Int,          // 关联的统计项
    val target: Int,        // 达成阈值
    val isBest: Boolean,    // true = 取历史最大值，false = 累计求和
    val color: Int
)

internal val ACHIEVEMENTS: Array<Achievement> = arrayOf(
    Achievement(0, "初次出击", "击落第 1 架敌机", S_KILLS, 1, false, 0xFF4FC3F7.toInt()),
    Achievement(1, "百机斩", "累计击落 100 架敌机", S_KILLS, 100, false, 0xFF29B6F6.toInt()),
    Achievement(2, "千机之王", "累计击落 1000 架敌机", S_KILLS, 1000, false, 0xFF0288D1.toInt()),
    Achievement(3, "连击新星", "单局达成 10 连击", S_BEST_COMBO, 10, true, 0xFF69F0AE.toInt()),
    Achievement(4, "连击大师", "单局达成 25 连击", S_BEST_COMBO, 25, true, 0xFF00C853.toInt()),
    Achievement(5, "连击之神", "单局达成 50 连击", S_BEST_COMBO, 50, true, 0xFF1B5E20.toInt()),
    Achievement(6, "弹药充足", "累计使用 20 次炸弹", S_BOMBS, 20, false, 0xFFFFD54F.toInt()),
    Achievement(7, "炸弹狂人", "累计使用 100 次炸弹", S_BOMBS, 100, false, 0xFFFFAB00.toInt()),
    Achievement(8, "BOSS 猎手", "击败 5 个 BOSS", S_BOSSES, 5, false, 0xFFFF5252.toInt()),
    Achievement(9, "BOSS 终结者", "击败 25 个 BOSS", S_BOSSES, 25, false, 0xFFD32F2F.toInt()),
    Achievement(10, "火力全开", "将火力升到 5 级", S_MAX_WEAPON, 5, true, 0xFFBA68C8.toInt()),
    Achievement(11, "初入星海", "首次通关第 1 关", S_LEVELS, 1, true, 0xFF64B5F6.toInt()),
    Achievement(12, "星际老兵", "到达第 5 关", S_LEVELS, 5, true, 0xFF3F51B5.toInt()),
    Achievement(13, "虚空征服者", "通关全部 8 个关卡", S_LEVELS, 8, true, 0xFF7C4DFF.toInt()),
    Achievement(14, "毫发无伤", "不受伤通过任意一关", S_NOHIT, 1, false, 0xFF80CBC4.toInt()),
    Achievement(15, "铁壁之身", "累计无伤过关 5 次", S_NOHIT, 5, false, 0xFF00897B.toInt()),
    Achievement(16, "财富积累", "累计获得 5000 金币", S_COINS, 5000, false, 0xFFFFB300.toInt()),
    Achievement(17, "富可敌国", "累计获得 30000 金币", S_COINS, 30000, false, 0xFFF57F17.toInt()),
    Achievement(18, "生存专家", "单局存活 180 秒", S_BEST_TIME, 180, true, 0xFFFF8A65.toInt()),
    Achievement(19, "百战之身", "游玩 50 局", S_GAMES, 50, false, 0xFF90A4AE.toInt())
)

internal fun achievementAt(i: Int): Achievement = ACHIEVEMENTS[i]

// ============================================================
//  成就状态
// ============================================================
internal class AchievementState {

    val stats = IntArray(S_COUNT)
    val unlocked = BooleanArray(ACHIEVEMENTS.size)

    /** 本局新增解锁的成就（用于弹出提示） */
    val pending = ArrayList<Achievement>(4)

    /** 更新一项统计，返回本次是否触发新成就 */
    fun add(stat: Int, value: Int): Boolean {
        if (stat !in stats.indices) return false
        if (value <= 0) return false
        val before = stats[stat]
        stats[stat] += value
        return checkAll(before, stats[stat], stat)
    }

    /** 更新一项"取最大值"的统计 */
    fun max(stat: Int, value: Int): Boolean {
        if (stat !in stats.indices) return false
        if (value <= stats[stat]) return false
        val before = stats[stat]
        stats[stat] = value
        return checkAll(before, stats[stat], stat)
    }

    /** 直接设置（用于"最远关卡"这类） */
    fun set(stat: Int, value: Int): Boolean {
        if (stat !in stats.indices) return false
        val before = stats[stat]
        stats[stat] = value
        return checkAll(before, stats[stat], stat)
    }

    private fun checkAll(before: Int, now: Int, stat: Int): Boolean {
        var any = false
        for (a in ACHIEVEMENTS) {
            if (a.stat != stat) continue
            if (unlocked[a.id]) continue
            val reached = if (a.isBest) now >= a.target else now >= a.target
            if (reached && before < a.target) {
                unlocked[a.id] = true
                pending.add(a)
                any = true
            }
        }
        return any
    }

    fun unlockedCount(): Int = unlocked.count { it }

    /** 成就进度：返回 0f..1f */
    fun progress(a: Achievement): Float {
        val v = if (a.stat in stats.indices) stats[a.stat] else 0
        return (v.toFloat() / a.target.toFloat()).coerceIn(0f, 1f)
    }

    fun save(prefs: android.content.SharedPreferences) {
        val e = prefs.edit()
        for (i in 0 until S_COUNT) e.putInt("s$i", stats[i])
        for (i in unlocked.indices) e.putBoolean("a$i", unlocked[i])
        e.apply()
    }

    fun load(prefs: android.content.SharedPreferences) {
        for (i in 0 until S_COUNT) stats[i] = prefs.getInt("s$i", 0)
        for (i in unlocked.indices) unlocked[i] = prefs.getBoolean("a$i", false)
    }
}

// ============================================================
//  每日挑战
// ============================================================

/**
 * 每日挑战：由当天日期决定参数，全世界的玩家当天挑战内容一致。
 * 用日期做种子，保证同一天多次进入参数相同、第二天自动变化。
 */
internal class DailyChallenge(val dateKey: Int) {

    val index: Int
    val title: String
    val desc: String
    val enemyRate: Float      // 刷怪频率倍率
    val enemyHp: Float        // 敌人血量倍率
    val coinMul: Float        // 金币倍率
    val startLives: Int       // 初始生命
    val color: Int

    init {
        // 用日期生成伪随机，保证当天固定
        var seed = dateKey
        seed = seed * 1103515245 + 12345
        val r = ((seed shr 16) and 0x7FFF) % 5
        index = r
        when (r) {
            0 -> {
                title = "狂暴日"
                desc = "敌人血量 ×1.8 · 金币 ×2"
                enemyRate = 1f; enemyHp = 1.8f; coinMul = 2f; startLives = 3
                color = 0xFFFF5252.toInt()
            }
            1 -> {
                title = "蜂群日"
                desc = "刷怪速度 ×1.7 · 金币 ×1.8"
                enemyRate = 1.7f; enemyHp = 1f; coinMul = 1.8f; startLives = 3
                color = 0xFFFFD54F.toInt()
            }
            2 -> {
                title = "一命日"
                desc = "只有 1 条命 · 金币 ×3"
                enemyRate = 1f; enemyHp = 1f; coinMul = 3f; startLives = 1
                color = 0xFFE040FB.toInt()
            }
            3 -> {
                title = "精锐日"
                desc = "敌人血量 ×2.2 · 金币 ×2.5"
                enemyRate = 1f; enemyHp = 2.2f; coinMul = 2.5f; startLives = 3
                color = 0xFF69F0AE.toInt()
            }
            else -> {
                title = "富裕日"
                desc = "金币 ×4 · 敌人不变"
                enemyRate = 1f; enemyHp = 1f; coinMul = 4f; startLives = 3
                color = 0xFFFFB300.toInt()
            }
        }
    }
}

internal fun todayKey(): Int {
    val c = Calendar.getInstance()
    return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
}

internal fun dailyChallenge(): DailyChallenge = DailyChallenge(todayKey())
