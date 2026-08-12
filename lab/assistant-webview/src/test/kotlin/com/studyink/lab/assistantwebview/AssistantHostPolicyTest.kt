package com.studyink.lab.assistantwebview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantHostPolicyTest {
    @Test fun acceptsOnlyExactHttpsHosts() {
        assertTrue(AssistantHostPolicy.isAllowed("https://chatgpt.com/"))
        assertFalse(AssistantHostPolicy.isAllowed("http://chatgpt.com/"))
        assertFalse(AssistantHostPolicy.isAllowed("https://chatgpt.com.evil.example/"))
        assertFalse(AssistantHostPolicy.isAllowed("file:///sdcard/secret"))
        assertFalse(AssistantHostPolicy.isAllowed("https://user@chatgpt.com/"))
    }
}
