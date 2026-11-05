package com.litetube.app

import android.content.Context

/**
 * The user's own recent searches, kept on-device in SharedPreferences
 * (Vidmate/Snaptube-style: the terms you typed show under the search bar
 * when the field is focused and empty). Plain text only, newest first,
 * at most MAX_HISTORY entries, no duplicates. Nothing leaves the device.
 *
 * Stored as one newline-joined string so the order is preserved (a
 * StringSet would not keep it). Search terms come from a single-line
 * EditText, so they can never contain a newline themselves.
 */
class SearchHistory(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Newest first. */
    fun load(): List<String> {
        val raw = prefs.getString(KEY_RECENT, null) ?: return emptyList()
        return raw.split('\n').filter { it.isNotBlank() }
    }

    /** Record a search term: move it to the top, drop duplicates, cap size. */
    fun save(query: String) {
        val term = query.trim()
        if (term.isEmpty()) return
        val updated = (listOf(term) + load().filter { !it.equals(term, ignoreCase = true) })
            .take(MAX_HISTORY)
        prefs.edit().putString(KEY_RECENT, updated.joinToString("\n")).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_RECENT).apply()
    }

    companion object {
        private const val PREFS_NAME = "litetube_search_history"
        private const val KEY_RECENT = "recent_searches"
        const val MAX_HISTORY = 10
    }
}
