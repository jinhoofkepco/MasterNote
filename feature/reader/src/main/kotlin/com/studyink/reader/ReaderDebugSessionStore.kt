package com.studyink.reader

import android.content.Context
import android.content.pm.ApplicationInfo

data class ReaderDebugSession(
    val bookId: String,
    val pageNumber: Int,
    val role: ReaderRole,
)

/** Debug-only shortcut state. Every entry point checks the debuggable application flag. */
object ReaderDebugSessionStore {
    private const val PREFERENCES = "reader-debug-resume"
    private const val BOOK_ID = "bookId"
    private const val PAGE_NUMBER = "pageNumber"
    private const val ROLE = "role"

    fun isEnabled(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    fun save(context: Context, state: ReaderUiState) {
        if (!isEnabled(context) || !state.documentReady || state.bookId.isBlank()) return
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(BOOK_ID, state.bookId)
            .putInt(PAGE_NUMBER, state.pageNumber)
            .putString(ROLE, state.role.name)
            .apply()
    }

    fun load(context: Context): ReaderDebugSession? {
        if (!isEnabled(context)) return null
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val bookId = preferences.getString(BOOK_ID, null)?.takeIf(String::isNotBlank) ?: return null
        val role = preferences.getString(ROLE, null)
            ?.let { runCatching { ReaderRole.valueOf(it) }.getOrNull() }
            ?: ReaderRole.STUDENT
        return ReaderDebugSession(
            bookId = bookId,
            pageNumber = preferences.getInt(PAGE_NUMBER, 0).coerceAtLeast(0),
            role = role,
        )
    }
}
