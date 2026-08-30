package com.studyink.reader

import android.content.Context
import android.content.SharedPreferences

internal val DEFAULT_PEN_COLOR_ARGB: Int = 0xFF17233C.toInt()
internal const val DEFAULT_PEN_WIDTH_DP = 1.6f
internal val PEN_WIDTH_CHOICES_DP = listOf(3.2f, 2.4f, 1.6f, 1.2f, 0.8f)

internal data class ReaderPenSettings(
    val colorArgb: Int,
    val widthDp: Float,
)

internal interface ReaderPenPreferenceStore {
    fun getInt(key: String, defaultValue: Int): Int
    fun getFloat(key: String, defaultValue: Float): Float
    fun putInt(key: String, value: Int)
    fun putFloat(key: String, value: Float)
}

internal class ReaderPenPreferences(
    private val store: ReaderPenPreferenceStore,
) {
    constructor(context: Context) : this(
        SharedPreferencesPenStore(
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        ),
    )

    fun load(): ReaderPenSettings = ReaderPenSettings(
        colorArgb = store.getInt(COLOR_KEY, DEFAULT_PEN_COLOR_ARGB),
        widthDp = store.getFloat(WIDTH_KEY, DEFAULT_PEN_WIDTH_DP),
    )

    fun saveColor(colorArgb: Int) = store.putInt(COLOR_KEY, colorArgb)

    fun saveWidth(widthDp: Float) = store.putFloat(WIDTH_KEY, widthDp)

    private class SharedPreferencesPenStore(
        private val preferences: SharedPreferences,
    ) : ReaderPenPreferenceStore {
        override fun getInt(key: String, defaultValue: Int): Int =
            preferences.getInt(key, defaultValue)

        override fun getFloat(key: String, defaultValue: Float): Float =
            preferences.getFloat(key, defaultValue)

        override fun putInt(key: String, value: Int) {
            preferences.edit().putInt(key, value).apply()
        }

        override fun putFloat(key: String, value: Float) {
            preferences.edit().putFloat(key, value).apply()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "reader-pen-settings"
        const val COLOR_KEY = "colorArgb"
        const val WIDTH_KEY = "widthDp"
    }
}
