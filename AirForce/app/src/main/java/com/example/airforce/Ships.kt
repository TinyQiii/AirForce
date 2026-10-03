package com.example.airforce

/**
 * 可解锁战机。不同战机有不同的机动性、射速、血量与被动技能，
 * 让"开局选机"成为一层策略。解锁消耗金币。
 */

internal const val SHIP_DAWN = 0       // 曙光号：均衡（默认）
internal const val SHIP_SWIFT = 1      // 疾风号：高机动
internal const val SHIP_FORTRESS = 2   // 堡垒号：高血量
internal const val SHIP_INFERNO = 3    // 烈焰号：高火力
internal const val SHIP_PHANTOM = 4    // 幻影号：自带护盾
internal const val SHIP_PHOENIX = 5    // 凤凰号：自动复活
internal const val SHIP_COUNT = 6

internal class ShipSpec(
    val id: Int,
    val name: String,
    val desc: String,
    val color: Int,
    val speedMul: Float,      // 跟随速度倍率（越大越跟手）
    val fireMul: Float,       // 射击间隔倍率（越小射得越快）
    val hpBonus: Int,         // 开局额外生命
    val startPower: Int,      // 开局火力等级加成
    val startShield: Float,   // 开局护盾秒数
    val autoRevive: Boolean,  // 阵亡自动复活一次
    val cost: Int             // 解锁金币（0 = 默认拥有）
)

internal val SHIPS: Array<ShipSpec> = arrayOf(
    ShipSpec(
        SHIP_DAWN, "曙光号", "均衡机体 · 新手推荐", 0xFF4FC3F7.toInt(),
        speedMul = 1.00f, fireMul = 1.00f, hpBonus = 0, startPower = 0,
        startShield = 0f, autoRevive = false, cost = 0
    ),
    ShipSpec(
        SHIP_SWIFT, "疾风号", "机动性极高 · 但装甲较薄", 0xFF69F0AE.toInt(),
        speedMul = 1.35f, fireMul = 0.95f, hpBonus = -1, startPower = 0,
        startShield = 0f, autoRevive = false, cost = 900
    ),
    ShipSpec(
        SHIP_FORTRESS, "堡垒号", "重装甲 · 生命 +2 · 移动略慢", 0xFFFF8A80.toInt(),
        speedMul = 0.82f, fireMul = 1.10f, hpBonus = 2, startPower = 0,
        startShield = 0f, autoRevive = false, cost = 1400
    ),
    ShipSpec(
        SHIP_INFERNO, "烈焰号", "火力全开 · 开局火力 +2 级", 0xFFFFAB40.toInt(),
        speedMul = 0.95f, fireMul = 0.85f, hpBonus = 0, startPower = 2,
        startShield = 0f, autoRevive = false, cost = 2200
    ),
    ShipSpec(
        SHIP_PHANTOM, "幻影号", "隐形护盾 · 开局 6 秒无敌护盾", 0xFFBA68C8.toInt(),
        speedMul = 1.05f, fireMul = 1.00f, hpBonus = 0, startPower = 0,
        startShield = 6f, autoRevive = false, cost = 3000
    ),
    ShipSpec(
        SHIP_PHOENIX, "凤凰号", "浴火重生 · 阵亡时自动复活一次", 0xFFFF5252.toInt(),
        speedMul = 1.00f, fireMul = 0.95f, hpBonus = 0, startPower = 1,
        startShield = 0f, autoRevive = true, cost = 5200
    )
)

internal fun shipSpec(id: Int): ShipSpec =
    if (id in SHIPS.indices) SHIPS[id] else SHIPS[0]

/** 战机解锁/选择状态，读写 SharedPreferences */
internal class ShipState {
    private val unlocked = BooleanArray(SHIP_COUNT) { it == SHIP_DAWN }
    var selected = SHIP_DAWN

    fun isUnlocked(id: Int): Boolean = id in unlocked.indices && unlocked[id]

    fun unlock(id: Int) {
        if (id in unlocked.indices) unlocked[id] = true
    }

    fun save(prefs: android.content.SharedPreferences) {
        val e = prefs.edit()
        for (i in 0 until SHIP_COUNT) e.putBoolean("ship$i", unlocked[i])
        e.putInt("shipSel", selected)
        e.apply()
    }

    fun load(prefs: android.content.SharedPreferences) {
        for (i in 0 until SHIP_COUNT) unlocked[i] = prefs.getBoolean("ship$i", i == SHIP_DAWN)
        unlocked[SHIP_DAWN] = true
        selected = prefs.getInt("shipSel", SHIP_DAWN)
        if (!isUnlocked(selected)) selected = SHIP_DAWN
    }
}
