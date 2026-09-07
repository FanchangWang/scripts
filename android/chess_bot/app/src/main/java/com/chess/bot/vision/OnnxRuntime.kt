package com.chess.bot.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogLevel
import com.chess.bot.log.LogTag
import java.nio.FloatBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * ONNX Runtime 会话管理（单例）：
 * - OrtEnvironment 全局唯一；按 assets 模型路径懒加载并缓存 Session。
 * - 模型从 assets 读入 ByteArray 直接建 Session（免落盘拷贝）。
 * - CPU EP（默认）：det 1280x1280 单帧约百毫秒级，仅校准回退路径调用，可接受。
 */
object OnnxRuntime {

    @Volatile
    private var env: OrtEnvironment? = null

    private val lock = ReentrantLock()
    private val sessions = mutableMapOf<String, OrtSession>()

    private fun environment(): OrtEnvironment =
        env ?: OrtEnvironment.getEnvironment().also { env = it }

    /** 获取（或创建）某 assets 模型的 Session；线程安全。 */
    fun session(context: Context, assetPath: String): OrtSession {
        sessions[assetPath]?.let { return it }
        return lock.withLock {
            sessions[assetPath]?.let { return it }
            val bytes = context.assets.open(assetPath).use { it.readBytes() }
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
            }
            val s = environment().createSession(bytes, opts)
            sessions[assetPath] = s
            LogBus.log(LogLevel.INFO, LogTag.VISION, "ONNX 模型已加载：$assetPath")
            s
        }
    }

    /** 构建 float32 NCHW 输入张量。 */
    fun tensor(shape: LongArray, data: FloatArray): OnnxTensor =
        OnnxTensor.createTensor(environment(), FloatBuffer.wrap(data), shape)

    /** 释放全部会话（进程级资源，一般无需显式调用；供测试/重启兜底）。 */
    fun closeAll() {
        lock.withLock {
            sessions.values.forEach { it.close() }
            sessions.clear()
        }
    }
}
