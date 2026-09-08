package com.chess.bot.engine

import com.chess.bot.game.Const
import kotlin.math.abs
import kotlin.math.max

/**
 * UCI info 行解析结果（immutable：drain 线程每次解析产出新实例整体赋值 currentInfo，
 * @Volatile 只保证引用可见性，绝不修改旧对象）。
 *
 * bound 行（lowerbound/upperbound）的 depth/seldepth/nodes 仍然有效、字段全保留；
 * 仅 score 不可靠、不参与质量达标判定（2026-09-08 方案 §2.1 / R7）。
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

/** pickFinalInfo 结果：scoreUnreliable=true 表示所有候选 info 均带 bound，分数不可作为决策依据。 */
data class PickedInfo(val info: EngineInfo, val scoreUnreliable: Boolean)

/**
 * 引擎 info 纯函数集（解析 / 伪影判定 / 质量门控 / 最终 info 选取 / 硬顶）。
 * 全部无 Android 依赖，JVM 单测直接覆盖（InfoParserTest 以四条实测行为回归用例）。
 */
object EngineInfoPick {

    // ---------- 解析 ----------

    /**
     * 解析单条 info 行；返回 null = 非决策相关行，直接丢弃：
     * - 非 "info" 前缀 / "info string ..." 行；
     * - currmove 进度行（无 score 且无 pv）；
     * - 缺 depth/seldepth/nodes/time 任一关键侍段（无法参与门控）；
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
        return EngineInfo(depth, seldepth, scoreCp, matePly, nodes, timeMs, hasBound, pvFirst, tbHits)
    }

    // ---------- 伪影判定（方案 §3.3：命中任一条即整行丢弃，不更新 currentInfo） ----------

    /** A1 的动态 time 阈值：快棋（TARGET 小）时避免把合法行全滤掉。 */
    fun minValidInfoMs(targetMs: Int): Int = max(Const.ENGINE_MIN_VALID_INFO_MS, targetMs / 10)

    /**
     * 伪影双规则（仅落 DEBUG 日志，不进 currentInfo、不参与门控与最终分）：
     * - A1：time < max(100, TARGET×10%) —— 搜索初期的 TT 注水行；
     * - A2：depth ≥ ENGINE_ARTIFACT_MIN_DEPTH(20) 且 depth−seldepth > 15 且 nodes < 10000
     *   —— 搜索中途大量命中 TT 的伪影（计算量配不上宣称的深度）；
     *   仅深度差大但 nodes 正常的是合法 TT 截断/窗口回退，一律保留。
     *
     * @return 命中的规则名（"A1"/"A2"）；null = 非伪影。D6 采样按此标注每行判定依据。
     */
    fun artifactReason(info: EngineInfo, targetMs: Int): String? {
        if (info.timeMs < minValidInfoMs(targetMs)) return "A1"
        return if (info.depth >= Const.ENGINE_ARTIFACT_MIN_DEPTH &&
            info.depth - info.seldepth > Const.ENGINE_ARTIFACT_DEPTH_GAP &&
            info.nodes < Const.ENGINE_MIN_VALID_NODES
        ) "A2" else null
    }

    fun isArtifact(info: EngineInfo, targetMs: Int): Boolean =
        artifactReason(info, targetMs) != null

    // ---------- 质量门控（方案 §3.2：联合判定，全部同时满足才达标） ----------

    /** TARGET < 500ms 时 depth 门槛 8→6 兜底（v3 R14：短时限复杂中局到硬顶也搜不到 8 层）。 */
    private fun depthMin(targetMs: Int): Int =
        if (targetMs < Const.ENGINE_FAST_TARGET_MS) Const.ENGINE_DEPTH_MIN_FAST
        else Const.ENGINE_DEPTH_MIN

    /**
     * 联合质量门控：time/nodes/depth/seldepth/无 bound 五维交叉校验。
     * 例外：精确 mate（非 bound）出现即视为达标前提成立（将杀线上 seldepth 无意义）；
     * bound 态 mate 不享受该例外，按常规门控走（v3 R13）。
     */
    fun qualityOk(info: EngineInfo, targetMs: Int): Boolean {
        if (info.matePly != null && !info.hasBound) return true
        return info.timeMs >= minValidInfoMs(targetMs) &&
                info.nodes >= Const.ENGINE_MIN_VALID_NODES &&
                info.depth >= depthMin(targetMs) &&
                info.seldepth >= Const.ENGINE_SELDEPTH_MIN &&
                !info.hasBound
    }

    // ---------- 最终 info 选取（方案 §3.4 / R4） ----------

    /**
     * 从引擎输出行中选取最终 info：
     * 1. 解析全部有效行并剔除伪影（isArtifact）；
     * 2. 优先取「depth 最深的无 bound 行」（depth 并列取更晚者——迭代深化的最新值）；
     * 3. 全部带 bound → 回退取「depth 最深的 bound 行」并标记 scoreUnreliable；
     * 4. 无任何有效行 → null。
     */
    fun pickFinalInfo(lines: List<String>, targetMs: Int): PickedInfo? {
        val infos = lines.mapNotNull { parseInfoLine(it) }.filter { !isArtifact(it, targetMs) }
        if (infos.isEmpty()) return null
        val precise = infos.filter { !it.hasBound }
        val pool = precise.ifEmpty { infos }
        // 取最深者；depth 并列取更晚者（迭代深化的最新读数）——maxByOrNull 平 tie 返回首个，需显式 fold
        val best = pool.fold(pool.first()) { acc, e -> if (e.depth >= acc.depth) e else acc }
        return PickedInfo(best, precise.isEmpty())
    }

    // ---------- 硬顶（方案 §3.5 / R3：分段倍率 + 追加式绝对上限） ----------

    /**
     * 硬顶 = 分段倍率（≤1s×3 / 1–5s×2 / >5s×1.5）再 clamp 到 TARGET+5000ms。
     * 追加上限保证短时限防拖长、长时限（TARGET 本身 >5s 是用户主动设置）不被截断。
     */
    fun hardCapMs(targetMs: Int): Int {
        val segmented = when {
            targetMs <= 1_000 -> targetMs * 3
            targetMs <= 5_000 -> targetMs * 2
            else -> targetMs * 3 / 2
        }
        return minOf(segmented, targetMs + Const.ENGINE_HARD_CAP_APPEND_MS)
    }

    /** 近杀提前停判定：精确 mate（非 bound）且 |N| ≤ 阈值（v3 R13）。 */
    fun nearMate(info: EngineInfo?): Boolean =
        info != null && info.matePly != null && !info.hasBound &&
                abs(info.matePly) <= Const.ENGINE_MATE_STOP_PLY
}
