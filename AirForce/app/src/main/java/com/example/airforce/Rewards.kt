package com.example.airforce

import java.util.Calendar

/**
 * 奖励系统（四大模块）
 *
 *   1. 关卡三星      —— 每关按表现评 0~3 星，总星数解锁里程碑奖励
 *   2. 7 天连续签到  —— 每天领一次，断签则从头开始，第 7 天送钥匙
 *   3. 每日任务+活跃度—— 每天随机 3 个任务，完成给活跃度，活跃度分档领奖
 *   4. 开箱抽奖      —— 花金币或钥匙开箱，带 10 次保底
 *
 * 所有数据都跟账号存档走（save_<id>.xml），键名统一用 r_ 前缀，
 * 避免和已有的进度键（s / a / u / ship / wg / eq 系列）冲突。
 */

// ============================================================
//  通用：日期
// ============================================================

// 注意：todayKey() 定义在 Achievements.kt 里，这里直接复用，不要重复定义。

/** 求某个日期键的前一天（跨月跨年都正确） */
internal fun dayKeyBefore(key: Int): Int {
    if (key <= 0) return 0
    val y = key / 10000
    val m = (key / 100) % 100
    val d = key % 100
    val c = Calendar.getInstance()
    c.clear()
    c.set(y, (m - 1).coerceIn(0, 11), d.coerceIn(1, 28), 12, 0, 0)
    c.add(Calendar.DAY_OF_MONTH, -1)
    return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
}

// ============================================================
//  一、关卡三星
// ============================================================

/** 第 3 颗星要求的本关得分（关卡越靠后要求越高） */
internal fun starScoreTarget(levelIndex: Int): Int = 400 + levelIndex * 220

/**
 * 三星规则：
 *   ★     通关即得
 *   ★★   本关全程未受伤
 *   ★★★ 本关得分达到目标分
 */
internal fun evaluateStars(noDamage: Boolean, levelScore: Int, levelIndex: Int): Int {
    var s = 1
    if (noDamage) s++
    if (levelScore >= starScoreTarget(levelIndex)) s++
    return s
}

internal const val MAX_STARS = 36      // 12 关 × 3 星

internal class LevelStarState {
    val stars = IntArray(LEVELS.size)

    fun total(): Int {
        var t = 0
        for (v in stars) t += v
        return t
    }

    /** 只在成绩更好时覆盖，返回 true 表示刷新了纪录 */
    fun record(levelIndex: Int, s: Int): Boolean {
        if (levelIndex !in stars.indices) return false
        if (s <= stars[levelIndex]) return false
        stars[levelIndex] = s
        return true
    }

    fun save(prefs: android.content.SharedPreferences) {
        val e = prefs.edit()
        for (i in stars.indices) e.putInt("r_star$i", stars[i])
        e.apply()
    }

    fun load(prefs: android.content.SharedPreferences) {
        for (i in stars.indices) stars[i] = prefs.getInt("r_star$i", 0).coerceIn(0, 3)
    }
}

// ---------------- 星级里程碑 ----------------

internal class StarMilestone(
    val need: Int,        // 需要的总星数
    val coins: Int,       // 奖励金币
    val keys: Int,        // 奖励钥匙
    val shipId: Int,      // 奖励战机（-1 = 无）
    val wingId: Int,      // 奖励僚机（-1 = 无）
    val eqId: Int,        // 奖励装备（-1 = 无）
    val label: String     // 展示文案
)

internal val STAR_MILESTONES: Array<StarMilestone> = arrayOf(
    StarMilestone(6, 600, 0, -1, -1, -1, "600 金币"),
    StarMilestone(12, 0, 0, -1, WG_TWIN, -1, "僚机 · 双翼僚机"),
    StarMilestone(18, 0, 0, SHIP_SWIFT, -1, -1, "战机 · 疾风号"),
    StarMilestone(24, 0, 0, -1, -1, EQ_GREED, "装备 · 贪婪装置"),
    StarMilestone(30, 1500, 1, -1, -1, -1, "1500 金币 + 钥匙 ×1"),
    StarMilestone(36, 3000, 2, SHIP_PHANTOM, -1, -1, "战机 · 幻影号 + 钥匙 ×2")
)

// ============================================================
//  二、7 天连续签到
// ============================================================

internal const val CHECKIN_DAYS = 7

internal class CheckinReward(val coins: Int, val keys: Int, val short: String)

internal val CHECKIN_REWARDS: Array<CheckinReward> = arrayOf(
    CheckinReward(120, 0, "120"),
    CheckinReward(180, 0, "180"),
    CheckinReward(260, 0, "260"),
    CheckinReward(350, 0, "350"),
    CheckinReward(450, 0, "450"),
    CheckinReward(600, 0, "600"),
    CheckinReward(800, 1, "800钥")
)

internal class CheckinState {

    /** 下一个要发出的奖励下标（0..6） */
    var nextIdx = 0
    /** 本轮 7 天周期里已领取的天数（0..7） */
    var filled = 0
    /** 上次签到的日期键 */
    var lastKey = 0
    /** 历史累计签到天数 */
    var totalDays = 0

    fun claimedToday(today: Int): Boolean = lastKey == today

    /** 断签判定：从没签过不算断；昨天或今天签过就不算断 */
    fun isBroken(today: Int): Boolean =
        lastKey != 0 && lastKey != today && lastKey != dayKeyBefore(today)

    /** 展示用：今天还能领第几天的奖励（-1 = 今天已领完） */
    fun pendingIndex(today: Int): Int {
        if (lastKey == today) return -1
        return if (lastKey == dayKeyBefore(today)) nextIdx else 0
    }

    /** 展示用：本轮已经点亮几个格子 */
    fun filledCount(today: Int): Int {
        if (lastKey == today) return filled
        if (lastKey == dayKeyBefore(today)) return filled
        return 0
    }

    /** 领取今日签到；成功返回奖励下标 0..6，今天已领过返回 -1 */
    fun claim(today: Int): Int {
        if (lastKey == today) return -1
        if (lastKey != dayKeyBefore(today)) {
            nextIdx = 0      // 断签 / 第一次签到：从第 1 天重新开始
        }
        val award = nextIdx
        if (award == 0) filled = 0   // 新一轮周期开始
        lastKey = today
        totalDays++
        filled++
        nextIdx = (award + 1) % CHECKIN_DAYS
        return award
    }

    fun save(prefs: android.content.SharedPreferences) {
        prefs.edit()
            .putInt("r_ciNext", nextIdx)
            .putInt("r_ciFilled", filled)
            .putInt("r_ciLast", lastKey)
            .putInt("r_ciTotal", totalDays)
            .apply()
    }

    fun load(prefs: android.content.SharedPreferences) {
        nextIdx = prefs.getInt("r_ciNext", 0).coerceIn(0, CHECKIN_DAYS - 1)
        filled = prefs.getInt("r_ciFilled", 0).coerceIn(0, CHECKIN_DAYS)
        lastKey = prefs.getInt("r_ciLast", 0)
        totalDays = prefs.getInt("r_ciTotal", 0)
    }
}

// ============================================================
//  三、每日任务 + 活跃度
// ============================================================

// 任务类型
internal const val T_KILLS = 0        // 击落 N 架敌机
internal const val T_BOSSES = 1       // 击败 N 个 BOSS
internal const val T_GAMES = 2        // 完成 N 局
internal const val T_SCORE = 3        // 单局得分达到 N（取最大值）
internal const val T_BOMBS = 4        // 使用 N 次炸弹
internal const val T_COMBO = 5        // 单局达成 N 连击（取最大值）
internal const val T_NOHIT = 6        // 无伤通关 N 关
internal const val T_LEVELS = 7       // 通关 N 关
internal const val T_COINS = 8        // 单局获得 N 金币（取最大值）
internal const val T_TIME = 9         // 累计存活 N 秒

internal class TaskSpec(
    val type: Int,
    val target: Int,
    val act: Int,        // 完成后给的活跃度
    val desc: String
)

internal val TASK_POOL: Array<TaskSpec> = arrayOf(
    TaskSpec(T_KILLS, 40, 20, "击落 40 架敌机"),
    TaskSpec(T_KILLS, 100, 35, "击落 100 架敌机"),
    TaskSpec(T_BOSSES, 1, 25, "击败 1 个 BOSS"),
    TaskSpec(T_BOSSES, 3, 45, "击败 3 个 BOSS"),
    TaskSpec(T_GAMES, 1, 15, "完成 1 局游戏"),
    TaskSpec(T_GAMES, 3, 35, "完成 3 局游戏"),
    TaskSpec(T_SCORE, 600, 20, "单局得分达到 600"),
    TaskSpec(T_SCORE, 1500, 40, "单局得分达到 1500"),
    TaskSpec(T_BOMBS, 3, 15, "使用 3 次炸弹"),
    TaskSpec(T_COMBO, 15, 25, "单局达成 15 连击"),
    TaskSpec(T_NOHIT, 1, 30, "无伤通关 1 个关卡"),
    TaskSpec(T_LEVELS, 2, 30, "通关 2 个关卡"),
    TaskSpec(T_COINS, 400, 20, "单局获得 400 金币"),
    TaskSpec(T_TIME, 180, 20, "累计存活 180 秒")
)

internal const val TASKS_PER_DAY = 3

/** 用日期做种子，每天从池子里挑 3 个不重复的任务 */
internal fun pickDailyTasks(seed: Int): IntArray {
    val n = TASK_POOL.size
    val used = BooleanArray(n)
    val out = IntArray(TASKS_PER_DAY)
    var s = seed
    var k = 0
    var guard = 0
    while (k < TASKS_PER_DAY && guard < 200) {
        guard++
        s = s * 1103515245 + 12345
        var idx = ((s shr 16) and 0x7FFF) % n
        if (idx < 0) idx += n
        if (used[idx]) continue
        used[idx] = true
        out[k] = idx
        k++
    }
    // 兜底：万一没抽满就按顺序补齐
    var f = 0
    for (i in k until TASKS_PER_DAY) {
        while (f < n && used[f]) f++
        out[i] = if (f < n) f else 0
        if (f < n) { used[f] = true; f++ }
    }
    return out
}

internal class DailyTaskState {

    var dayKey = 0
    var taskIdx = pickDailyTasks(1)
    var progress = IntArray(TASKS_PER_DAY)
    var done = BooleanArray(TASKS_PER_DAY)

    /** 换天就重置并重新抽题；同一天调用无副作用 */
    fun reset(today: Int, picks: IntArray) {
        if (dayKey == today) return
        dayKey = today
        taskIdx = picks
        progress = IntArray(TASKS_PER_DAY)
        done = BooleanArray(TASKS_PER_DAY)
    }

    fun specAt(slot: Int): TaskSpec {
        val i = if (slot in taskIdx.indices) taskIdx[slot] else 0
        return if (i in TASK_POOL.indices) TASK_POOL[i] else TASK_POOL[0]
    }

    /** 累计型推进（击落 / BOSS / 炸弹…），返回本次拿到的活跃度 */
    fun advance(type: Int, value: Int): Int {
        if (value <= 0) return 0
        var gained = 0
        for (i in 0 until TASKS_PER_DAY) {
            val sp = specAt(i)
            if (sp.type != type) continue
            if (done[i]) continue
            progress[i] += value
            if (progress[i] >= sp.target) {
                done[i] = true
                gained += sp.act
            }
        }
        return gained
    }

    /** 取最大值型推进（单局得分 / 连击 / 单局金币），返回本次拿到的活跃度 */
    fun best(type: Int, value: Int): Int {
        if (value <= 0) return 0
        var gained = 0
        for (i in 0 until TASKS_PER_DAY) {
            val sp = specAt(i)
            if (sp.type != type) continue
            if (done[i]) continue
            if (value > progress[i]) progress[i] = value
            if (progress[i] >= sp.target) {
                done[i] = true
                gained += sp.act
            }
        }
        return gained
    }

    fun save(prefs: android.content.SharedPreferences) {
        val e = prefs.edit()
        e.putInt("r_tkDay", dayKey)
        for (i in 0 until TASKS_PER_DAY) {
            e.putInt("r_tkIdx$i", taskIdx[i])
            e.putInt("r_tkPro$i", progress[i])
            e.putBoolean("r_tkDone$i", done[i])
        }
        e.apply()
    }

    fun load(prefs: android.content.SharedPreferences) {
        dayKey = prefs.getInt("r_tkDay", 0)
        for (i in 0 until TASKS_PER_DAY) {
            taskIdx[i] = prefs.getInt("r_tkIdx$i", i).coerceIn(0, TASK_POOL.size - 1)
            progress[i] = prefs.getInt("r_tkPro$i", 0)
            done[i] = prefs.getBoolean("r_tkDone$i", false)
        }
    }
}

internal class ActivityTier(val need: Int, val coins: Int, val keys: Int, val short: String)

internal val ACTIVITY_TIERS: Array<ActivityTier> = arrayOf(
    ActivityTier(30, 150, 0, "150 金"),
    ActivityTier(60, 300, 0, "300 金"),
    ActivityTier(100, 500, 1, "500 金+钥")
)

internal const val ACTIVITY_MAX = 100   // 画进度条用

// ============================================================
//  四、开箱抽奖
// ============================================================

internal const val BOX_COIN = 0
internal const val BOX_KEY = 1

internal class BoxSpec(
    val type: Int,
    val name: String,
    val costCoins: Int,
    val costKeys: Int,
    val tip: String,
    val color: Int
)

internal val BOXES: Array<BoxSpec> = arrayOf(
    BoxSpec(BOX_COIN, "金币宝箱", 300, 0, "300 金币一次 · 10 次内必出大奖", 0xFFFFB300.toInt()),
    BoxSpec(BOX_KEY, "秘银宝箱", 0, 1, "1 把钥匙一次 · 大奖概率更高", 0xFF7E57C2.toInt())
)

// 奖励种类
internal const val L_COIN = 0     // 金币
internal const val L_KEY = 1      // 钥匙
internal const val L_WING = 2     // 僚机解锁券
internal const val L_EQUIP = 3    // 装备解锁券
internal const val L_SHIP = 4     // 战机解锁券

internal class LootEntry(
    val kind: Int,
    val amount: Int,
    val weight: Int,
    val label: String,
    val color: Int,
    val big: Boolean      // true = 算「大奖」，参与保底
)

internal val LOOT_COIN: Array<LootEntry> = arrayOf(
    LootEntry(L_COIN, 100, 30, "金币 ×100", 0xFFFFD54F.toInt(), false),
    LootEntry(L_COIN, 200, 26, "金币 ×200", 0xFFFFD54F.toInt(), false),
    LootEntry(L_COIN, 400, 16, "金币 ×400", 0xFFFFD54F.toInt(), false),
    LootEntry(L_COIN, 800, 8, "金币 ×800", 0xFFFFB300.toInt(), false),
    LootEntry(L_KEY, 1, 8, "秘银钥匙 ×1", 0xFF7E57C2.toInt(), true),
    LootEntry(L_WING, 0, 5, "僚机解锁券", 0xFF4DD0E1.toInt(), true),
    LootEntry(L_EQUIP, 0, 5, "装备解锁券", 0xFF66BB6A.toInt(), true),
    LootEntry(L_SHIP, 0, 2, "战机解锁券", 0xFFFF5252.toInt(), true)
)

internal val LOOT_KEY: Array<LootEntry> = arrayOf(
    LootEntry(L_COIN, 600, 20, "金币 ×600", 0xFFFFD54F.toInt(), false),
    LootEntry(L_COIN, 1200, 18, "金币 ×1200", 0xFFFFB300.toInt(), false),
    LootEntry(L_COIN, 2500, 10, "金币 ×2500", 0xFFFFAB00.toInt(), false),
    LootEntry(L_KEY, 1, 10, "秘银钥匙 ×1", 0xFF7E57C2.toInt(), true),
    LootEntry(L_KEY, 2, 4, "秘银钥匙 ×2", 0xFF7E57C2.toInt(), true),
    LootEntry(L_WING, 0, 14, "僚机解锁券", 0xFF4DD0E1.toInt(), true),
    LootEntry(L_EQUIP, 0, 14, "装备解锁券", 0xFF66BB6A.toInt(), true),
    LootEntry(L_SHIP, 0, 8, "战机解锁券", 0xFFFF5252.toInt(), true)
)

internal const val PITY_LIMIT = 10   // 连续 10 次没出大奖，第 10 次强制出

// ============================================================
//  总状态
// ============================================================

internal class RewardState {

    // 1. 星级
    val stars = LevelStarState()
    val msClaimed = BooleanArray(STAR_MILESTONES.size)

    // 2. 签到
    val checkin = CheckinState()

    // 3. 每日任务 / 活跃度
    val tasks = DailyTaskState()
    var dayKey = 0
    var activity = 0
    val tierClaimed = BooleanArray(ACTIVITY_TIERS.size)

    // 4. 宝箱
    var keys = 0
    var sinceBigCoin = 0
    var sinceBigKey = 0
    var boxOpened = 0

    // ---------------- 每日刷新 ----------------

    /** 换天时重置活跃度与领取状态，并重新抽题 */
    fun refreshDaily() {
        val t = todayKey()
        if (t != dayKey) {
            dayKey = t
            activity = 0
            for (i in tierClaimed.indices) tierClaimed[i] = false
        }
        tasks.reset(t, pickDailyTasks(t))
    }

    /** 累计型任务推进，返回本次新增活跃度 */
    fun push(type: Int, value: Int): Int {
        refreshDaily()
        val g = tasks.advance(type, value)
        activity += g
        return g
    }

    /** 取最大值型任务推进，返回本次新增活跃度 */
    fun pushBest(type: Int, value: Int): Int {
        refreshDaily()
        val g = tasks.best(type, value)
        activity += g
        return g
    }

    // ---------------- 星级里程碑 ----------------

    fun milestoneReady(i: Int): Boolean =
        i in STAR_MILESTONES.indices && !msClaimed[i] && stars.total() >= STAR_MILESTONES[i].need

    fun claimMilestone(i: Int): Boolean {
        if (!milestoneReady(i)) return false
        msClaimed[i] = true
        return true
    }

    // ---------------- 活跃度档位 ----------------

    fun tierReady(i: Int): Boolean =
        i in ACTIVITY_TIERS.indices && !tierClaimed[i] && activity >= ACTIVITY_TIERS[i].need

    fun claimTier(i: Int): Boolean {
        if (!tierReady(i)) return false
        tierClaimed[i] = true
        return true
    }

    // ---------------- 开箱 ----------------

    fun canOpen(boxType: Int, coins: Int): Boolean {
        val b = if (boxType in BOXES.indices) BOXES[boxType] else return false
        return coins >= b.costCoins && keys >= b.costKeys
    }

    fun boxSpec(boxType: Int): BoxSpec? = if (boxType in BOXES.indices) BOXES[boxType] else null

    /** 抽一次（只负责决定抽中什么，不负责发放） */
    fun rollLoot(boxType: Int): LootEntry {
        val table = if (boxType == BOX_KEY) LOOT_KEY else LOOT_COIN
        val pity = if (boxType == BOX_KEY) sinceBigKey else sinceBigCoin
        val forceBig = pity + 1 >= PITY_LIMIT
        var total = 0
        for (e in table) {
            if (forceBig && !e.big) continue
            total += e.weight
        }
        if (total <= 0) return table[0]
        var r = kotlin.random.Random.nextInt(total)
        for (e in table) {
            if (forceBig && !e.big) continue
            r -= e.weight
            if (r < 0) return e
        }
        return table[0]
    }

    /**
     * 开箱后记账：更新保底计数。
     * 花费由 GameView 在开箱前自己扣（金币在 UpgradeState，钥匙在这里）。
     */
    fun noteOpen(boxType: Int, entry: LootEntry) {
        boxOpened++
        if (entry.big) {
            if (boxType == BOX_KEY) sinceBigKey = 0 else sinceBigCoin = 0
        } else {
            if (boxType == BOX_KEY) sinceBigKey++ else sinceBigCoin++
        }
    }

    fun pityOf(boxType: Int): Int = if (boxType == BOX_KEY) sinceBigKey else sinceBigCoin

    // ---------------- 存档 ----------------

    fun save(prefs: android.content.SharedPreferences) {
        stars.save(prefs)
        checkin.save(prefs)
        tasks.save(prefs)
        val e = prefs.edit()
        for (i in msClaimed.indices) e.putBoolean("r_ms$i", msClaimed[i])
        for (i in tierClaimed.indices) e.putBoolean("r_tr$i", tierClaimed[i])
        e.putInt("r_day", dayKey)
        e.putInt("r_act", activity)
        e.putInt("r_keys", keys)
        e.putInt("r_pityC", sinceBigCoin)
        e.putInt("r_pityK", sinceBigKey)
        e.putInt("r_boxN", boxOpened)
        e.apply()
    }

    fun load(prefs: android.content.SharedPreferences) {
        stars.load(prefs)
        checkin.load(prefs)
        tasks.load(prefs)
        for (i in msClaimed.indices) msClaimed[i] = prefs.getBoolean("r_ms$i", false)
        for (i in tierClaimed.indices) tierClaimed[i] = prefs.getBoolean("r_tr$i", false)
        dayKey = prefs.getInt("r_day", 0)
        activity = prefs.getInt("r_act", 0)
        keys = prefs.getInt("r_keys", 0)
        sinceBigCoin = prefs.getInt("r_pityC", 0)
        sinceBigKey = prefs.getInt("r_pityK", 0)
        boxOpened = prefs.getInt("r_boxN", 0)
    }
}
