package com.chess.bot.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import com.chess.bot.data.BoardCornersStore
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogKind
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
    val validationPassed = mutableStateOf(false)
    val errorMsg = mutableStateOf<String?>(null)
    /** 截图识别进行中（防抖：识别期间忽略再次点击「截图」）。 */
    val recognizing = mutableStateOf(false)
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
                LogBus.log(LogKind.WARN, LogTag.CALIB, "未捕获到画面，请确认象棋 App 在前台")
                screen.value = CalibrationScreen.HOME
                return@launch
            }
            screen.value = CalibrationScreen.RECOGNIZING
            // 3. 后台识别（OpenCV 初始化 + 模板加载 + 角点检测可能耗时数秒）
            try {
                val templates = VisionInit.loadPieceTemplates(context)
                val (result, ok) = withContext(Dispatchers.Default) {
                    val gray = BoardCornerDetector.toGray(frame)
                    val r = BoardCornerDetector.detect(gray, templates)
                    gray.release()
                    val passed = BoardCornerDetector.validateAsOpening(frame, r.corners, templates)
                    r to passed
                }
                capturedBitmap = frame
                corners = result.corners
                matchScores = result.scores
                validationPassed.value = ok
                errorMsg.value = null
                manualCorners = null
                screen.value = CalibrationScreen.RESULT
                LogBus.log(LogKind.OK, LogTag.CALIB, "识别完成，开局校验${if (ok) "通过" else "未通过"}")
            } catch (e: Exception) {
                frame.recycle()
                capturedBitmap = null
                corners = null
                validationPassed.value = false
                errorMsg.value = "识别失败：${e.message}"
                LogBus.log(LogKind.ERROR, LogTag.CALIB, "识别失败：${e.message}")
                screen.value = CalibrationScreen.HOME
            } finally {
                recognizing.value = false
            }
        }
    }

    fun openManual() {
        if (corners == null) return
        screen.value = CalibrationScreen.MANUAL
    }

    fun backToResult() {
        screen.value = CalibrationScreen.RESULT
    }

    fun setManualCorners(c: List<Pair<Double, Double>>) {
        manualCorners = c
    }

    /** 保存：优先手动微调结果，否则自动识别结果；写 JSON 并失效 Homography 缓存。 */
    fun save(context: Context, onSaved: () -> Unit) {
        val c = manualCorners ?: corners
        if (c == null) {
            LogBus.log(LogKind.WARN, LogTag.CALIB, "无可用四角，无法保存")
            return
        }
        Homography.invalidate(width, height)
        BoardCornersStore.put(width, height, c, context)
        screen.value = CalibrationScreen.HOME
        onSaved()
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
        validationPassed.value = false
        errorMsg.value = null
        screen.value = CalibrationScreen.HOME
    }
}
