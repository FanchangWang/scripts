package com.chess.bot.game

/** 帧分类纯函数（移植 python classifier.py，含 captured=r2_old 修正）。 */

/** 双方将/帥同时缺失（单帧疑似结束）；连续帧 streak 由控制层维护。 */
fun isResignSuspect(board: Board, mySide: Side): Boolean {
    val myGeneral = if (mySide == Side.RED) "r_K" else "b_k"
    val enemyGeneral = if (mySide == Side.RED) "b_k" else "r_K"
    val hasMine = board.any { row -> row.any { it == myGeneral } }
    val hasEnemy = board.any { row -> row.any { it == enemyGeneral } }
    return !hasMine && !hasEnemy
}

/**
 * 我方走棋后单帧分类，按变动格数 n 分派。
 * 只校验「走棋是否成功」或返回对应 SelfFrameResult（LIFTED/NOISY/SILENT）及 selfMove/enemyMove。
 * 不再有 myMoveSettled 兜底：SELF_DONE 的判定即「起点变空 + 落点成为我方棋子」，已直接覆盖；
 * 动画中尚未 settle 的敌方中途格不影响该判定，故兜底分支不可达（已与用户确认移除）。
 */
fun classifySelfFrame(
    changes: List<Change>,
    newBoard: Board,
    expected: Move,
    mySide: Side,
): SelfFrame {
    return when {
        changes.isEmpty() -> SelfFrame(SelfFrameResult.SILENT)

        changes.size == 1 -> {
            if (isLiftedOnly(changes[0], expected, newBoard)) {
                SelfFrame(SelfFrameResult.LIFTED)
            } else {
                SelfFrame(SelfFrameResult.NOISY)
            }
        }

        changes.size == 2 -> {
            inferMove(changes)?.let { moved ->
                if (moveMatches(moved, expected)) {
                    return SelfFrame(SelfFrameResult.SELF_DONE, selfMove = moved)
                }
            }
            enemyRecaptureN2(changes, newBoard, expected, mySide)?.let { enemy ->
                // 我方 captured = 落点原有棋子（若为敌子）
                val r2Old = changes.firstOrNull {
                    it.r == expected.dst.first && it.c == expected.dst.second
                }?.old
                val captured = r2Old?.takeIf { pieceColor(it) != mySide }
                val selfMove = Move(expected.src, expected.dst, expected.piece, captured)
                return SelfFrame(SelfFrameResult.SELF_THEN_ENEMY, selfMove, enemy)
            }
            SelfFrame(SelfFrameResult.NOISY)
        }

        changes.size == 3 -> classifyN3(changes, newBoard, expected, mySide)
            ?: SelfFrame(SelfFrameResult.NOISY)

        changes.size == 4 -> classifyN4(changes, expected, mySide)
            ?: SelfFrame(SelfFrameResult.NOISY)

        // n>4：无法归入上述任一模式 → NOISY（含双方将帅缺失的终局签名，由 verifyForSelfMove 尾部查结束画面）
        else -> SelfFrame(SelfFrameResult.NOISY)
    }
}

/** 敌方走棋检测单帧分类（返回 EnemyFrame data class，result 判断、enemyMove 取移动数据）。 */
fun classifyEnemyFrame(changes: List<Change>, mySide: Side): EnemyFrame {
    return when (changes.size) {
        0 -> EnemyFrame(EnemyFrameResult.SILENT)
        1 -> {
            val only = changes[0]
            if (only.old != null && only.new == null && pieceColor(only.old) != mySide) {
                EnemyFrame(EnemyFrameResult.LIFTED)
            } else {
                EnemyFrame(EnemyFrameResult.NOISY)
            }
        }

        2 -> inferMove(changes)?.let { EnemyFrame(EnemyFrameResult.MOVED, it) } ?: EnemyFrame(
            EnemyFrameResult.NOISY
        )

        else -> EnemyFrame(EnemyFrameResult.NOISY)
    }
}

// ---------- 内部 ----------

/** n==1 恰好是我方起点提子未落（强约束避免误判）。 */
private fun isLiftedOnly(change: Change, expected: Move, newBoard: Board): Boolean {
    if ((change.r to change.c) != expected.src) return false
    if (change.old != expected.piece || change.new != null) return false
    return newBoard[expected.dst.first][expected.dst.second] != expected.piece
}

/** n==2 兜底：我方起点空 + 另一格敌方起点空 + 终点是敌方棋（敌方同终点反吃）。 */
private fun enemyRecaptureN2(
    changes: List<Change>,
    newBoard: Board,
    expected: Move,
    mySide: Side,
): Move? {
    val lookup = changes.associate { (it.r to it.c) to (it.old to it.new) }
    val srcPair = lookup[expected.src] ?: return null
    val (srcOld, srcNew) = srcPair
    if (srcOld != expected.piece || srcNew != null) return null
    val other = changes.first { (it.r to it.c) != expected.src }
    val ep = other.old
    if (ep != null && other.new == null && pieceColor(ep) != mySide &&
        newBoard[expected.dst.first][expected.dst.second] == ep
    ) {
        return Move((other.r to other.c), expected.dst, ep, expected.piece)
    }
    return null
}

/** 从 3 格变动里找出不是预期起/终点的第三格。 */
private fun findThirdCell(changes: List<Change>, expected: Move): Change? =
    changes.firstOrNull { (it.r to it.c) != expected.src && (it.r to it.c) != expected.dst }

private fun classifyN3(
    changes: List<Change>,
    newBoard: Board,
    expected: Move,
    mySide: Side,
): SelfFrame? {
    val lookup = changes.associate { (it.r to it.c) to (it.old to it.new) }
    val srcPair = lookup[expected.src] ?: return null
    val dstPair = lookup[expected.dst] ?: return null
    val (r1Old, r1New) = srcPair
    val (r2Old, r2New) = dstPair
    val third = findThirdCell(changes, expected) ?: return null
    val xCell = third.r to third.c
    val piece = expected.piece
    val destCaptured = r2Old?.takeIf { pieceColor(it) != mySide }

    // 情况1：我方走棋成功（r1空 r2成piece）+ 第三格敌方棋消失（敌方他子提起未落/识别闪动）
    if (
        r1Old == piece && r1New == null && r2New == piece &&
        third.old != null && third.new == null && pieceColor(third.old) != mySide
    ) {
        return SelfFrame(
            SelfFrameResult.SELF_DONE,
            Move(expected.src, expected.dst, piece, destCaptured),
        )
    }

    // 情况2：我方被敌方在终点反吃（r1空 r2成敌方e）→ 第三格是 e 的原格。
    // 我方 captured = 落子前该格内容 r2_old；e_piece 是反吃方，不属于我方这步。
    if (r1Old == piece && r1New == null && r2New != null && pieceColor(r2New) != mySide) {
        val ePiece = r2New
        if (third.old == ePiece && third.new == null) {
            val selfMove = Move(expected.src, expected.dst, piece, destCaptured)
            val enemyMove = Move(xCell, expected.dst, ePiece, piece)
            return SelfFrame(SelfFrameResult.SELF_THEN_ENEMY, selfMove, enemyMove)
        }
    }

    // 情况3：我方 r1→r2 成功，敌方另一子 x→r1 占我原位（敌方不吃子）
    if (r1New != null && pieceColor(r1New) != mySide) {
        val ePiece = r1New
        if (r1Old == piece && r2New == piece && third.old == ePiece && third.new == null) {
            val selfMove = Move(expected.src, expected.dst, piece, destCaptured)
            val enemyMove = Move(xCell, expected.src, ePiece, null)
            return SelfFrame(SelfFrameResult.SELF_THEN_ENEMY, selfMove, enemyMove)
        }
    }
    return null
}

private fun classifyN4(changes: List<Change>, expected: Move, mySide: Side): SelfFrame? {
    val lookup = changes.associate { (it.r to it.c) to (it.old to it.new) }
    val srcPair = lookup[expected.src] ?: return null
    val dstPair = lookup[expected.dst] ?: return null
    val (_, r1New) = srcPair
    val (r2Old, r2New) = dstPair
    if (!(srcPair.first == expected.piece && r1New == null && r2New == expected.piece)) return null
    val rest = changes.filter { (it.r to it.c) != expected.src && (it.r to it.c) != expected.dst }
    if (rest.size != 2) return null
    val enemyMove = inferMove(rest) ?: return null
    val captured = r2Old?.takeIf { pieceColor(it) != mySide }
    val selfMove = Move(expected.src, expected.dst, expected.piece, captured)
    return SelfFrame(SelfFrameResult.SELF_THEN_ENEMY, selfMove, enemyMove)
}
