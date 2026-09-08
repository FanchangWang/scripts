package com.chess.bot.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chess.bot.game.Const
import com.chess.bot.log.LogLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "bot_settings")

/** 运行时配置快照（Const 为默认值层；DataStore 持久化用户偏好）。 */
data class BotConfigData(
    val movetimeMs: Int = Const.ENGINE_MOVETIME_MS,
    val threads: Int = Const.ENGINE_THREADS,
    val hashMb: Int = Const.ENGINE_HASH_MB,
    val bookEnabled: Boolean = Const.ENGINE_BOOK_ENABLED,
    val autoNext: Boolean = true,
    val boardDraw: Boolean = true,
    // 对弈节奏（设置页「我方走棋/敌方走棋」分组；默认值来自 Const，DataStore 持久化用户覆盖）
    val tapHoldMs: Int = Const.TAP_HOLD_MS,
    val verifyAnimBaseMs: Int = Const.VERIFY_ANIM_BASE_MS,
    val verifyNextFrameMs: Int = Const.VERIFY_NEXT_FRAME_MS,
    val enemyPollMs: Int = Const.ENEMY_IDLE_POLL_MS.toInt(),
    val fileLogLevel: LogLevel = LogLevel.DEBUG,
)

/** 全局配置单例：服务启动时 load；设置页保存时整体刷新。引擎/会话直接读 data。 */
object BotConfig {
    @Volatile
    var data: BotConfigData = BotConfigData()
        private set

    suspend fun load(context: Context) {
        val s = BotSettings(context)
        data = BotConfigData(
            movetimeMs = s.movetimeMs.first(),
            threads = s.threads.first(),
            hashMb = s.hashMb.first(),
            bookEnabled = s.bookEnabled.first(),
            autoNext = s.autoNextEnabled.first(),
            fileLogLevel = s.fileLogLevel.first(),
            boardDraw = s.boardDrawEnabled.first(),
            tapHoldMs = s.tapHoldMs.first(),
            verifyAnimBaseMs = s.verifyAnimBaseMs.first(),
            verifyNextFrameMs = s.verifyNextFrameMs.first(),
            enemyPollMs = s.enemyPollMs.first(),
        )
    }

    suspend fun save(context: Context, value: BotConfigData) {
        data = value
        BotSettings(context).apply {
            setMovetimeMs(value.movetimeMs)
            setThreads(value.threads)
            setHashMb(value.hashMb)
            setBookEnabled(value.bookEnabled)
            setAutoNextEnabled(value.autoNext)
            setFileLogLevel(value.fileLogLevel)
            setBoardDrawEnabled(value.boardDraw)
            setTapHoldMs(value.tapHoldMs)
            setVerifyAnimBaseMs(value.verifyAnimBaseMs)
            setVerifyNextFrameMs(value.verifyNextFrameMs)
            setEnemyPollMs(value.enemyPollMs)
        }
    }
}

class BotSettings(private val context: Context) {

    // 默认值唯一源头 = BotConfigData()：所有 Flow 的 `?:` 回退一律引用它，
    // 避免「数据类默认」与「DataStore 回退」两处不一致（首装/清数据后默认值失效）。
    val movetimeMs: Flow<Int> =
        context.dataStore.data.map { it[KEY_MOVETIME] ?: DEFAULTS.movetimeMs }
    val threads: Flow<Int> = context.dataStore.data.map { it[KEY_THREADS] ?: DEFAULTS.threads }
    val hashMb: Flow<Int> = context.dataStore.data.map { it[KEY_HASH] ?: DEFAULTS.hashMb }
    val bookEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_BOOK_ENABLED] ?: DEFAULTS.bookEnabled }
    val autoNextEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_AUTO_NEXT] ?: DEFAULTS.autoNext }
    val fileLogLevel: Flow<LogLevel> =
        context.dataStore.data.map {
            LogLevel.valueOf(
                it[KEY_LOG_LEVEL] ?: DEFAULTS.fileLogLevel.name
            )
        }

    /** 棋盘绘制总开关（设置页「棋盘绘制」+ 操控条「棋盘」图标的持久化来源）。 */
    val boardDrawEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_BOARD_DRAW] ?: DEFAULTS.boardDraw }

    /** 悬浮窗位置持久化（-1 = 未记忆，用默认值）。 */
    val overlayControlX: Flow<Int> = context.dataStore.data.map { it[KEY_OVERLAY_CONTROL_X] ?: -1 }
    val overlayControlY: Flow<Int> = context.dataStore.data.map { it[KEY_OVERLAY_CONTROL_Y] ?: -1 }
    val overlayBoardX: Flow<Int> = context.dataStore.data.map { it[KEY_OVERLAY_BOARD_X] ?: -1 }
    val overlayBoardY: Flow<Int> = context.dataStore.data.map { it[KEY_OVERLAY_BOARD_Y] ?: -1 }

    /**
     * 信息框记忆位置 v2（2026-09-07 锚点改 TOP|END：x=右缘边距、y=顶缘边距，与旧键语义不兼容，
     * 故另开新键；-1 = 未记忆，用默认值：贴右缘 x=0、y=状态栏+5 与棋盘小窗一致）。
     */
    val overlayInfo2X: Flow<Int> = context.dataStore.data.map { it[KEY_OVERLAY_INFO2_X] ?: -1 }
    val overlayInfo2Y: Flow<Int> = context.dataStore.data.map { it[KEY_OVERLAY_INFO2_Y] ?: -1 }

    // 对弈节奏（设置页「我方走棋」分组）
    val tapHoldMs: Flow<Int> =
        context.dataStore.data.map { it[KEY_TAP_HOLD] ?: DEFAULTS.tapHoldMs }
    val verifyAnimBaseMs: Flow<Int> =
        context.dataStore.data.map { it[KEY_VERIFY_ANIM_BASE] ?: DEFAULTS.verifyAnimBaseMs }
    val verifyNextFrameMs: Flow<Int> =
        context.dataStore.data.map { it[KEY_VERIFY_NEXT_FRAME] ?: DEFAULTS.verifyNextFrameMs }

    // 敌方走棋（设置页「敌方走棋」分组）
    val enemyPollMs: Flow<Int> =
        context.dataStore.data.map { it[KEY_ENEMY_POLL] ?: DEFAULTS.enemyPollMs }

    suspend fun setMovetimeMs(v: Int) = context.dataStore.edit { it[KEY_MOVETIME] = v }
    suspend fun setThreads(v: Int) = context.dataStore.edit { it[KEY_THREADS] = v }
    suspend fun setHashMb(v: Int) = context.dataStore.edit { it[KEY_HASH] = v }
    suspend fun setBookEnabled(v: Boolean) = context.dataStore.edit { it[KEY_BOOK_ENABLED] = v }
    suspend fun setAutoNextEnabled(v: Boolean) = context.dataStore.edit { it[KEY_AUTO_NEXT] = v }
    suspend fun setFileLogLevel(v: LogLevel) = context.dataStore.edit { it[KEY_LOG_LEVEL] = v.name }
    suspend fun setBoardDrawEnabled(v: Boolean) = context.dataStore.edit { it[KEY_BOARD_DRAW] = v }
    suspend fun setTapHoldMs(v: Int) = context.dataStore.edit { it[KEY_TAP_HOLD] = v }
    suspend fun setVerifyAnimBaseMs(v: Int) =
        context.dataStore.edit { it[KEY_VERIFY_ANIM_BASE] = v }

    suspend fun setVerifyNextFrameMs(v: Int) =
        context.dataStore.edit { it[KEY_VERIFY_NEXT_FRAME] = v }

    suspend fun setEnemyPollMs(v: Int) = context.dataStore.edit { it[KEY_ENEMY_POLL] = v }

    suspend fun setOverlayControl(x: Int, y: Int) = context.dataStore.edit {
        it[KEY_OVERLAY_CONTROL_X] = x
        it[KEY_OVERLAY_CONTROL_Y] = y
    }

    suspend fun setOverlayBoard(x: Int, y: Int) = context.dataStore.edit {
        it[KEY_OVERLAY_BOARD_X] = x
        it[KEY_OVERLAY_BOARD_Y] = y
    }

    suspend fun setOverlayInfo2(x: Int, y: Int) = context.dataStore.edit {
        it[KEY_OVERLAY_INFO2_X] = x
        it[KEY_OVERLAY_INFO2_Y] = y
    }

    /**
     * 恢复默认设置（2026-09-07 设置页「恢复默认」按钮）：清空本 DataStore 全部键——
     * 引擎/开局库/对弈节奏/开关/悬浮窗位置记忆一并回到默认；
     * 棋盘四角校准数据存独立 JSON 文件（BoardCornersStore），不受影响。
     */
    suspend fun resetAll() = context.dataStore.edit { it.clear() }

    companion object {
        /** 配置默认值唯一源头：与 BotConfigData 数据类默认保持单一事实，改默认值只动 BotConfigData。 */
        private val DEFAULTS = BotConfigData()

        private val KEY_MOVETIME = intPreferencesKey("movetime_ms")
        private val KEY_THREADS = intPreferencesKey("threads")
        private val KEY_HASH = intPreferencesKey("hash_mb")
        private val KEY_BOOK_ENABLED = booleanPreferencesKey("book_enabled")
        private val KEY_AUTO_NEXT = booleanPreferencesKey("auto_next_enabled")
        private val KEY_LOG_LEVEL = stringPreferencesKey("file_log_level")
        private val KEY_BOARD_DRAW = booleanPreferencesKey("board_draw_enabled")
        private val KEY_OVERLAY_CONTROL_X = intPreferencesKey("overlay_control_x")
        private val KEY_OVERLAY_CONTROL_Y = intPreferencesKey("overlay_control_y")
        private val KEY_OVERLAY_BOARD_X = intPreferencesKey("overlay_board_x")
        private val KEY_OVERLAY_BOARD_Y = intPreferencesKey("overlay_board_y")
        private val KEY_OVERLAY_INFO2_X = intPreferencesKey("overlay_info2_x")
        private val KEY_OVERLAY_INFO2_Y = intPreferencesKey("overlay_info2_y")
        private val KEY_TAP_HOLD = intPreferencesKey("tap_hold_ms")
        private val KEY_VERIFY_ANIM_BASE = intPreferencesKey("verify_anim_base_ms")
        private val KEY_VERIFY_NEXT_FRAME = intPreferencesKey("verify_next_frame_ms")
        private val KEY_ENEMY_POLL = intPreferencesKey("enemy_poll_ms")
    }
}
