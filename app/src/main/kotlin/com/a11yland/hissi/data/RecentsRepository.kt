package com.a11yland.hissi.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private val Context.recentsDataStore by preferencesDataStore(name = "recent-searches")

// Persistence for the recent search terms; the list logic itself is the
// fixture-tested RecentSearchesStore in :core.
class RecentsRepository(private val context: Context) {
    private val key = stringPreferencesKey("recentSearches")

    suspend fun load(): List<String> {
        val stored = context.recentsDataStore.data.first()[key] ?: return emptyList()
        return runCatching { Json.decodeFromString<List<String>>(stored) }.getOrDefault(emptyList())
    }

    suspend fun save(searches: List<String>) {
        context.recentsDataStore.edit { it[key] = Json.encodeToString(searches) }
    }
}
