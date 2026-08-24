package com.studyink.monitor.core

sealed interface ParentInboundAction {
    data class Text(val text: String) : ParentInboundAction
    data object CurrentPageSnapshot : ParentInboundAction
    data object EnableRealtimeActivity : ParentInboundAction
    data object UseHourlyActivity : ParentInboundAction
}

/** Parses the small, intentionally closed command surface accepted from the paired parent. */
object ParentCommandParser {
    fun parse(raw: String): ParentInboundAction? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith('/')) return ParentInboundAction.Text(trimmed.take(MAX_TEXT_CHARS))

        val commandToken = trimmed.substringBefore(' ').substringBefore('@').lowercase()
        return when (commandToken) {
            "/화면", "/screen" -> ParentInboundAction.CurrentPageSnapshot
            "/실시간", "/realtime" -> ParentInboundAction.EnableRealtimeActivity
            "/일반", "/normal", "/hourly" -> ParentInboundAction.UseHourlyActivity
            // Pairing is consumed only by the setup flow, never displayed to the student.
            "/연결", "/connect" -> null
            else -> null
        }
    }

    fun isPairingRequest(raw: String): Boolean {
        val token = raw.trim().substringBefore(' ').substringBefore('@').lowercase()
        return token == "/연결" || token == "/connect"
    }

    private const val MAX_TEXT_CHARS = 4_096
}
