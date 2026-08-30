package com.chess.bot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.chess.bot.data.BotConfig
import com.chess.bot.log.FileLogger
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogKind
import com.chess.bot.log.LogTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BotForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 配置先于日志加载：文件日志级别过滤读 BotConfig（默认 DEBUG 兜底）
        serviceScope.launch { BotConfig.load(applicationContext) }
        FileLogger.start(this)
        LogBus.log(LogKind.OK, LogTag.SERVICE, "前台服务已创建，会话日志已开启")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            return handleStart(intent)
        } catch (e: Exception) {
            LogBus.log(
                LogKind.ERROR,
                LogTag.SERVICE,
                "前台服务处理异常：${e::class.java.simpleName}: ${e.message}"
            )
            android.util.Log.e("BotForegroundService", "handleStart 失败", e)
            stopSelf()
            return START_NOT_STICKY
        }
    }

    private fun handleStart(intent: Intent?): Int {
        if (intent?.action == ACTION_STOP) {
            com.chess.bot.overlay.BotRuntime.playActive.value = false
            ScreenCaptureSource.get().stop()
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE

        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode == Int.MIN_VALUE || resultData == null) {
            LogBus.log(LogKind.ERROR, LogTag.SERVICE, "启动服务缺少屏幕捕获授权参数")
            stopSelf()
            return START_NOT_STICKY
        }
        // 校准模式：仅持有截屏管线，不弹出对弈控制条
        val isCalibration = intent?.getBooleanExtra(EXTRA_CALIBRATION, false) == true
        LogBus.log(
            LogKind.DEBUG,
            LogTag.SERVICE,
            "收到授权结果：code=$resultCode${if (isCalibration) "（校准模式）" else ""}"
        )

        // Android 14+：必须先进入 mediaProjection 型前台服务，再获取 MediaProjection
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        LogBus.log(LogKind.DEBUG, LogTag.SERVICE, "前台服务已进入 mediaProjection 类型")

        if (ScreenCaptureSource.get().start(this, resultCode, resultData)) {
            LogBus.log(LogKind.OK, LogTag.SERVICE, "截屏管线已启动")
            val appCtx = applicationContext
            // 预热（2026-08-29 #2 提速）：OpenCV + 棋子模板立即在后台加载，
            // 对弈模式再并行启动 pikafish 子进程（uci 握手 + NNUE 加载 ~1.4s），
            // 使首次识别与第一步思考不再排队等冷启动
            serviceScope.launch(Dispatchers.Default) {
                com.chess.bot.vision.VisionInit.init(appCtx)
                com.chess.bot.vision.VisionInit.loadPieceTemplates(appCtx)
            }
            if (!isCalibration) {
                serviceScope.launch(Dispatchers.Default) {
                    BotConfig.load(appCtx)
                    runCatching { com.chess.bot.engine.PikafishEngine.get().ensureStarted(appCtx) }
                        .onFailure { e ->
                            LogBus.log(
                                LogKind.WARN,
                                LogTag.ENGINE,
                                "引擎预热失败（首次走棋时将重试）：${e.message}"
                            )
                        }
                }
                com.chess.bot.overlay.BotRuntime.playActive.value = true
                serviceScope.launch { com.chess.bot.overlay.OverlayManager.ensureShown(this@BotForegroundService) }
            }
        } else {
            LogBus.log(LogKind.ERROR, LogTag.SERVICE, "截屏管线启动失败，请重新授权")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        com.chess.bot.overlay.BotRuntime.playActive.value = false
        ScreenCaptureSource.get().stop()
        com.chess.bot.overlay.OverlayManager.shutdown()
        FileLogger.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "象棋 Bot 运行中",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("ChessBot 运行中")
            .setContentText("截屏管线运行中")
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "bot_foreground"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.chess.bot.action.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_CALIBRATION = "calibration"

        /** 携带 MediaProjection 授权结果启动前台服务；calibration=true 时只持管线、不弹控制条。 */
        fun start(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            calibration: Boolean = false
        ) {
            val intent = Intent(context, BotForegroundService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_CALIBRATION, calibration)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BotForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
