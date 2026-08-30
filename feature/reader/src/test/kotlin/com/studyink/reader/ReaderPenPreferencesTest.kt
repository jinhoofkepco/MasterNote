package com.studyink.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderPenPreferencesTest {
    @Test
    fun defaultSettingsUseTheMiddleHalfSizePencil() {
        val settings = ReaderPenPreferences(MemoryStore()).load()

        assertEquals(DEFAULT_PEN_COLOR_ARGB, settings.colorArgb)
        assertEquals(1.6f, settings.widthDp)
        assertEquals(listOf(3.2f, 2.4f, 1.6f, 1.2f, 0.8f), PEN_WIDTH_CHOICES_DP)
    }

    @Test
    fun colorAndWidthSurviveCreatingANewPreferencesInstance() {
        val store = MemoryStore()
        ReaderPenPreferences(store).apply {
            saveColor(0xFF2C6CE8.toInt())
            saveWidth(0.8f)
        }

        val restored = ReaderPenPreferences(store).load()

        assertEquals(0xFF2C6CE8.toInt(), restored.colorArgb)
        assertEquals(0.8f, restored.widthDp)
    }

    private class MemoryStore : ReaderPenPreferenceStore {
        private val values = mutableMapOf<String, Any>()

        override fun getInt(key: String, defaultValue: Int): Int = values[key] as? Int ?: defaultValue

        override fun getFloat(key: String, defaultValue: Float): Float = values[key] as? Float ?: defaultValue

        override fun putInt(key: String, value: Int) {
            values[key] = value
        }

        override fun putFloat(key: String, value: Float) {
            values[key] = value
        }
    }
}
