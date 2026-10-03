package com.example.airforce

import kotlin.random.Random

// ============================================================
//  敌人类型
// ============================================================
internal const val K_SCOUT = 0        // 侦察机：直飞，速度快
internal const val K_ZIGZAG = 1       // 蛇形机：正弦摆动
internal const val K_TANK = 2         // 坦克机：慢，血厚，三连发
internal const val K_DIVER = 3        // 俯冲机：追踪玩家
internal const val K_ELITE = 4        // 精英机：悬停扫射
internal const val K_ORBITER = 5      // 【新】绕圈机：绕中心点转圈
internal const val K_SPLITTER = 6     // 【新】分裂机：死亡后分裂出小机
internal const val K_SHIELDED = 7     // 【新】护盾机：有护盾需先打破
internal const val K_SNIPER = 8       // 【新】狙击机：蓄力后高精度瞄准射击
internal const val K_KAMIKAZE = 9     // 【新】自爆机：不开火，撞向玩家
internal const val K_BOSS = 10        // BOSS
internal const val K_BOMBER = 11      // 【新】轰炸机：慢速厚血，投掷散射弹幕
internal const val K_WARP = 12        // 【新】瞬移机：周期性瞬移，难以命中
internal const val K_COUNT = 13

// ============================================================
//  道具类型
// ============================================================
internal const val P_WEAPON = 0       // 火力升级
internal const val P_BOMB = 1         // 补充炸弹
internal const val P_LIFE = 2         // 加命
internal const val P_SHIELD = 3       // 护盾
internal const val P_COIN = 4         // 【新】金币
internal const val P_MAGNET = 5       // 【新】磁铁：自动吸附道具
internal const val P_SLOW = 6         // 【新】时间减速
internal const val P_COUNT = 7

// ============================================================
//  武器类型
// ============================================================
internal const val W_BASIC = 0        // 基础：直线弹，随等级扩散
internal const val W_LASER = 1        // 【新】激光：持续光束，穿透
internal const val W_SPREAD = 2       // 【新】散射：大范围扇形弹幕
internal const val W_RING = 3         // 【新】环形：向多方向同时开火
internal const val W_HOMING = 4       // 【新】追踪弹：自动锁定最近敌机
internal const val W_COUNT = 5

// ============================================================
//  BOSS 类型
// ============================================================
internal const val BOSS_CRIMSON = 0   // 赤红：扇形弹幕
internal const val BOSS_AZURE = 1     // 湛蓝：追踪弹 + 侧翼炮
internal const val BOSS_VOID = 2      // 虚空：旋转弹幕 + 召唤小机
internal const val BOSS_OMEGA = 3     // 【新】欧米伽：激光扫射 + 弹幕墙
internal const val BOSS_NOVA = 4      // 【新】新星：环形爆发 + 分裂弹

// ============================================================
//  通用工具
// ============================================================

/** O(1) 移除：把队尾元素挪到被删位置，再删队尾。避免 ArrayList.removeAt 的 O(n) 搬移。 */
internal inline fun <T> ArrayList<T>.fastRemove(i: Int) {
    val last = size - 1
    if (i != last) this[i] = this[last]
    removeAt(last)
}

/** 极简对象池：复用实体，避免每帧 new 造成 GC 抖动。 */
internal class Pool<T>(private val factory: () -> T, private val cap: Int) {
    private val free = ArrayList<T>()
    fun obtain(): T = if (free.isEmpty()) factory() else free.removeAt(free.size - 1)
    fun recycle(v: T) {
        if (free.size < cap) free.add(v)
    }
}

// ============================================================
//  实体（全部可变字段 + 复用，避免 GC 抖动）
// ============================================================

class Bullet {
    var x = 0f
    var y = 0f
    var vx = 0f
    var vy = 0f
    var r = 0f
    var friendly = true
    var power = 1
    var missile = false
    var homing = false
    var pierce = false          // 【新】穿透弹（击中后不消失）
    var color = 0               // 【新】自定义颜色（0 = 用默认贴图）
}

class Particle {
    var x = 0f
    var y = 0f
    var vx = 0f
    var vy = 0f
    var life = 0f
    var maxLife = 1f
    var color = 0
    var size = 0f
    var drag = 2.2f
    var additive = false
}

class Enemy {
    var x = 0f
    var y = 0f
    var vx = 0f
    var vy = 0f
    var r = 0f
    var hp = 0
    var maxHp = 1
    var kind = 0
    var score = 0
    var fireTimer = 0f
    var phase = 0f
    var baseX = 0f
    var amp = 0f
    var targetY = 0f
    var flash = 0f
    var entering = true

    // ---- 新增字段 ----
    var bossType = BOSS_CRIMSON   // BOSS 变体
    var shieldHp = 0              // 护盾机的护盾值（>0 时先扣护盾）
    var shieldMax = 0
    var orbitCx = 0f              // 绕圈机的圆心
    var orbitCy = 0f
    var orbitR = 0f
    var orbitSpd = 0f
    var gen = 0                   // 分裂代数：0 = 母体，1 = 子体
    var chargeTimer = 0f          // 蓄力计时（狙击机 / 自爆机）
    var charging = false
    var coinDrop = 0              // 击落掉落金币数
    var warpTimer = 0f            // 【新】瞬移机计时
    var subTimer = 0f             // 【新】通用副计时（轰炸机等）

    fun reset() {
        x = 0f; y = 0f; vx = 0f; vy = 0f; r = 0f
        hp = 0; maxHp = 1; kind = 0; score = 0
        fireTimer = 0f; phase = 0f; baseX = 0f; amp = 0f
        targetY = 0f; flash = 0f; entering = true
        bossType = BOSS_CRIMSON
        shieldHp = 0; shieldMax = 0
        orbitCx = 0f; orbitCy = 0f; orbitR = 0f; orbitSpd = 0f
        gen = 0; chargeTimer = 0f; charging = false; coinDrop = 0
        warpTimer = 0f; subTimer = 0f
    }

    /** 护盾是否还在（护盾机专用） */
    val shielded: Boolean get() = shieldHp > 0
}

class PowerUp {
    var x = 0f
    var y = 0f
    var vx = 0f          // 【新】磁铁吸附时需要水平速度
    var vy = 0f
    var kind = 0
    var phase = 0f
    var attracted = false // 【新】是否已被磁铁吸引
}

class Star {
    var x = 0f
    var y = 0f
    var speed = 0f
    var size = 0f
    var alpha = 0
    var phase = 0f
}

class Ring {
    var x = 0f
    var y = 0f
    var r = 0f
    var maxR = 0f
    var life = 0f
    var color = 0
    var width = 0f
}

class FloatText {
    var x = 0f
    var y = 0f
    var life = 0f
    var maxLife = 1f
    var text = ""
    var color = 0
    var size = 0f
}

internal class Neb {
    var x = 0f
    var y = 0f
    var speed = 0f
    var scale = 0f
    var alpha = 0
    var variant = 0
}

// ============================================================
//  敌人配置表（集中管理，方便调平衡）
// ============================================================

internal class EnemySpec(
    val kind: Int,
    val radiusDp: Float,      // 半径（dp）
    val hpMul: Int,           // 血量倍率（乘以随时间的基数）
    val score: Int,           // 击落得分
    val coin: Int,            // 掉落金币
    val fires: Boolean        // 是否会开火
)

internal val ENEMY_SPECS: Array<EnemySpec> = arrayOf(
    EnemySpec(K_SCOUT, 15f, 1, 10, 1, false),
    EnemySpec(K_ZIGZAG, 16f, 2, 20, 1, false),
    EnemySpec(K_TANK, 24f, 4, 40, 3, true),
    EnemySpec(K_DIVER, 15f, 2, 35, 2, false),
    EnemySpec(K_ELITE, 21f, 6, 80, 5, true),
    EnemySpec(K_ORBITER, 14f, 3, 45, 2, false),
    EnemySpec(K_SPLITTER, 22f, 3, 55, 3, false),
    EnemySpec(K_SHIELDED, 20f, 3, 70, 4, true),
    EnemySpec(K_SNIPER, 17f, 3, 65, 4, true),
    EnemySpec(K_KAMIKAZE, 16f, 2, 30, 2, false),
    EnemySpec(K_BOMBER, 26f, 6, 120, 6, true),
    EnemySpec(K_WARP, 15f, 4, 90, 5, true),
    EnemySpec(K_BOSS, 52f, 1, 600, 60, true)
)

internal fun enemySpec(kind: Int): EnemySpec =
    if (kind in ENEMY_SPECS.indices) ENEMY_SPECS[kind] else ENEMY_SPECS[0]

// ============================================================
//  随机工具
// ============================================================

internal fun randRange(a: Float, b: Float): Float = a + Random.nextFloat() * (b - a)
