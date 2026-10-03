package com.example.airforce

/**
 * 游戏模式。战役为 12 关推进；无尽为生存挑战；BOSS 连战为连续讨伐。
 * 每日挑战（DailyChallenge）是独立的一套按日期随机规则。
 */

internal const val M_CAMPAIGN = 0     // 战役：12 关推进
internal const val M_ENDLESS = 1      // 无尽：难度随时间递增，无终点
internal const val M_BOSSRUSH = 2     // BOSS 连战：连续挑战多个 BOSS

internal class ModeSpec(
    val id: Int,
    val name: String,
    val shortName: String,
    val desc: String,
    val color: Int
)

internal val MODES: Array<ModeSpec> = arrayOf(
    ModeSpec(M_CAMPAIGN, "战役模式", "战役", "12 个关卡 · 击败最终 BOSS 通关", 0xFF29B6F6.toInt()),
    ModeSpec(M_ENDLESS, "无尽模式", "无尽", "难度持续上升 · 看你能撑多久", 0xFFE040FB.toInt()),
    ModeSpec(M_BOSSRUSH, "BOSS 连战", "连战", "连续挑战 5 个 BOSS · 中途回血", 0xFFFF5252.toInt())
)

internal fun modeSpec(id: Int): ModeSpec =
    if (id in MODES.indices) MODES[id] else MODES[0]
