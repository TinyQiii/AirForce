package com.example.airforce

/**
 * 关卡配置。
 *
 * 每个关卡由三个阶段组成：
 *   1. 关卡开场（显示关卡名）
 *   2. 小怪阶段（持续 swarmSeconds 秒，按 weights 权重随机刷怪）
 *   3. BOSS 阶段（击杀 BOSS 即过关）
 *
 * weights 数组下标对应敌人 kind（0..K_COUNT-1，长度 13），值为相对权重，0 表示不出现。
 * 下标 K_BOSS(10) 恒为 0 —— BOSS 由 spawnBoss() 单独生成。
 */
internal class LevelConfig(
    val index: Int,             // 关卡序号（0 起）
    val name: String,           // 关卡名
    val subtitle: String,       // 副标题
    val bgRes: Int,             // 背景图资源
    val bgTop: Int,             // 背景渐变色（上）—— 背景图加载失败时的兜底
    val bgMid: Int,             // 背景渐变色（中）
    val bgBot: Int,             // 背景渐变色（下）
    val weights: IntArray,      // 敌人权重（长度 13）
    val swarmSeconds: Float,    // 小怪阶段时长
    val hpScale: Float,         // 血量倍率
    val spawnScale: Float,      // 刷怪间隔倍率（越小越密）
    val bossType: Int,          // BOSS 类型
    val bossHp: Int,            // BOSS 血量
    val bossName: String        // BOSS 名
)

/** 快捷构造权重数组（长度 13，K_BOSS 位恒 0） */
private fun w(
    scout: Int = 0, zigzag: Int = 0, tank: Int = 0, diver: Int = 0, elite: Int = 0,
    orbiter: Int = 0, splitter: Int = 0, shielded: Int = 0, sniper: Int = 0, kamikaze: Int = 0,
    bomber: Int = 0, warp: Int = 0
): IntArray = intArrayOf(
    scout, zigzag, tank, diver, elite, orbiter, splitter, shielded, sniper, kamikaze,
    0, bomber, warp
)

internal val LEVELS: Array<LevelConfig> = arrayOf(
    LevelConfig(
        index = 0,
        name = "第 1 关 · 星际前哨",
        subtitle = "侦察机编队 · 试探你的火力",
        bgRes = R.drawable.bg_menu,
        bgTop = 0xFF04060F.toInt(), bgMid = 0xFF0A1030.toInt(), bgBot = 0xFF1B0A2E.toInt(),
        weights = w(scout = 10),
        swarmSeconds = 30f, hpScale = 1.0f, spawnScale = 1.0f,
        bossType = BOSS_CRIMSON, bossHp = 100, bossName = "赤红巡洋舰"
    ),
    LevelConfig(
        index = 1,
        name = "第 2 关 · 陨石带",
        subtitle = "蛇形机出没 · 注意躲闪",
        bgRes = R.drawable.bg_menu,
        bgTop = 0xFF0B0518.toInt(), bgMid = 0xFF1E0A33.toInt(), bgBot = 0xFF3A0F3F.toInt(),
        weights = w(scout = 8, zigzag = 5),
        swarmSeconds = 32f, hpScale = 1.3f, spawnScale = 0.94f,
        bossType = BOSS_CRIMSON, bossHp = 160, bossName = "赤红巡洋舰 · 改"
    ),
    LevelConfig(
        index = 2,
        name = "第 3 关 · 钢铁要塞",
        subtitle = "重装甲出现 · 火力要集中",
        bgRes = R.drawable.bg_crimson,
        bgTop = 0xFF0F0805.toInt(), bgMid = 0xFF2B1408.toInt(), bgBot = 0xFF4A2109.toInt(),
        weights = w(scout = 6, zigzag = 4, tank = 4),
        swarmSeconds = 34f, hpScale = 1.6f, spawnScale = 0.88f,
        bossType = BOSS_AZURE, bossHp = 240, bossName = "湛蓝铁壁"
    ),
    LevelConfig(
        index = 3,
        name = "第 4 关 · 深空伏击",
        subtitle = "俯冲机与绕圈机 · 前后夹击",
        bgRes = R.drawable.bg_crimson,
        bgTop = 0xFF03100E.toInt(), bgMid = 0xFF082A28.toInt(), bgBot = 0xFF0C3F38.toInt(),
        weights = w(scout = 5, zigzag = 4, tank = 3, diver = 4, orbiter = 4),
        swarmSeconds = 36f, hpScale = 1.9f, spawnScale = 0.83f,
        bossType = BOSS_CRIMSON, bossHp = 340, bossName = "赤红猎杀者"
    ),
    LevelConfig(
        index = 4,
        name = "第 5 关 · 蜂巢",
        subtitle = "分裂机登场 · 别被包围",
        bgRes = R.drawable.bg_azure,
        bgTop = 0xFF0C1003.toInt(), bgMid = 0xFF23300A.toInt(), bgBot = 0xFF3B4A0E.toInt(),
        weights = w(scout = 5, zigzag = 3, tank = 3, diver = 3, elite = 2, orbiter = 3, splitter = 4),
        swarmSeconds = 38f, hpScale = 2.2f, spawnScale = 0.78f,
        bossType = BOSS_AZURE, bossHp = 460, bossName = "湛蓝蜂后"
    ),
    LevelConfig(
        index = 5,
        name = "第 6 关 · 熔火炼狱",
        subtitle = "护盾机来袭 · 先破盾再打",
        bgRes = R.drawable.bg_azure,
        bgTop = 0xFF150303.toInt(), bgMid = 0xFF3A0A08.toInt(), bgBot = 0xFF5C1409.toInt(),
        weights = w(scout = 4, zigzag = 3, tank = 3, diver = 3, elite = 2, orbiter = 3, splitter = 2, shielded = 4),
        swarmSeconds = 40f, hpScale = 2.6f, spawnScale = 0.73f,
        bossType = BOSS_VOID, bossHp = 600, bossName = "虚空之眼"
    ),
    LevelConfig(
        index = 6,
        name = "第 7 关 · 暗影狙击",
        subtitle = "狙击机锁定 · 保持移动",
        bgRes = R.drawable.bg_emerald,
        bgTop = 0xFF0A0318.toInt(), bgMid = 0xFF1D0838.toInt(), bgBot = 0xFF2E0C52.toInt(),
        weights = w(scout = 4, zigzag = 3, tank = 3, diver = 3, elite = 3, orbiter = 2, splitter = 2, shielded = 2, sniper = 5),
        swarmSeconds = 42f, hpScale = 3.0f, spawnScale = 0.68f,
        bossType = BOSS_VOID, bossHp = 780, bossName = "虚空审判"
    ),
    LevelConfig(
        index = 7,
        name = "第 8 关 · 虚空核心",
        subtitle = "自爆机突袭 · 全部兵力",
        bgRes = R.drawable.bg_emerald,
        bgTop = 0xFF050308.toInt(), bgMid = 0xFF14062B.toInt(), bgBot = 0xFF2A0A45.toInt(),
        weights = w(scout = 4, zigzag = 3, tank = 3, diver = 3, elite = 3, orbiter = 3, splitter = 3, shielded = 3, sniper = 4, kamikaze = 4),
        swarmSeconds = 45f, hpScale = 3.5f, spawnScale = 0.62f,
        bossType = BOSS_VOID, bossHp = 1000, bossName = "虚空核心 · 终焉"
    ),
    LevelConfig(
        index = 8,
        name = "第 9 关 · 轰炸前线",
        subtitle = "轰炸机压境 · 弹幕如雨",
        bgRes = R.drawable.bg_golden,
        bgTop = 0xFF140A02.toInt(), bgMid = 0xFF33200A.toInt(), bgBot = 0xFF54330E.toInt(),
        weights = w(scout = 3, zigzag = 3, tank = 3, elite = 3, splitter = 3, shielded = 3, kamikaze = 3, bomber = 5),
        swarmSeconds = 46f, hpScale = 4.0f, spawnScale = 0.58f,
        bossType = BOSS_OMEGA, bossHp = 1200, bossName = "欧米伽 · 湮灭炮台"
    ),
    LevelConfig(
        index = 9,
        name = "第 10 关 · 相位迷航",
        subtitle = "瞬移机神出鬼没 · 难以命中",
        bgRes = R.drawable.bg_golden,
        bgTop = 0xFF0A0614.toInt(), bgMid = 0xFF1E1038.toInt(), bgBot = 0xFF34205E.toInt(),
        weights = w(scout = 3, zigzag = 3, diver = 3, elite = 3, orbiter = 3, sniper = 3, bomber = 2, warp = 5),
        swarmSeconds = 48f, hpScale = 4.5f, spawnScale = 0.55f,
        bossType = BOSS_OMEGA, bossHp = 1450, bossName = "欧米伽 · 相位主宰"
    ),
    LevelConfig(
        index = 10,
        name = "第 11 关 · 终焉回廊",
        subtitle = "全部精英 · 不留余地",
        bgRes = R.drawable.bg_void,
        bgTop = 0xFF0B0410.toInt(), bgMid = 0xFF200A38.toInt(), bgBot = 0xFF3A1058.toInt(),
        weights = w(scout = 3, zigzag = 3, tank = 3, diver = 3, elite = 4, orbiter = 3, splitter = 3, shielded = 3, sniper = 3, kamikaze = 3, bomber = 3, warp = 3),
        swarmSeconds = 50f, hpScale = 5.0f, spawnScale = 0.52f,
        bossType = BOSS_NOVA, bossHp = 1800, bossName = "新星 · 超载核心"
    ),
    LevelConfig(
        index = 11,
        name = "第 12 关 · 虚空王座",
        subtitle = "最终决战 · 一切在此终结",
        bgRes = R.drawable.bg_void,
        bgTop = 0xFF07030C.toInt(), bgMid = 0xFF17062E.toInt(), bgBot = 0xFF2E0A4E.toInt(),
        weights = w(scout = 3, zigzag = 3, tank = 3, diver = 3, elite = 4, orbiter = 3, splitter = 3, shielded = 3, sniper = 4, kamikaze = 4, bomber = 4, warp = 4),
        swarmSeconds = 55f, hpScale = 6.0f, spawnScale = 0.48f,
        bossType = BOSS_NOVA, bossHp = 2400, bossName = "新星 · 虚空王座"
    )
)

internal fun levelAt(i: Int): LevelConfig =
    LEVELS[i.coerceIn(0, LEVELS.size - 1)]

// ============================================================
//  BOSS 配置
// ============================================================

internal class BossSpec(
    val type: Int,
    val name: String,
    val bodyColor: Int,
    val accentColor: Int,
    val phases: Int
)

internal val BOSS_SPECS: Array<BossSpec> = arrayOf(
    BossSpec(BOSS_CRIMSON, "赤红", 0xFFD32F2F.toInt(), 0xFFFFCDD2.toInt(), 3),
    BossSpec(BOSS_AZURE, "湛蓝", 0xFF1565C0.toInt(), 0xFFBBDEFB.toInt(), 3),
    BossSpec(BOSS_VOID, "虚空", 0xFF6A1B9A.toInt(), 0xFFE1BEE7.toInt(), 3),
    BossSpec(BOSS_OMEGA, "欧米伽", 0xFFEF6C00.toInt(), 0xFFFFE0B2.toInt(), 3),
    BossSpec(BOSS_NOVA, "新星", 0xFFC62828.toInt(), 0xFFFFCDD2.toInt(), 3)
)

internal fun bossSpec(type: Int): BossSpec =
    if (type in BOSS_SPECS.indices) BOSS_SPECS[type] else BOSS_SPECS[0]
