package com.chess.bot.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "bot_settings")

class BotSettings(private val context: Context) {

    val autoNextEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_AUTO_NEXT] ?: true }

    suspend fun setAutoNextEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_NEXT] = value }
    }

    companion object {
        private val KEY_AUTO_NEXT = booleanPreferencesKey("auto_next_enabled")
    }
}
