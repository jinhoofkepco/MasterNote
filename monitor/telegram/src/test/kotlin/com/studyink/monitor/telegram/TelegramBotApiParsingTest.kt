package com.studyink.monitor.telegram

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramBotApiParsingTest {
    @Test fun parsesPeerBotIdentityDocumentCaptionSizeAndReply() {
        val update = parseTelegramInboundUpdate(
            JSONObject(
                """
                {
                  "update_id": 81,
                  "message": {
                    "message_id": 44,
                    "date": 123,
                    "chat": {"id": 202, "type": "private"},
                    "from": {"id": 202, "is_bot": true, "first_name": "Teacher", "username": "teacher_bot"},
                    "caption": "MNTP1 DOC pair_identifier_123 transfer_123 PAGE_SNAPSHOT",
                    "document": {
                      "file_id": "telegram-file-id",
                      "file_unique_id": "stable-id",
                      "file_name": "master-note.mne",
                      "mime_type": "application/vnd.masternote.peer+encrypted",
                      "file_size": 1048576
                    },
                    "reply_to_message": {"message_id": 40}
                  }
                }
                """.trimIndent(),
            ),
        )

        assertTrue(update.senderIsBot)
        assertEquals(202L, update.senderId)
        assertEquals(202L, update.chatId)
        assertEquals("teacher_bot", update.senderUsername)
        assertEquals(1_048_576L, update.document?.fileSizeBytes)
        assertEquals("telegram-file-id", update.document?.fileId)
        assertEquals(40L, update.replyToMessageId)
    }
}
