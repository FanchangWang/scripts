package com.chess.bot.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chess.bot.game.Const
import com.chess.bot.log.LogKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "bot_settings")

/** 引擎思考模式：仅时长 / 仅层数 / 双限（先到为准）。 */
enum class ThinkMode(val cn: String) { TIME("时长"), DEPTH("层数"), BOTH("先到为准") }

/** 运行时配置快照（Const 为默认值层；DataStore 持久化用户偏好）。 */
data class BotConfigData(
    val thinkMode: ThinkMode = ThinkMode.BOTH,
    val movetimeMs: Int = Const.ENGINE_MOVETIME_MS,
    val depth: Int = Const.ENGINE_DEPTH,
    val threads: Int = Const.ENGINE_THREADS,
    val hashMb: Int = Const.ENGINE_HASH_MB,
    val bookEnabled: Boolean = Const.ENGINE_BOOK_ENABLED,
    val bookMaxMoves: Int = Const.ENGINE_BOOK_MAX_MOVES,
    val autoNext: Boolean = true,
    val boardDraw: Boolean = true,
    // 对弈节奏（设置页「对弈」分组；默认值来自 Const，DataStore 持久化用户覆盖）
    val tapHoldMs: Int = Const.TAP_HOLD_MS,
    val verifyAnimBaseMs: Int = Const.VERIFY_ANIM_BASE_MS,
    val verifyNextFrameMs: Int = Const.VERIFY_NEXT_FRAME_MS,
    val fileLogLevel: LogKind = LogKind.DEBUG,
)

/** 全局配置单例：服务启动时 load；设置页保存时整体刷新。引擎/会话直接读 data。 */
object BotConfig {
    @Volatile
    var data: BotConfigData = BotConfigData()
        private set

    suspend fun load(context: Context) {
        val s = BotSettings(context)
        data = BotConfigData(
            thinkMode = s.thinkMode.first(),
            movetimeMs = s.movetimeMs.first(),
            depth = s.depth.first(),
            threads = s.threads.first(),
            hashMb = s.hashMb.first(),
            bookEnabled = s.bookEnabled.first(),
            bookMaxMoves = s.bookMaxMoves.first(),
            autoNext = s.autoNextEnabled.first(),
            fileLogLevel = s.fileLogLevel.first(),
            boardDraw = s.boardDrawEnabled.first(),
            tapHoldMs = s.tapHoldMs.first(),
            verifyAnimBaseMs = s.verifyAnimBaseMs.first(),
            verifyNextFrameMs = s.verifyNextFrameMs.first(),
        )
    }

    suspend fun save(context: Context, value: BotConfigData) {
        data = value
        BotSettings(context).apply {
            setThinkMode(value.thinkMode)
            setMovetimeMs(value.movetimeMs)
            setDepth(value.depth)
            setThreads(value.threads)
            setHashMb(value.hashMb)
            setBookEnabled(value.bookEnabled)
            setBookMaxMoves(value.bookMaxMoves)
            setAutoNextEnabled(value.autoNext)
            setFileLogLevel(value.fileLogLevel)
            setBoardDrawEnabled(value.boardDraw)
            setTapHoldMs(value.tapHoldMs)
            setVerifyAnimBaseMs(value.verifyAnimBaseMs)
            setVerifyNextFrameMs(value.verifyNextFrameMs)
        }
    }
}

class BotSettings(private val context: Context) {

    // 默认值唯一源头 = BotConfigData()：所有 Flow 的 `?:` 回退一律引用它，
    // 避免「数据类默认」与「DataStore 回退」两处不一致（首装/清数据后默认值失效）。
    val thinkMode: Flow<ThinkMode> =
        context.dataStore.data.map {
            ThinkMode.valueOf(
                it[KEY_THINK_MODE] ?: DEFAULTS.thinkMode.name
            )
        }
    val movetimeMs: Flow<Int> =
        context.dataStore.data.map { it[KEY_MOVETIME] ?: DEFAULTS.movetimeMs }
    val depth: Flow<Int> = context.dataStore.data.map { it[KEY_DEPTH] ?: DEFAULTS.depth }
    val threads: Flow<Int> = context.dataStore.data.map { it[KEY_THREADS] ?: DEFAULTS.threads }
    val hashMb: Flow<Int> = context.dataStore.data.map { it[KEY_HASH] ?: DEFAULTS.hashMb }
    val bookEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_BOOK_ENABLED] ?: DEFAULTS.bookEnabled }
    val bookMaxMoves: Flow<Int> =
        context.dataStore.data.map { it[KEY_BOOK_MAX_MOVES] ?: DEFAULTS.bookMaxMoves }
    val autoNextEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_AUTO_NEXT] ?: DEFAULTS.autoNext }
    val fileLogLevel: Flow<LogKind> =
        context.dataStore.data.map {
            LogKind.valueOf(
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

    /** 信息框（收起小窗）独立记忆位置（-1 = 未记忆，用默认值）。 */
    val overlayInfoX: Flow<Int> = context.dataStore.data.map { it[KEY_OVERLAY_INFO_X] ?: -1 }
    val overlayInfoY: Flow<Int> = context.dataStore.data.map { it[KEY_OVERLAY_INFO_Y] ?: -1 }

    // 对弈节奏（设置页「对弈」分组）
    val tapHoldMs: Flow<Int> =
        context.dataStore.data.map { it[KEY_TAP_HOLD] ?: DEFAULTS.tapHoldMs }
    val verifyAnimBaseMs: Flow<Int> =
        context.dataStore.data.map { it[KEY_VERIFY_ANIM_BASE] ?: DEFAULTS.verifyAnimBaseMs }
    val verifyNextFrameMs: Flow<Int> =
        context.dataStore.data.map { it[KEY_VERIFY_NEXT_FRAME] ?: DEFAULTS.verifyNextFrameMs }

    suspend fun setThinkMode(v: ThinkMode) = context.dataStore.edit { it[KEY_THINK_MODE] = v.name }
    suspend fun setMovetimeMs(v: Int) = context.dataStore.edit { it[KEY_MOVETIME] = v }
    suspend fun setDepth(v: Int) = context.dataStore.edit { it[KEY_DEPTH] = v }
    suspend fun setThreads(v: Int) = context.dataStore.edit { it[KEY_THREADS] = v }
    suspend fun setHashMb(v: Int) = context.dataStore.edit { it[KEY_HASH] = v }
    suspend fun setBookEnabled(v: Boolean) = context.dataStore.edit { it[KEY_BOOK_ENABLED] = v }
    suspend fun setBookMaxMoves(v: Int) = context.dataStore.edit { it[KEY_BOOK_MAX_MOVES] = v }
    suspend fun setAutoNextEnabled(v: Boolean) = context.dataStore.edit { it[KEY_AUTO_NEXT] = v }
    suspend fun setFileLogLevel(v: LogKind) = context.dataStore.edit { it[KEY_LOG_LEVEL] = v.name }
    suspend fun setBoardDrawEnabled(v: Boolean) = context.dataStore.edit { it[KEY_BOARD_DRAW] = v }
    suspend fun setTapHoldMs(v: Int) = context.dataStore.edit { it[KEY_TAP_HOLD] = v }
    suspend fun setVerifyAnimBaseMs(v: Int) =
        context.dataStore.edit { it[KEY_VERIFY_ANIM_BASE] = v }

    suspend fun setVerifyNextFrameMs(v: Int) =
        context.dataStore.edit { it[KEY_VERIFY_NEXT_FRAME] = v }

    suspend fun setOverlayControl(x: Int, y: Int) = context.dataStore.edit {
        it[KEY_OVERLAY_CONTROL_X] = x
        it[KEY_OVERLAY_CONTROL_Y] = y
    }

    suspend fun setOverlayBoard(x: Int, y: Int) = context.dataStore.edit {
        it[KEY_OVERLAY_BOARD_X] = x
        it[KEY_OVERLAY_BOARD_Y] = y
    }

    suspend fun setOverlayInfo(x: Int, y: Int) = context.dataStore.edit {
        it[KEY_OVERLAY_INFO_X] = x
        it[KEY_OVERLAY_INFO_Y] = y
    }

    companion object {
        /** 配置默认值唯一源头：与 BotConfigData 数据类默认保持单一事实，改默认值只动 BotConfigData。 */
        private val DEFAULTS = BotConfigData()

        private val KEY_THINK_MODE = stringPreferencesKey("think_mode")
        private val KEY_MOVETIME = intPreferencesKey("movetime_ms")
        private val KEY_DEPTH = intPreferencesKey("depth")
        private val KEY_THREADS = intPreferencesKey("threads")
        private val KEY_HASH = intPreferencesKey("hash_mb")
        private val KEY_BOOK_ENABLED = booleanPreferencesKey("book_enabled")
        private val KEY_BOOK_MAX_MOVES = intPreferencesKey("book_max_moves")
        private val KEY_AUTO_NEXT = booleanPreferencesKey("auto_next_enabled")
        private val KEY_LOG_LEVEL = stringPreferencesKey("file_log_level")
        private val KEY_BOARD_DRAW = booleanPreferencesKey("board_draw_enabled")
        private val KEY_OVERLAY_CONTROL_X = intPreferencesKey("overlay_control_x")
        private val KEY_OVERLAY_CONTROL_Y = intPreferencesKey("overlay_control_y")
        private val KEY_OVERLAY_BOARD_X = intPreferencesKey("overlay_board_x")
        private val KEY_OVERLAY_BOARD_Y = intPreferencesKey("overlay_board_y")
        private val KEY_OVERLAY_INFO_X = intPreferencesKey("overlay_info_x")
        private val KEY_OVERLAY_INFO_Y = intPreferencesKey("overlay_info_y")
        private val KEY_TAP_HOLD = intPreferencesKey("tap_hold_ms")
        private val KEY_VERIFY_ANIM_BASE = intPreferencesKey("verify_anim_base_ms")
        private val KEY_VERIFY_NEXT_FRAME = intPreferencesKey("verify_next_frame_ms")
    }
}
