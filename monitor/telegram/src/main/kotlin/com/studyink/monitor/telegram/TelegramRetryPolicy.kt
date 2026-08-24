package com.studyink.monitor.telegram

import java.io.FileNotFoundException
import java.nio.file.NoSuchFileException
import kotlin.math.roundToLong

fun interface TelegramJitterSource {
    /** A value in 0.0..1.0. */
    fun nextFraction(): Double
}

object TelegramRetryPolicy {
    fun isPermanent(error: Throwable): Boolean = when (error) {
        is TelegramApiException -> error.statusCode in 400..499 &&
            error.statusCode !in setOf(408, 409, 425, 429)
        is FileNotFoundException, is NoSuchFileException, is SecurityException,
        is IllegalArgumentException -> true
        else -> false
    }

    fun retryDelayMs(
        error: Throwable,
        attemptsAlreadyMade: Int,
        jitterFraction: Double,
    ): Long {
        require(attemptsAlreadyMade >= 0)
        require(jitterFraction in 0.0..1.0)
        val base = if (error is TelegramApiException && error.statusCode == 429) {
            error.retryAfterSeconds?.coerceIn(1L, 60L * 60L)?.times(1_000L) ?: DEFAULT_429_MS
        } else {
            (INITIAL_BACKOFF_MS shl attemptsAlreadyMade.coerceAtMost(8)).coerceAtMost(MAX_BACKOFF_MS)
        }
        // Positive jitter never retries earlier than Telegram's retry_after value.
        return (base + base * MAX_JITTER_RATIO * jitterFraction).roundToLong()
            .coerceAtMost(MAX_BACKOFF_WITH_JITTER_MS)
    }

    fun shortReason(error: Throwable): String = when (error) {
        is TelegramApiException -> "Telegram ${error.statusCode} · ${error.message.orEmpty().take(160)}"
        is FileNotFoundException, is NoSuchFileException -> "로컬 파일을 찾을 수 없음"
        is SecurityException -> "로컬 파일 접근 거부"
        is IllegalArgumentException -> "파일 또는 요청 형식 오류"
        else -> error.javaClass.simpleName.take(80)
    }

    private const val INITIAL_BACKOFF_MS = 2_000L
    private const val DEFAULT_429_MS = 30_000L
    private const val MAX_BACKOFF_MS = 5L * 60_000L
    private const val MAX_JITTER_RATIO = 0.25
    private const val MAX_BACKOFF_WITH_JITTER_MS = 375_000L
}
