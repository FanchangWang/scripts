package com.chess.bot.game

/**
 * 落点四态探测（2026-09-13 用户批复的 P1/D2/D5 共用件）。
 *
 * 背景（真机 h6h4 卡死事故，log.txt 13064→13865）：我方走子 `h6→h4` 后，落点 h4 被敌方
 * `g6→g3` 的动画帧干扰，cls 只读到 `红炮[0.88]`（< CLS_TRUST_MIN=0.95）→ 落点未确认 → 分类器判
 * LIFTED → RETRY_DST 补点 h4，而这一击**把已经落定的红炮重新提了起来** → 此后 h4 读数彻底消失
 * （"空→lift" 又被扫描层当飞行伪影剔除）→ 零变化死循环 39.7s。
 *
 * 区分「已落定但置信度低」与「被误提起悬空」是本题的关键，判据只有一个来源：
 *   - 格中心读数 `Recognizer.analyzeCellEx(corrected, r, c).first`（**原始 top1，不过滤置信度**）
 *   - 向上扫描 `Recognizer.identifyLiftedPiece(corrected, r, c, abovePiece)`（0.5 档多数票）
 *
 * ⚠️ 已提起的棋子**无法主动落子**（2026-09-13 用户纠正）：使其回位只有两条路——点它的目标位置
 * （= 完成一次移动）、或点另一个我方棋子（App 令其落回）。故探测结论只用于**判定与提交**，
 * **绝不**据此点击该格（点 dst 只会把悬空子移过去、或把已落定的子重新提起）。
 */

/** 落点状态（[probeLandingState] 的返回值）。 */
enum class LandingState {
    /** 子已落定在格内（**哪怕格中心读数只有 0.88**——调用方传入前不得做置信度过滤）。 */
    LANDED,

    /** 子被提起、悬在格子上方：格中心空（或 lift）且向上扫描命中该子。 */
    LIFTED_ABOVE,

    /** 该格探不到我方这颗子（空且向上也扫不到；或读到我方另一颗子——异常兜底）。 */
    ABSENT,

    /** 格中心是**敌方子**：我方这颗子已被吃，走敌方链路（本探测不涉及）。 */
    CAPTURED,
}

/**
 * 落点四态判定（纯函数，JVM 可测）。
 *
 * 判定次序（先格中心、后向上扫描；CAPTURED 优先于 LIFTED_ABOVE）：
 * 1. `centerRead == expectedPiece` → [LandingState.LANDED]
 * 2. `centerRead` 为敌方子（非空、非 lift、颜色 != `mySide`）→ [LandingState.CAPTURED]
 * 3. `liftedRead == expectedPiece` → [LandingState.LIFTED_ABOVE]
 * 4. 其余 → [LandingState.ABSENT]
 *
 * @param centerRead  格中心 cls 原始 top1（`analyzeCellEx(...).first`）；空为 null、提起为 [Const.LIFT]。
 *                    **不要**预先按 `CLS_TRUST_MIN` 过滤——0.88 的读数恰恰是本探测要利用的证据。
 * @param liftedRead  `identifyLiftedPiece(corrected, r, c, abovePiece)` 的多数票结果（0.5 档）；
 *                    仅在格中心读不到该子时才有判定价值，未跑可传 null。
 * @param expectedPiece 本步着法落点应有的棋子 ID（如 "r_C"）。
 * @param mySide      我方阵营（判 CAPTURED 用）。
 */
fun probeLandingState(
    centerRead: String?,
    liftedRead: String?,
    expectedPiece: String,
    mySide: Side,
): LandingState {
    if (centerRead == expectedPiece) return LandingState.LANDED
    val enemyOccupied =
        centerRead != null && centerRead != Const.LIFT && pieceColor(centerRead) != mySide
    if (enemyOccupied) return LandingState.CAPTURED
    if (liftedRead == expectedPiece) return LandingState.LIFTED_ABOVE
    return LandingState.ABSENT
}
