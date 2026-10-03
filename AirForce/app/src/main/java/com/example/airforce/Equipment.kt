package com.example.airforce

/**
 * 僚机（跟随玩家自动开火的小飞机）与装备（被动增益）。
 * 两者都用金币在商店解锁，解锁后永久生效。
 */

// ---------------- 僚机 ----------------

internal const val WG_NONE = 0      // 无
internal const val WG_TWIN = 1      // 双翼僚机：左右各一架，直射
internal const val WG_LASER = 2     // 激光僚机：单架，持续光束
internal const val WG_MISSILE = 3   // 导弹僚机：发射追踪弹
internal const val WG_COUNT = 4

internal class WingmanSpec(
    val id: Int,
    val name: String,
    val desc: String,
    val color: Int,
    val cost: Int,
    val count: Int,          // 僚机数量
    val fireInterval: Float, // 开火间隔（秒）
    val missile: Boolean     // 是否发射追踪弹
)

internal val WINGMEN: Array<WingmanSpec> = arrayOf(
    WingmanSpec(WG_NONE, "无僚机", "独自作战", 0xFF78909C.toInt(), 0, 0, 0f, false),
    WingmanSpec(WG_TWIN, "双翼僚机", "左右各一架，跟随直射", 0xFF4DD0E1.toInt(), 1200, 2, 0.42f, false),
    WingmanSpec(WG_LASER, "激光僚机", "单架，持续激光束", 0xFF69F0AE.toInt(), 2000, 1, 0.18f, false),
    WingmanSpec(WG_MISSILE, "导弹僚机", "发射自动追踪导弹", 0xFFFFAB40.toInt(), 3200, 2, 0.95f, true)
)

internal fun wingmanSpec(id: Int): WingmanSpec =
    if (id in WINGMEN.indices) WINGMEN[id] else WINGMEN[0]

// ---------------- 装备 ----------------

internal const val EQ_LIFESTEAL = 0   // 吸血：击落敌机 8% 概率回 1 血
internal const val EQ_CRIT = 1        // 暴击：20% 概率双倍伤害
internal const val EQ_REGEN = 2       // 再生：每 25 秒自动回 1 血
internal const val EQ_GREED = 3       // 贪婪：金币收益 +50%
internal const val EQ_OVERDRIVE = 4   // 超载：连击 ≥10 时射速 +25%
internal const val EQ_GRAVITY = 5     // 重力场：道具吸附范围大幅提升
internal const val EQ_COUNT = 6

internal class EquipmentSpec(
    val id: Int,
    val name: String,
    val desc: String,
    val color: Int,
    val cost: Int
)

internal val EQUIPMENT: Array<EquipmentSpec> = arrayOf(
    EquipmentSpec(EQ_LIFESTEAL, "吸血核心", "击落敌机有 8% 概率回复 1 点生命", 0xFFEF5350.toInt(), 1600),
    EquipmentSpec(EQ_CRIT, "暴击芯片", "子弹有 20% 概率造成双倍伤害", 0xFFFFCA28.toInt(), 1800),
    EquipmentSpec(EQ_REGEN, "纳米再生", "每 25 秒自动回复 1 点生命", 0xFF66BB6A.toInt(), 2200),
    EquipmentSpec(EQ_GREED, "贪婪装置", "所有金币收益 +50%", 0xFFFFB300.toInt(), 2400),
    EquipmentSpec(EQ_OVERDRIVE, "超载引擎", "连击 ≥10 时射速提升 25%", 0xFF7E57C2.toInt(), 2800),
    EquipmentSpec(EQ_GRAVITY, "重力场发生器", "道具吸附范围大幅提升", 0xFF29B6F6.toInt(), 1500)
)

internal fun equipmentSpec(id: Int): EquipmentSpec =
    if (id in EQUIPMENT.indices) EQUIPMENT[id] else EQUIPMENT[0]

/** 僚机与装备的解锁状态，读写 SharedPreferences */
internal class GearState {
    private val wingUnlocked = BooleanArray(WG_COUNT) { it == WG_NONE }
    private val eqOwned = BooleanArray(EQ_COUNT)
    var selectedWingman = WG_NONE

    fun wingmanUnlocked(id: Int): Boolean = id in wingUnlocked.indices && wingUnlocked[id]

    fun hasEquipment(id: Int): Boolean = id in eqOwned.indices && eqOwned[id]

    fun unlockWingman(id: Int) {
        if (id in wingUnlocked.indices) wingUnlocked[id] = true
    }

    fun unlockEquipment(id: Int) {
        if (id in eqOwned.indices) eqOwned[id] = true
    }

    fun save(prefs: android.content.SharedPreferences) {
        val e = prefs.edit()
        for (i in 0 until WG_COUNT) e.putBoolean("wg$i", wingUnlocked[i])
        for (i in 0 until EQ_COUNT) e.putBoolean("eq$i", eqOwned[i])
        e.putInt("wgSel", selectedWingman)
        e.apply()
    }

    fun load(prefs: android.content.SharedPreferences) {
        for (i in 0 until WG_COUNT) wingUnlocked[i] = prefs.getBoolean("wg$i", i == WG_NONE)
        wingUnlocked[WG_NONE] = true
        for (i in 0 until EQ_COUNT) eqOwned[i] = prefs.getBoolean("eq$i", false)
        selectedWingman = prefs.getInt("wgSel", WG_NONE)
        if (!wingmanUnlocked(selectedWingman)) selectedWingman = WG_NONE
    }
}
