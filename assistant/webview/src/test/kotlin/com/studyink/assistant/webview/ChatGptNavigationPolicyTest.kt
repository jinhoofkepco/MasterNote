package com.studyink.assistant.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptNavigationPolicyTest {
    @Test
    fun allowsOnlyExactKnownHttpsHosts() {
        assertTrue(ChatGptNavigationPolicy.allows("https://chatgpt.com/"))
        assertTrue(ChatGptNavigationPolicy.allows("https://www.chatgpt.com/c/123?x=1"))
        assertTrue(ChatGptNavigationPolicy.allows("https://chat.openai.com/"))
        assertTrue(ChatGptNavigationPolicy.allows("HTTPS://CHATGPT.COM:443/"))
        assertTrue(ChatGptNavigationPolicy.allows("https://auth.openai.com/log-in"))
        assertTrue(ChatGptNavigationPolicy.allows("https://accounts.google.com/o/oauth2/auth"))
        assertTrue(ChatGptNavigationPolicy.allows("https://appleid.apple.com/auth/authorize"))

        assertFalse(ChatGptNavigationPolicy.allows("http://chatgpt.com/"))
        assertFalse(ChatGptNavigationPolicy.allows("https://chatgpt.com:8443/"))
        assertFalse(ChatGptNavigationPolicy.allows("https://chatgpt.com.evil.example/"))
        assertFalse(ChatGptNavigationPolicy.allows("https://auth.openai.com.evil.example/"))
        assertFalse(ChatGptNavigationPolicy.allows("https://accounts.google.com.evil.example/"))
        assertFalse(ChatGptNavigationPolicy.allows("https://evil.example/?next=https://chatgpt.com"))
        assertFalse(ChatGptNavigationPolicy.allows("https://chatgpt.com@evil.example/"))
        assertFalse(ChatGptNavigationPolicy.allows("https://user@chatgpt.com/"))
        assertFalse(ChatGptNavigationPolicy.allows("content://chatgpt.com/session"))
        assertFalse(ChatGptNavigationPolicy.allows("file:///data/local/tmp/page.html"))
        assertFalse(ChatGptNavigationPolicy.allows("javascript:alert(1)"))
    }

    @Test
    fun rejectsMalformedOrBlankUrlsWithoutThrowing() {
        assertFalse(ChatGptNavigationPolicy.allows(""))
        assertFalse(ChatGptNavigationPolicy.allows("not a url"))
        assertFalse(ChatGptNavigationPolicy.allows("https://"))
    }

    @Test
    fun automationRunsOnlyOnChatGptHostsAndNeverOnLoginProviders() {
        assertTrue(ChatGptNavigationPolicy.allowsAutomation("https://chatgpt.com/"))
        assertTrue(ChatGptNavigationPolicy.allowsAutomation("https://chat.openai.com/c/123"))

        assertFalse(ChatGptNavigationPolicy.allowsAutomation("https://auth.openai.com/log-in"))
        assertFalse(ChatGptNavigationPolicy.allowsAutomation("https://accounts.google.com/o/oauth2/auth"))
        assertFalse(ChatGptNavigationPolicy.allowsAutomation("https://chatgpt.com.evil.example/"))
        assertFalse(ChatGptNavigationPolicy.allowsAutomation(null))
    }
}
