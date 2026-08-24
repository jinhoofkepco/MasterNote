package com.studyink.monitor.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramRetryPolicyTest {
    @Test fun rateLimitHonorsRetryAfterAndAddsOnlyPositiveJitter() {
        val error = TelegramApiException(429, "Too Many Requests", retryAfterSeconds = 40L)
        assertEquals(40_000L, TelegramRetryPolicy.retryDelayMs(error, 0, 0.0))
        assertEquals(50_000L, TelegramRetryPolicy.retryDelayMs(error, 0, 1.0))
        assertFalse(TelegramRetryPolicy.isPermanent(error))
    }

    @Test fun authenticationFailureIsPermanentButServerFailureRetries() {
        assertTrue(TelegramRetryPolicy.isPermanent(TelegramApiException(401, "Unauthorized")))
        assertFalse(TelegramRetryPolicy.isPermanent(TelegramApiException(503, "Unavailable")))
    }

    @Test fun responsePolicyExtractsTelegramRetryAfter() {
        val error = TelegramApiResponsePolicy.httpFailure(
            429,
            "body",
            org.json.JSONObject(
                """{"ok":false,"description":"Too Many Requests","parameters":{"retry_after":17}}""",
            ),
        )
        assertEquals(17L, error.retryAfterSeconds)
        assertEquals("Too Many Requests", error.message)
    }
}
