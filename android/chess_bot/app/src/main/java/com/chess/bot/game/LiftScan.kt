package com.chess.bot.game

/**
 * 提子身份识别的候选选择（2026-09-08 D1-A 多数票加固，纯函数便于 JVM 单测）。
 *
 * 背景：向上逐 dy 扫描时，若正上方格有棋子且其格型恰好被部分裁入，个别档位会以
 * 高置信读出上格棋子；若上格棋子的 analyzeBoard 读取失误（abovePiece 为 null/误判），
 * 原「单取最高置信」策略可能被该孤例污染。真机实测（2026-09-08 01:40/01:50 两例）
 * 有效识别带内 5 档读数完全一致——多数票天然选中真实棋子，单档孤例被免疫。
 *
 * 规则：候选按「出现档数降序 → 同票数取最高置信降序」选择；
 * abovePiece 排除保留（候选存在其他棋子时剔除上格棋子档）。
 *
 * @param candidates 有效档位候选（pieceId -> top1Prob），已过滤 lift 与低置信
 * @param abovePiece 正上方格棋子 id（analyzeBoard 读取；可为 null）
 * @return 选中的棋子 id；候选为空返回 null
 */
fun pickLiftedCandidate(
    candidates: List<Pair<String, Float>>,
    abovePiece: String?,
): String? {
    if (candidates.isEmpty()) return null
    val filtered = if (abovePiece != null && candidates.any { it.first != abovePiece }) {
        candidates.filter { it.first != abovePiece }
    } else {
        candidates
    }
    if (filtered.isEmpty()) return null
    return filtered
        .groupBy { it.first }
        .maxWithOrNull(
            compareBy(
                { (_, v) -> v.size }, // 多数票优先
                { (_, v) -> v.maxOf { it.second } }, // 同票数比最高置信
            ),
        )
        ?.key
}
