package com.chess.bot.engine

import com.chess.bot.game.Const

/**
 * UCI info 行解析结果（immutable：drain 线程每次解析产出新实例整体赋值 currentInfo，
 * @Volatile 只保证引用可见性，绝不修改旧对象）。
 *
 * bound 行（lowerbound/upperbound）的 depth/seldepth/nodes 仍然有效、字段全保留；
 * hasBound 仅用于「bound 行的 mate 是『至少 N 步』边界值，不可当精确 mate 外泄」——
 * 属 mate 相关判断，保留；不再参与任何质量/可靠性判定。
 */
data class EngineInfo(
    val depth: Int,
    val seldepth: Int,
    /** cp 或 mate 换算后的分数（正=当前行棋方占优；对齐旧 parseScore 口径）。 */
    val scoreCp: Int,
    /** score mate N（正=当前行棋方 N 步内将死；负=被将死；null=非将死）。 */
    val matePly: Int?,
    val nodes: Long,
    val timeMs: Int,
    /** lowerbound/upperbound 标记：分数是边界值而非精确分。 */
    val hasBound: Boolean,
    /** pv 首着（≈将出的 bestmove，仅观测告警用）。 */
    val pvFirst: String?,
    /** 残局库命中（本项目未加载 Syzygy 恒 0；解析保留供后续迭代使用）。 */
    val tbHits: Long = 0,
)

/**
 * 引擎 info 纯函数集（解析 / 最终 info 选取 / 硬顶）。
 * 全部无 Android 依赖，JVM 单测直接覆盖。
 *
 * 2026-09-11 质量检测重做（R4）：自研伪影 A1/A2、五维 qualityOk、动态 time 阈值全部删除——
 * 皮卡鱼 `go movetime` 到点自交 bestmove，App 不再二次判断「搜透没搜透」。
 * 保留：解析、选最深行、硬顶兜底。（nearMate 早停已随 R5 删除。）
 */
object EngineInfoPick {

    // ---------- 解析 ----------

    /**
     * 解析单条 info 行；返回 null = 非决策相关行，直接丢弃：
     * - 非 "info" 前缀 / "info string ..." 行；
     * - currmove 进度行（无 score 且无 pv）；
     * - 缺 depth/seldepth/nodes/time 任一关键字段（无法参与展示与最终选取）；
     * - multipv ≠ 1 的行（未开 MultiPV；未来开启时防解析混乱，只取 multipv 1）。
     */
    fun parseInfoLine(line: String): EngineInfo? {
        if (!line.startsWith("info")) return null
        val tokens = line.split(" ").filter { it.isNotEmpty() }
        if (tokens.size < 2) return null
        if (tokens[1] == "string") return null
        var depth = -1
        var seldepth = -1
        var nodes = -1L
        var timeMs = -1
        var multipv = 1
        var scoreCp = 0
        var matePly: Int? = null
        var hasBound = false
        var pvFirst: String? = null
        var tbHits = 0L
        var hasScore = false
        var i = 1
        while (i < tokens.size) {
            when (tokens[i]) {
                "depth" -> depth = tokens.getOrNull(i + 1)?.toIntOrNull() ?: -1
                "seldepth" -> seldepth = tokens.getOrNull(i + 1)?.toIntOrNull() ?: -1
                "nodes" -> nodes = tokens.getOrNull(i + 1)?.toLongOrNull() ?: -1L
                "time" -> timeMs = tokens.getOrNull(i + 1)?.toIntOrNull() ?: -1
                "multipv" -> multipv = tokens.getOrNull(i + 1)?.toIntOrNull() ?: 0
                "lowerbound", "upperbound" -> hasBound = true
                "tbhits" -> tbHits = tokens.getOrNull(i + 1)?.toLongOrNull() ?: 0L
                "score" -> {
                    val kind = tokens.getOrNull(i + 1)
                    val value = tokens.getOrNull(i + 2)?.toIntOrNull()
                    if (kind != null && value != null) {
                        hasScore = true
                        when (kind) {
                            "cp" -> scoreCp = value
                            "mate" -> {
                                matePly = value
                                scoreCp = if (value > 0) 100000 - value else -100000 - value
                            }
                        }
                    }
                }

                "pv" -> pvFirst = tokens.getOrNull(i + 1)
            }
            i++
        }
        if (depth < 0 || seldepth < 0 || nodes < 0 || timeMs < 0) return null
        if (multipv != 1) return null
        if (!hasScore && pvFirst == null) return null // currmove 进度行
        return EngineInfo(
            depth,
            seldepth,
            scoreCp,
            matePly,
            nodes,
            timeMs,
            hasBound,
            pvFirst,
            tbHits
        )
    }

    // ---------- 最终 info 选取（R4：不做任何可信度判断） ----------

    /** 从引擎输出行中选取最终 info：取 depth 最深者（并列取更晚者）。无有效行 → null。 */
    fun pickFinalInfo(lines: List<String>): EngineInfo? {
        val infos = lines.mapNotNull { parseInfoLine(it) }
        if (infos.isEmpty()) return null
        // 取最深者；depth 并列取更晚者（迭代深化的最新读数）——maxByOrNull 平 tie 返回首个，需显式 fold
        return infos.fold(infos.first()) { acc, e -> if (e.depth >= acc.depth) e else acc }
    }

    // ---------- 硬顶（R6：追加式） ----------

    /** 硬顶 = TARGET + ENGINE_HARD_CAP_APPEND_MS（R6）。 */
    fun hardCapMs(targetMs: Int): Int = targetMs + Const.ENGINE_HARD_CAP_APPEND_MS
}
