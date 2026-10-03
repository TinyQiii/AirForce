package com.example.airforce

/**
 * 永久升级（商店）。等级存进 SharedPreferences，跨局保留。
 *
 * 金币来源：击落敌机、过关结算奖励。
 */
internal class Upgrade(
    val id: Int,
    val name: String,
    val desc: String,
    val maxLevel: Int,
    val baseCost: Int,
    val growth: Float,
    val color: Int
) {
    /** 从当前等级升到下一级所需金币 */
    fun costAt(level: Int): Int = (baseCost * (1f + level * growth)).toInt()
}

internal const val U_FIREPOWER = 0      // 火力强化
internal const val U_ARMOR = 1          // 装甲强化
internal const val U_AMMO = 2           // 弹药储备
internal const val U_SHIELD = 3         // 护盾核心
internal const val U_COIN = 4           // 金币雷达
internal const val U_MAGNET = 5         // 磁力场
internal const val U_W_LASER = 6        // 解锁激光
internal const val U_W_SPREAD = 7       // 解锁散射
internal const val U_W_RING = 8         // 解锁环形
internal const val U_REVIVE = 9         // 复活装置
internal const val U_SPEED = 10         // 【新】机动强化
internal const val U_W_HOMING = 11      // 【新】解锁追踪弹
internal const val U_COUNT = 12

internal val UPGRADES: Array<Upgrade> = arrayOf(
    Upgrade(U_FIREPOWER, "火力强化", "开局火力等级 +1", 3, 200, 0.7f, 0xFF4FC3F7.toInt()),
    Upgrade(U_ARMOR, "装甲强化", "开局生命 +1", 2, 320, 0.9f, 0xFFFF8A80.toInt()),
    Upgrade(U_AMMO, "弹药储备", "开局炸弹 +1", 3, 260, 0.7f, 0xFFFFD54F.toInt()),
    Upgrade(U_SHIELD, "护盾核心", "开局自带 2 秒护盾", 3, 300, 0.8f, 0xFF69F0AE.toInt()),
    Upgrade(U_COIN, "金币雷达", "金币收益 +25%", 4, 420, 0.8f, 0xFFFFB300.toInt()),
    Upgrade(U_MAGNET, "磁力场", "道具吸附范围增大", 3, 280, 0.7f, 0xFFBA68C8.toInt()),
    Upgrade(U_W_LASER, "解锁 · 激光", "持续光束，可穿透敌机", 1, 600, 0f, 0xFF69F0AE.toInt()),
    Upgrade(U_W_SPREAD, "解锁 · 散射", "大范围扇形弹幕", 1, 950, 0f, 0xFFFFAB40.toInt()),
    Upgrade(U_W_RING, "解锁 · 环形", "向四周同时开火", 1, 1400, 0f, 0xFFE040FB.toInt()),
    Upgrade(U_REVIVE, "复活装置", "阵亡时原地复活一次", 1, 2000, 0f, 0xFFFF5252.toInt()),
    Upgrade(U_SPEED, "机动强化", "机身跟随速度 +8%", 3, 340, 0.7f, 0xFF4DD0E1.toInt()),
    Upgrade(U_W_HOMING, "解锁 · 追踪弹", "自动锁定最近敌机，指哪打哪", 1, 1900, 0f, 0xFF7E57C2.toInt())
)

/** 商店升级数据：等级数组 + 金币，读写 SharedPreferences */
internal class UpgradeState {

    val levels = IntArray(U_COUNT)
    var coins = 0
    var selectedWeapon = W_BASIC

    fun levelOf(id: Int): Int = if (id in levels.indices) levels[id] else 0

    fun isMaxed(id: Int): Boolean =
        id in UPGRADES.indices && levels[id] >= UPGRADES[id].maxLevel

    fun costOf(id: Int): Int =
        if (id in UPGRADES.indices) UPGRADES[id].costAt(levels[id]) else Int.MAX_VALUE

    /** 尝试购买，成功返回 true */
    fun buy(id: Int): Boolean {
        if (id !in UPGRADES.indices) return false
        if (isMaxed(id)) return false
        val c = costOf(id)
        if (coins < c) return false
        coins -= c
        levels[id]++
        return true
    }

    /** 武器是否已解锁（基础武器永远可用） */
    fun weaponUnlocked(w: Int): Boolean = when (w) {
        W_BASIC -> true
        W_LASER -> levelOf(U_W_LASER) > 0
        W_SPREAD -> levelOf(U_W_SPREAD) > 0
        W_RING -> levelOf(U_W_RING) > 0
        W_HOMING -> levelOf(U_W_HOMING) > 0
        else -> false
    }

    fun save(prefs: android.content.SharedPreferences) {
        val e = prefs.edit()
        for (i in 0 until U_COUNT) e.putInt("u$i", levels[i])
        e.putInt("coins", coins)
        e.putInt("weapon", selectedWeapon)
        e.apply()
    }

    fun load(prefs: android.content.SharedPreferences) {
        for (i in 0 until U_COUNT) levels[i] = prefs.getInt("u$i", 0)
        coins = prefs.getInt("coins", 0)
        selectedWeapon = prefs.getInt("weapon", W_BASIC)
        if (!weaponUnlocked(selectedWeapon)) selectedWeapon = W_BASIC
    }
}

// ============================================================
//  武器元数据
// ============================================================

internal class WeaponSpec(
    val type: Int,
    val name: String,
    val shortName: String,
    val desc: String,
    val color: Int
)

internal val WEAPONS: Array<WeaponSpec> = arrayOf(
    WeaponSpec(W_BASIC, "标准炮", "标准", "均衡的直线弹道，随等级扩散", 0xFF4FC3F7.toInt()),
    WeaponSpec(W_LASER, "激光", "激光", "持续光束，可穿透多个敌机", 0xFF69F0AE.toInt()),
    WeaponSpec(W_SPREAD, "散射炮", "散射", "大范围扇形，近战强", 0xFFFFAB40.toInt()),
    WeaponSpec(W_RING, "环形炮", "环形", "向四周同时开火，无死角", 0xFFE040FB.toInt()),
    WeaponSpec(W_HOMING, "追踪弹", "追踪", "自动锁定最近敌机，命中率高", 0xFF7E57C2.toInt())
)

internal fun weaponSpec(t: Int): WeaponSpec =
    if (t in WEAPONS.indices) WEAPONS[t] else WEAPONS[0]
