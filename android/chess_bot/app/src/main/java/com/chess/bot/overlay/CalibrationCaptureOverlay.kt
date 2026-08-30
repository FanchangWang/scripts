package com.chess.bot.overlay

import android.content.Context
import android.view.Gravity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chess.bot.ui.CalibrationSession

/**
 * 校准用悬浮操作条（截图 / 返回）：覆盖在象棋 App 之上，chessbot 主界面已隐藏。
 * 复用 OverlayHost 的 WindowManager + ComposeView 生命周期桥。
 */
object CalibrationCaptureOverlay {

    private var host: OverlayHost? = null

    fun show(context: Context) {
        if (host?.isShowing == true) return
        OverlayHost(context).also { host = it }.show(
            layout = {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                x = 0
                y = 120
            },
        ) {
            CaptureBarContent(
                onCapture = { CalibrationSession.onScreenshot(context.applicationContext) },
                onBack = { CalibrationSession.onBack(context.applicationContext) },
            )
        }
    }

    fun dismiss() {
        host?.dismiss()
        host = null
    }

    @Composable
    private fun CaptureBarContent(onCapture: () -> Unit, onBack: () -> Unit) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            tonalElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(onClick = onBack) { Text("返回") }
                Button(onClick = onCapture) { Text("截图") }
            }
        }
    }
}
