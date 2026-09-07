package com.chess.bot.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import com.chess.bot.data.BoardCornersStore
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogLevel
import com.chess.bot.log.LogTag
import com.chess.bot.overlay.CalibrationCaptureOverlay
import com.chess.bot.service.ScreenCaptureSource
import com.chess.bot.vision.BoardCornerDetector
import com.chess.bot.vision.Homography
import com.chess.bot.vision.VisionInit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 校准流程界面：HOME 主界面 / STEP1 进入人机模式 / RECOGNIZING 识别中 / RESULT 识别结果 / MANUAL 手动微调。 */
enum class CalibrationScreen { HOME, STEP1, RECOGNIZING, RESULT, MANUAL }

/**
 * 校准流程的跨界面状态机：主界面卡片、悬浮截图条、识别结果/手动微调界面共享。
 *
 * 时序：主界面「开始校准」-> 隐藏 App(moveTaskToBack) + 显示悬浮截图条 ->
 * 用户在象棋 App 进入人机模式 -> 点悬浮条「截图」抓帧检测校验 -> 回主界面 RESULT ->
 * 「确认并保存」写 JSON（优先手动微调结果）并失效 Homography 缓存。
 */
object CalibrationSession {

    val screen = mutableStateOf(CalibrationScreen.HOME)

    /** 由 MainActivity 注入：去截图时若未授权屏幕捕获，用来拉起 MediaProjection 授权。 */
    var projectionRequest: (() -> Unit)? = null

    var capturedBitmap: Bitmap? = null
        private set
    var corners: List<Pair<Double, Double>>? = null
        private set
    var matchScores: List<Double> = emptyList()
        private set

    /** 四角识别来源："template:<setName>"（模板匹配）或 "det"（YOLO det 回退）。 */
    var detectSource: String = ""
        private set
    val validationPassed = mutableStateOf(false)
    val errorMsg = mutableStateOf<String?>(null)

    /** 截图识别进行中（防抖：识别期间忽略再次点击「截图」）。 */
    val recognizing = mutableStateOf(false)

    /** 识别进度文案（识别中界面实时展示，如「模板匹配 set_03（4/11）…」）。 */
    val progress = mutableStateOf("")
    var width = 0
        private set
    var height = 0
        private set
    var manualCorners: List<Pair<Double, Double>>? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 进入校准：仅需 4 项权限已授权（按钮在权限未齐时禁用）。
     * 仅切到步骤 1 界面（进入人机模式说明），隐藏 App / 显悬浮条的时机交给「去截图」。
     */
    fun start(context: Context) {
        width = context.resources.displayMetrics.widthPixels
        height = context.resources.displayMetrics.heightPixels
        errorMsg.value = null
        manualCorners = null
        screen.value = CalibrationScreen.STEP1
        // 预热 YOLO det ONNX 会话（首次加载可能数秒，避免截图后才开始加载）
        scope.launch(Dispatchers.Default) {
            runCatching { com.chess.bot.vision.CornerDetModel.ensure(context) }
        }
    }

    /** 步骤 1「去截图」：截屏管线已运行则隐藏 App 显悬浮条；否则先请求屏幕捕获授权。 */
    fun onGoScreenshot(context: Context) {
        if (ScreenCaptureSource.active.value) {
            (context as? Activity)?.moveTaskToBack(true)
            CalibrationCaptureOverlay.show(context)
        } else {
            projectionRequest?.invoke()
        }
    }

    /** 屏幕捕获授权成功后：隐藏 App 并弹出悬浮截图条（由 MainActivity 授权回调调用）。 */
    fun onProjectionGranted(context: Context) {
        (context as? Activity)?.moveTaskToBack(true)
        CalibrationCaptureOverlay.show(context)
    }

    /** 步骤 1 取消，返回主界面。 */
    fun cancelStep1() {
        screen.value = CalibrationScreen.HOME
    }

    /** 悬浮条「返回」：收起截图条，回主界面 HOME。 */
    fun onBack(context: Context) {
        bringHome(context)       // 先回前台（悬浮窗仍在，豁免后台启动限制）
        CalibrationCaptureOverlay.dismiss()
        screen.value = CalibrationScreen.HOME
    }

    /**
     * 悬浮条「截图」：抓帧后【立即】回 App 显示「识别中」，识别在后台完成后切 RESULT。
     * 防抖：recognizing 为 true 期间直接忽略（连续快速点击不会重复触发）。
     */
    fun onScreenshot(context: Context) {
        if (recognizing.value) return
        recognizing.value = true
        scope.launch {
            // 1. 同步抓取当前帧（此时前台仍是象棋 App，画面正确）
            val frame = ScreenCaptureSource.get().latest()
            // 2. 立即回 App（悬浮窗仍在，豁免后台启动限制）并切到「识别中」界面
            bringHome(context)
            CalibrationCaptureOverlay.dismiss()
            if (frame == null) {
                recognizing.value = false
                LogBus.log(LogLevel.WARN, LogTag.CALIB, "未捕获到画面，请确认象棋 App 在前台")
                screen.value = CalibrationScreen.HOME
                return@launch
            }
            screen.value = CalibrationScreen.RECOGNIZING
            LogBus.log(
                LogLevel.DEBUG, LogTag.CALIB,
                "截图完成 ${frame.width}x${frame.height}，开始识别"
            )
            // 3. 后台识别（OpenCV 初始化 + det → 全套角子模板精修 → cls 32 子校验，
            //    首次含 ONNX 会话加载可能耗时数秒）
            try {
                val (result, ok) = withContext(Dispatchers.Default) {
                    progress.value = "初始化 OpenCV / 加载模板套…"
                    val cornerSets = VisionInit.loadCornerTemplateSets(context)
                    val gray = BoardCornerDetector.toGray(frame)
                    val detected = try {
                        BoardCornerDetector.detectWithFallback(
                            context, gray, frame, cornerSets
                        ) { progress.value = it }
                    } finally {
                        gray.release()
                    }
                    if (detected == null) {
                        null to false
                    } else {
                        detected to BoardCornerDetector.validateAsOpening(
                            frame, detected.corners
                        ) { progress.value = it }
                    }
                }
                if (result == null) {
                    // 全部手段失败：仍进步骤 2（保留截图），展示失败横幅，引导手动微调
                    capturedBitmap = frame
                    corners = null
                    matchScores = emptyList()
                    detectSource = ""
                    validationPassed.value = false
                    manualCorners = null
                    errorMsg.value =
                        "模板与 YOLO det 均未能定位棋盘四角，请点击「手动微调」拖动 4 个角标"
                    LogBus.log(LogLevel.ERROR, LogTag.CALIB, errorMsg.value ?: "")
                    screen.value = CalibrationScreen.RESULT
                    return@launch
                }
                capturedBitmap = frame
                corners = result.corners
                matchScores = result.scores
                detectSource = result.source
                validationPassed.value = ok
                errorMsg.value = null
                manualCorners = null
                screen.value = CalibrationScreen.RESULT
                LogBus.log(
                    LogLevel.INFO,
                    LogTag.CALIB,
                    "识别完成（${result.source}），开局校验${if (ok) "通过" else "未通过"}"
                )
            } catch (e: Exception) {
                frame.recycle()
                capturedBitmap = null
                corners = null
                validationPassed.value = false
                errorMsg.value = "识别失败：${e.message}"
                LogBus.log(LogLevel.ERROR, LogTag.CALIB, "识别失败：${e.message}")
                screen.value = CalibrationScreen.HOME
            } finally {
                recognizing.value = false
                progress.value = ""
            }
        }
    }

    fun openManual() {
        // 无自动识别结果也允许进入：手动微调以屏幕比例默认位置初始化角标
        screen.value = CalibrationScreen.MANUAL
    }

    fun backToResult() {
        screen.value = CalibrationScreen.RESULT
    }

    fun setManualCorners(c: List<Pair<Double, Double>>) {
        manualCorners = c
        errorMsg.value = null
        // 手动微调改变了坐标：立即重跑 32 子校验刷新结果页徽标（保存时还会再次强制校验）
        val frame = capturedBitmap ?: return
        scope.launch {
            validationPassed.value = withContext(Dispatchers.Default) {
                BoardCornerDetector.validateAsOpening(frame, c)
            }
        }
    }

    /**
     * 保存：优先手动微调结果，否则自动识别结果。
     * 2026-09-05 强制约束：任一来源保存前都必须通过 cls 32 子校验，未通过禁止保存。
     */
    fun save(context: Context, onSaved: () -> Unit) {
        val c = manualCorners ?: corners
        if (c == null) {
            LogBus.log(LogLevel.WARN, LogTag.CALIB, "无可用四角，无法保存")
            return
        }
        val frame = capturedBitmap
        if (frame == null) {
            LogBus.log(LogLevel.WARN, LogTag.CALIB, "无校准截图，无法执行 32 子校验")
            return
        }
        scope.launch {
            val ok = withContext(Dispatchers.Default) {
                BoardCornerDetector.validateAsOpening(frame, c)
            }
            if (!ok) {
                errorMsg.value = "32 子校验未通过，已禁止保存：请重新截图或手动微调四角"
                LogBus.log(LogLevel.WARN, LogTag.CALIB, "保存被拦截：32 子校验未通过")
                return@launch
            }
            Homography.invalidate(width, height)
            BoardCornersStore.put(width, height, c, context)
            screen.value = CalibrationScreen.HOME
            onSaved()
        }
    }

    private fun bringHome(context: Context) {
        // 必须在悬浮窗 dismiss 之前调用：悬浮窗(TYPE_APPLICATION_OVERLAY)仍在显示时，
        // 属于 Android 10+ 后台 Activity 启动豁免；关掉悬浮窗后再 startActivity 会被静默拦截。
        val intent = Intent(context, com.chess.bot.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        context.startActivity(intent)
    }

    fun reset() {
        capturedBitmap?.recycle()
        capturedBitmap = null
        corners = null
        manualCorners = null
        matchScores = emptyList()
        detectSource = ""
        validationPassed.value = false
        errorMsg.value = null
        screen.value = CalibrationScreen.HOME
    }
}
