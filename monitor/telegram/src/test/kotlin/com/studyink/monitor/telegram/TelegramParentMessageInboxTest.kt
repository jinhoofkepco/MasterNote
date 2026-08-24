package com.studyink.monitor.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TelegramParentMessageInboxTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun latestPendingMessageAndHighestUpdateSurviveRestart() {
        val file = temporary.newFile("parent-inbox.v1")
        val first = TelegramParentMessageInbox(file)
        assertTrue(first.offer(10L, "첫 메시지"))
        assertFalse(first.offer(10L, "중복"))
        assertFalse(first.offer(9L, "과거"))
        assertTrue(first.offer(11L, "최신 메시지"))

        val replayed = TelegramParentMessageInbox(file)
        assertEquals(TelegramParentMessageInbox.Pending(11L, "최신 메시지"), replayed.pending())
        assertFalse(replayed.acknowledge(10L))
        assertTrue(replayed.acknowledge(11L))

        val acknowledged = TelegramParentMessageInbox(file)
        assertNull(acknowledged.pending())
        assertFalse(acknowledged.offer(11L, "재전송"))
        assertTrue(acknowledged.offer(12L, "다음 메시지"))
    }

    @Test fun subscriptionReplaysPendingAndDoesNotLetOldAckEraseNewerMessage() {
        val inbox = TelegramParentMessageInbox(temporary.newFile("subscription.v1"))
        inbox.offer(20L, "대기")
        val observed = mutableListOf<TelegramParentMessageInbox.Pending>()
        val subscription = inbox.subscribe { observed += it }

        assertEquals(listOf(TelegramParentMessageInbox.Pending(20L, "대기")), observed)
        inbox.offer(21L, "새 메시지")
        assertFalse(inbox.acknowledge(20L))
        assertEquals(TelegramParentMessageInbox.Pending(21L, "새 메시지"), inbox.pending())
        assertTrue(inbox.acknowledge(21L))
        subscription.close()
        inbox.offer(22L, "구독 해제 뒤")
        assertEquals(2, observed.size)
    }

    @Test fun connectionChangeClearRemovesPendingAndResetsUpdateScopeDurably() {
        val file = temporary.newFile("clear.v1")
        val inbox = TelegramParentMessageInbox(file)
        inbox.offer(90L, "이전 부모 메시지")

        inbox.clear()

        val replayed = TelegramParentMessageInbox(file)
        assertNull(replayed.pending())
        assertTrue(replayed.offer(1L, "새 봇의 첫 메시지"))
    }
}
