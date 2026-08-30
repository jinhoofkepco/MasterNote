package com.studyink.assistant.webview

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatGptTextFormatTest {
    @Test
    fun detectsCommonTexDelimitersAndMarkdownBlocks() {
        assertEquals(ChatGptTextFormat.MARKDOWN_TEX, inferChatGptTextFormat("답은 \\(x^2\\) 입니다."))
        assertEquals(ChatGptTextFormat.MARKDOWN_TEX, inferChatGptTextFormat("\\[\\frac{1}{2}\\]"))
        assertEquals(ChatGptTextFormat.MARKDOWN_TEX, inferChatGptTextFormat("답은 ${'$'}x+1${'$'} 입니다."))
        assertEquals(ChatGptTextFormat.MARKDOWN_TEX, inferChatGptTextFormat("## 풀이\n\n1. 식을 세운다"))
    }

    @Test
    fun leavesEscapedCurrencyAndOrdinaryProsePlain() {
        assertEquals(
            ChatGptTextFormat.PLAIN_TEXT,
            inferChatGptTextFormat("가격은 \\${'$'}5~\\${'$'}10이고 설명은 짧다."),
        )
        assertEquals(ChatGptTextFormat.PLAIN_TEXT, inferChatGptTextFormat("수식을 말로 설명한 평범한 문장"))
    }
}
