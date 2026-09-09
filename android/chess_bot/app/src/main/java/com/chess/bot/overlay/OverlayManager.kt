package com.chess.bot.overlay

import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import com.chess.bot.MainActivity
import com.chess.bot.data.BoardCornersStore
import com.chess.bot.data.BotSettings
import com.chess.bot.game.Board
import com.chess.bot.game.BotStatus
import com.chess.bot.game.MoveSource
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogLevel
import com.chess.bot.log.LogTag
import com.chess.bot.service.BotForegroundService
import com.chess.bot.ui.theme.ChessBotTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 跨悬浮窗/后续 BotSession 共享的运行态。 */
object BotRuntime {
    /** 自动对弈是否运行中。 */
    val running = MutableStateFlow(false)

    /** 对弈模式是否运行（对弈控制条已弹出）；校准仅持截屏管线时不置位，主界面「对弈」按钮据此显示。 */
    val playActive = MutableStateFlow(false)

    /** 自动下一局开关（默认开，DataStore 持久化）。 */
    val autoNext = MutableStateFlow(true)

    /** 悬浮窗状态行：阶段 / 阵营 / 运行状态。 */
    val statusLine = MutableStateFlow("未同步 · 未运行")

    /** 最近一次引擎评估分（我方视角，正=我方占优）；悬浮窗据此给「评估」段着色。 */
    val evalScore = MutableStateFlow(0)

    /** 信息框第四行评估文本（2026-09-09：mate 优先「绝杀 N/被绝杀 N」，否则「+N/N」，盲区「评估 -」）；着色仍用 evalScore。 */
    val evalText = MutableStateFlow("-")

    /** 状态机阶段（12 态互斥单值，2026-08-28 审计 §一.5~7）。 */
    val status = MutableStateFlow(BotStatus.PAUSED)

    /** 最近一步着法来源（引擎 / 📖 开局库）。 */
    val moveSource = MutableStateFlow(MoveSource.ENGINE)

    /** 最近一步引擎思考层数（开局库命中时无意义，显示 vscore/胜率替代）。 */
    val moveDepth = MutableStateFlow(0)

    /** 开局库命中时的胜率（0..1）；引擎着法时为 0。 */
    val bookWinRate = MutableStateFlow(0f)

    /** 最近一步着法（ICCS，如 h2e2）。 */
    val lastMoveIccs = MutableStateFlow<String?>(null)

    /** 棋盘快照（防御性拷贝）：棋盘小窗 Canvas 直绘数据源。 */
    val board = MutableStateFlow<Board?>(null)

    /** 我方最近一步起止格（网格坐标）；null=我方尚未走子。 */
    val lastSelfMoveCells = MutableStateFlow<Pair<Pair<Int, Int>, Pair<Int, Int>>?>(null)

    /** 我方箭头阶段：true=走棋前(圈标目标TO) / false=走棋后(圈标起点FROM)。 */
    val lastSelfMovePlanned = MutableStateFlow(false)

    /** 敌方最近一步起止格（网格坐标）；null=敌方尚未走子。 */
    val lastEnemyMoveCells = MutableStateFlow<Pair<Pair<Int, Int>, Pair<Int, Int>>?>(null)

    /** bot 执子方是否为红方（决定「我方箭头 / 敌方箭头」的圈色与落点）。 */
    val mySideIsRed = MutableStateFlow(true)

    /** 棋盘小窗显示开关（唯一出口=操控条「棋盘」，窗体自身不可隐藏）。 */
    val boardWindowShown = MutableStateFlow(false)

    /**
     * 信息框显示开关（2026-09-07 新增，唯一出口=操控条「信息框」按钮）。
     * **不持久化**：每次从主页「开始对弈」弹出悬浮窗（ensureShown）都复位为 true，
     * 仅允许本次对弈期间临时隐藏。
     */
    val infoShown = MutableStateFlow(true)

    /** 等待摆棋态信息（悬浮窗等待态展示）：已进入等待的秒数。 */
    val waitElapsedS = MutableStateFlow(0)

    /** 等待摆棋态摘要：如「子数 26 · 稳定 1/3」「子数 31（提子过渡）」，非等待态为空串。 */
    val waitDetail = MutableStateFlow("")
}

/**
 * 悬浮窗总管：操控条 + 信息框（两个独立悬浮窗）+ 棋盘小窗。
 * - 操控条 / 信息框各自独立创建、独立拖动、互不干扰（解决 #7 抖动）。
 * - 操控条常驻右缘（BOTTOM|END，仅上下拖）；信息框默认贴右缘、顶边与棋盘小窗一致
 *   （TOP|END，2026-09-07 起自由拖动、显隐由操控条「信息框」按钮控制，不持久化）。
 * - 生命周期跟随前台服务（日志仅落文件，无悬浮窗）。
 */
object OverlayManager {

    private var appContext: Context? = null
    private var controlHost: OverlayHost? = null
    private var infoHost: OverlayHost? = null
    private var boardHost: OverlayHost? = null
    private var settings: BotSettings? = null

    /** 操控条记忆位置（像素，BOTTOM|END 语义：x=右缘边距，y=底缘边距）。 */
    private var ctrlX = 8
    private var ctrlY = 180

    /** 信息框记忆位置（TOP|END 语义：x=右缘边距、y=顶缘边距；默认贴右缘、与棋盘小窗同 y）。 */
    private var infoX = 0
    private var infoY = -1
    private var boardX = -1
    private var boardY = -1

    /** 初始（用户未手动移动）定位基准：棋盘右下角 y；null=未校准/不可用 → 退回固定默认。 */
    private var boardCornerY: Double? = null

    /** 操控条 y 是否由「未移动默认」推导（首帧按棋盘角自定位，拖动后置否）。信息框不再需要（默认位=棋盘小窗顶边）。 */
    private var ctrlYAuto = false

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val botExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "bot-worker").apply { isDaemon = true }
        }
    private val botScope = CoroutineScope(
        SupervisorJob() + botExecutor.asCoroutineDispatcher() + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            LogBus.log(
                LogLevel.ERROR,
                LogTag.SYSTEM,
                "后台任务未捕获异常：${e::class.java.simpleName}: ${e.message}"
            )
            LogBus.log(LogLevel.ERROR, LogTag.SYSTEM, "后台任务未捕获异常：${e.message}")
        },
    )

    /** ⌂ 返回 App 确认文案；null=正常态（3s 超时自动还原）。 */
    private val exitPrompt = androidx.compose.runtime.mutableStateOf<String?>(null)
    private var exitPromptJob: kotlinx.coroutines.Job? = null

    private var session: com.chess.bot.game.BotSession? = null

    fun ensureSession(context: Context): com.chess.bot.game.BotSession =
        session ?: synchronized(this) {
            session ?: com.chess.bot.game.BotSession(context.applicationContext)
                .also { session = it }
        }

    /** 显示（或恢复显示）悬浮窗；幂等。操控条(右) + 信息框(左) 常驻同显，互不切换。 */
    suspend fun ensureShown(context: Context) {
        val ctx = context.applicationContext
        if (appContext == null) {
            appContext = ctx
            val s = BotSettings(ctx).also { settings = it }
            ctrlX = s.overlayControlX.first().let { if (it < 0) 8 else it }
            val rawCtrlY = s.overlayControlY.first()
            ctrlY = if (rawCtrlY < 0) 180 else rawCtrlY
            ctrlYAuto = rawCtrlY < 0
            // 信息框位置（TOP|END：x=右缘边距、y=顶缘边距）；默认贴右缘、顶边与棋盘小窗一致。
            // 2026-09-07 锚点从 BOTTOM|START 改为 TOP|END，旧键 overlay_info_x/y 语义不兼容已弃用。
            infoX = s.overlayInfo2X.first().let { if (it < 0) 0 else it }
            val rawInfoY = s.overlayInfo2Y.first()
            infoY = if (rawInfoY < 0) statusBarHeightPx() + 5 else rawInfoY
            boardX = s.overlayBoardX.first()
            boardY = s.overlayBoardY.first()
            // 棋盘右下角 y（四角中 x+y 最大者）作初始悬浮窗定位基准；未校准→null→退回固定默认
            val dm = ctx.resources.displayMetrics
            boardCornerY = BoardCornersStore.get(dm.widthPixels, dm.heightPixels, ctx)
                ?.maxByOrNull { it.first + it.second }?.second
            // 棋盘绘制总开关默认值
            BotRuntime.boardWindowShown.value = s.boardDrawEnabled.first()
            uiScope.launch { BotRuntime.autoNext.value = s.autoNextEnabled.first() }
            uiScope.launch {
                BotRuntime.boardWindowShown.collect { shown ->
                    if (shown) showBoardWindow() else dismissBoardWindow()
                }
            }
            // 信息框显隐跟随开关（不持久化，见 BotRuntime.infoShown 注释）
            uiScope.launch {
                BotRuntime.infoShown.collect { shown ->
                    if (shown) ensureInfoHost() else {
                        infoHost?.dismiss()
                        infoHost = null
                    }
                }
            }
        }
        // 每次从主页「开始对弈」弹出悬浮窗：信息框默认打开（仅本次对弈内可临时隐藏）
        BotRuntime.infoShown.value = true
        // 常驻双窗：操控条(右) + 信息框(右) 同时显示，互不切换（主线程创建窗）
        uiScope.launch { ensureControlHost() }
        uiScope.launch { if (BotRuntime.infoShown.value) ensureInfoHost() }
        // 重启后同步棋盘小窗（主线程）
        uiScope.launch { if (BotRuntime.boardWindowShown.value) showBoardWindow() else dismissBoardWindow() }
        // 弹出即自动开局（保留 ⌂ 返回后手动开始路径）
        if (!BotRuntime.running.value) {
            LogBus.log(LogLevel.INFO, LogTag.PLAY, "悬浮窗已弹出，自动开始棋局")
            scopeLaunchStart(ctx)
        }
    }

    fun dismissAll() {
        controlHost?.dismiss()
        controlHost = null
        infoHost?.dismiss()
        infoHost = null
        boardHost?.dismiss()
        boardHost = null
    }

    /** 彻底关停：中断会话、关闭引擎子进程、清空引用（前台服务销毁时调用）。 */
    fun shutdown() {
        session?.interrupt()
        session?.close()
        session = null
        dismissAll()
    }

    /**
     * 恢复默认设置（2026-09-07 设置页「恢复默认」）：清空悬浮窗位置记忆与运行态开关。
     * DataStore 已由 BotSettings.resetAll() 清除（校准数据不在其中）；这里同步内存态，
     * 使下次弹窗按默认位创建（信息框 infoY=-1 哨兵 → ensureInfoHost 实时解析状态栏+5）。
     */
    fun resetToDefaults() {
        ctrlX = 8
        ctrlY = 180
        ctrlYAuto = true
        infoX = 0
        infoY = -1
        boardX = -1
        boardY = -1
        BotRuntime.autoNext.value = true
        BotRuntime.boardWindowShown.value = true
        BotRuntime.infoShown.value = true
    }

    // ---------- 操控条 / 信息框 ----------

    private fun ensureControlHost() {
        if (controlHost?.isShowing == true) return
        val ctx = appContext ?: return
        OverlayHost(ctx).also { controlHost = it }.show(
            layout = {
                gravity = Gravity.BOTTOM or Gravity.END
                width = WindowManager.LayoutParams.WRAP_CONTENT
                x = ctrlX
                y = ctrlY
            },
        ) { controlContent() }
        // 初始未移动且有校准：首帧布局后按「棋盘右下角 y + 100px」自定位，避免遮挡棋子
        // （postLayout 回调异步执行，前置捕获快照值，回调期不依赖可变成员）
        val cornerY = boardCornerY
        if (ctrlYAuto && cornerY != null) {
            controlHost?.postLayout { _, h ->
                ctrlY = belowBoardTopMargin(cornerY + 100.0, h)
                controlHost?.updateLayout { y = ctrlY }
            }
        }
    }

    private fun ensureInfoHost() {
        if (infoHost?.isShowing == true) return
        val ctx = appContext ?: return
        // 信息框默认位支持哨兵：infoY<0 = 未定位（恢复默认后），按「状态栏+5」实时解析
        if (infoY < 0) infoY = statusBarHeightPx() + 5
        OverlayHost(ctx).also { infoHost = it }.show(
            layout = {
                // 2026-09-07：TOP|END 锚点，默认贴屏幕右缘（x=0）、顶边与棋盘小窗初始 y 一致
                gravity = Gravity.TOP or Gravity.END
                width = WindowManager.LayoutParams.WRAP_CONTENT
                x = infoX
                y = infoY
            },
        ) { infoContent() }
    }

    /**
     * 初始定位换算：让窗口顶边落在「棋盘右下角 y + 100px」之下。
     * 窗口以 BOTTOM|END 锚定（y=底缘边距），故底缘边距 = 屏高 − 目标顶边 y − 窗口高。
     * 用户未手动移动时整体位于棋盘下方，不遮挡棋子；无校准数据/窗口未布局时退回 0（沿用调用方原值）。
     */
    private fun belowBoardTopMargin(targetTopY: Double, windowHeightPx: Int): Int {
        val screenH = appContext?.resources?.displayMetrics?.heightPixels ?: return 0
        return (screenH - targetTopY - windowHeightPx).roundToInt().coerceAtLeast(0)
    }

    @Composable
    private fun controlContent() {
        ChessBotTheme {
            val dark = isSystemInDarkTheme()
            val running by BotRuntime.running.collectAsState()
            val autoNext by BotRuntime.autoNext.collectAsState()
            val boardShown by BotRuntime.boardWindowShown.collectAsState()
            val infoShown by BotRuntime.infoShown.collectAsState()
            ControlBarContent(
                dark = dark,
                running = running,
                autoNext = autoNext,
                boardShown = boardShown,
                infoShown = infoShown,
                exitPrompt = exitPrompt.value,
                onStartStop = ::onStartStop,
                onAutoNextChange = ::onAutoNextChange,
                onBoardToggle = ::onBoardToggle,
                onInfoToggle = ::onInfoToggle,
                onRequestClose = ::onRequestClose,
                onConfirmExit = ::onConfirmExit,
                onCancelExit = ::onCancelExit,
                onDragY = { dragControl(it) },
                onDragEnd = { persistControl() },
            )
        }
    }

    @Composable
    private fun infoContent() {
        ChessBotTheme {
            val dark = isSystemInDarkTheme()
            val running by BotRuntime.running.collectAsState()
            val status by BotRuntime.status.collectAsState()
            val evalScore by BotRuntime.evalScore.collectAsState()
            val evalText by BotRuntime.evalText.collectAsState()
            val moveSource by BotRuntime.moveSource.collectAsState()
            val moveDepth by BotRuntime.moveDepth.collectAsState()
            val lastMoveIccs by BotRuntime.lastMoveIccs.collectAsState()
            InfoBoxMini(
                dark = dark,
                running = running,
                status = status,
                evalScore = evalScore,
                evalText = evalText,
                moveSource = moveSource,
                moveDepth = moveDepth,
                lastMoveIccs = lastMoveIccs,
                onDrag = { dx, dy -> dragInfo(dx, dy) },
                onDragEnd = { persistInfo() },
            )
        }
    }

    // ---------- 棋盘小窗 ----------

    /** 棋盘小窗：无标题栏、整窗拖动、不可隐藏（显示开关唯一出口=操控条「棋盘」）。
     *  动态尺寸：高度 = (棋盘上方两角 y − 状态栏高) × 0.7，9:10 比例；位置 TOP|START、x=10、y=状态栏+5。 */
    private suspend fun showBoardWindow() {
        val ctx = appContext ?: return
        if (boardHost?.isShowing == true) return
        val density = ctx.resources.displayMetrics.density
        val topEdgeY = boardTopEdgeY()
        val statusBar = statusBarHeightPx()
        val padDp = 8f
        val cellDp: Float = if (topEdgeY != null) {
            val avail = (topEdgeY - statusBar).coerceAtLeast(80.0)
            val heightPx = (avail * 0.7).coerceAtLeast(120.0)
            val padPx = padDp * density
            val cellPx = (heightPx - 2 * padPx) / 10.0
            (cellPx / density).toFloat().coerceAtLeast(8f)
        } else {
            24f
        }
        // 2026-09-07：默认贴屏幕左缘 x=0（用户未手动移动过时），y 仍=状态栏+5
        val bx = if (boardX >= 0) boardX else 0
        val by = if (boardY >= 0) boardY else statusBar + 5
        boardX = bx
        boardY = by
        OverlayHost(ctx).also { boardHost = it }.show(
            layout = {
                gravity = Gravity.TOP or Gravity.START
                x = bx
                y = by
            },
        ) {
            val board by BotRuntime.board.collectAsState()
            val selfMoveCells by BotRuntime.lastSelfMoveCells.collectAsState()
            val enemyMoveCells by BotRuntime.lastEnemyMoveCells.collectAsState()
            val mySideIsRed by BotRuntime.mySideIsRed.collectAsState()
            val selfPlanned by BotRuntime.lastSelfMovePlanned.collectAsState()
            BoardWindowContent(
                board = board,
                selfMoveCells = selfMoveCells,
                enemyMoveCells = enemyMoveCells,
                mySideIsRed = mySideIsRed,
                selfPlanned = selfPlanned,
                cellDp = cellDp.dp,
                padDp = padDp.dp,
                onDrag = { dx, dy -> dragBoard(dx, dy) },
                onDragEnd = { persistBoard() },
            )
        }
        LogBus.log(
            LogLevel.INFO,
            LogTag.SYSTEM,
            "棋盘小窗已显示（cell=${cellDp.toInt()}dp, 位置 $bx,$by）"
        )
    }

    /** 棋盘上方两角 y 均值（屏幕像素）；无校准返回 null。 */
    private fun boardTopEdgeY(): Double? {
        val ctx = appContext ?: return null
        val w = ctx.resources.displayMetrics.widthPixels
        val h = ctx.resources.displayMetrics.heightPixels
        val corners = BoardCornersStore.get(w, h, ctx) ?: return null
        if (corners.size < 2) return null
        val ys = corners.map { it.second }.sorted()
        return (ys[0] + ys[1]) / 2.0
    }

    /** 状态栏（通知栏）高度像素；取不到返回 0。 */
    private fun statusBarHeightPx(): Int {
        val ctx = appContext ?: return 0
        return runCatching {
            val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0
        }.getOrDefault(0)
    }

    private fun dismissBoardWindow() {
        boardHost?.dismiss()
        boardHost = null
    }

    // ---------- 回调 ----------

    private fun onStartStop() {
        val ctx = appContext ?: return
        val s = ensureSession(ctx)
        if (BotRuntime.running.value) {
            s.interrupt()
            LogBus.log(LogLevel.INFO, LogTag.PLAY, "已请求中断棋局")
            // 中断后保持操控条（不切信息框，#4）
        } else {
            scopeLaunchStart(ctx)
        }
    }

    private fun scopeLaunchStart(ctx: Context) {
        // 双窗常驻，开始后无需切换；直接启动会话
        botScope.launch { ensureSession(ctx).start() }
        LogBus.log(LogLevel.INFO, LogTag.PLAY, "开始棋局：等待棋盘就绪并启动对弈")
    }

    private fun onAutoNextChange(value: Boolean) {
        BotRuntime.autoNext.value = value
        LogBus.log(LogLevel.INFO, LogTag.NEXT, "自动下一局已${if (value) "开启" else "关闭"}")
        uiScope.launch { settings?.setAutoNextEnabled(value) }
    }

    private fun onBoardToggle() {
        val next = !BotRuntime.boardWindowShown.value
        BotRuntime.boardWindowShown.value = next
        LogBus.log(LogLevel.INFO, LogTag.SYSTEM, "棋盘小窗已${if (next) "显示" else "隐藏"}")
        uiScope.launch { settings?.setBoardDrawEnabled(next) }
    }

    /** 信息框显隐（操控条「信息框」按钮）：仅内存态，不持久化（每次弹出悬浮窗默认打开）。 */
    private fun onInfoToggle() {
        val next = !BotRuntime.infoShown.value
        BotRuntime.infoShown.value = next
        LogBus.log(LogLevel.INFO, LogTag.SYSTEM, "信息框已${if (next) "显示" else "隐藏"}")
    }

    /** ⌂ 返回 App：自动停止对弈并退出悬浮窗（操控条+信息框+棋盘），回到主界面。运行中→先确认（3s 超时还原）。 */
    private fun onRequestClose() {
        exitPromptJob?.cancel()
        if (!BotRuntime.running.value) {
            stopAndExitToHome()
            return
        }
        exitPrompt.value = "返回 App 会中断对弈并退出悬浮窗，是否确认？"
        exitPromptJob = uiScope.launch {
            kotlinx.coroutines.delay(3000)
            exitPrompt.value = null
        }
    }

    /** ⌂ 确认：停止前台服务（中断会话 + 收起全部悬浮窗）并回到主界面。 */
    private fun onConfirmExit() {
        exitPromptJob?.cancel()
        exitPrompt.value = null
        stopAndExitToHome()
    }

    private fun onCancelExit() {
        exitPromptJob?.cancel()
        exitPrompt.value = null
    }

    /** ⌂ 返回：停止前台服务（中断会话 + 收起全部悬浮窗）并回到主界面。 */
    private fun stopAndExitToHome() {
        val ctx = appContext ?: return
        BotForegroundService.stop(ctx)
        returnToApp()
    }

    /** 回到 ChessBot 主界面（悬浮窗常驻，服务不停止）。 */
    private fun returnToApp() {
        val ctx = appContext ?: return
        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { ctx.startActivity(intent) }
    }

    // 拖动只更新窗口位置（每个 move 事件即时生效）；落盘统一在拖动结束时执行一次，
    // 避免拖动过程中每个事件都起 DataStore 写协程造成主线程抖动。

    private fun dragControl(dy: Float) {
        ctrlYAuto = false
        ctrlY = (ctrlY - dy.roundToInt()).coerceAtLeast(0)
        controlHost?.updateLayout { y = ctrlY }
    }

    private fun persistControl() {
        uiScope.launch { settings?.setOverlayControl(ctrlX, ctrlY) }
    }

    private fun dragInfo(dx: Float, dy: Float) {
        // TOP|END 锚定：y 向下增大（y += dy）；x 为右缘边距，手指向右（dx>0）→ 边距减小（x -= dx）。
        // 自由拖动：仅钳制不越出屏幕（边距 ≥0），不再要求贴边。
        infoX = (infoX - dx.roundToInt()).coerceAtLeast(0)
        infoY = (infoY + dy.roundToInt()).coerceAtLeast(0)
        infoHost?.updateLayout { x = infoX; y = infoY }
    }

    private fun persistInfo() {
        uiScope.launch { settings?.setOverlayInfo2(infoX, infoY) }
    }

    private fun dragBoard(dx: Float, dy: Float) {
        // TOP|START 锚定：手指向右/下 → x/y 增大（与纵向一致；此前横向符号写反）
        boardX = (boardX + dx.roundToInt()).coerceAtLeast(0)
        boardY = (boardY + dy.roundToInt()).coerceAtLeast(0)
        boardHost?.updateLayout { x = boardX; y = boardY }
    }

    private fun persistBoard() {
        uiScope.launch { settings?.setOverlayBoard(boardX, boardY) }
    }
}
