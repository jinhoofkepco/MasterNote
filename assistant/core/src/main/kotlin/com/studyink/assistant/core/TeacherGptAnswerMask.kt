package com.studyink.assistant.core

import java.security.MessageDigest

/**
 * Non-destructive visibility metadata for one immutable GPT answer revision.
 *
 * Block ordinals are zero-based positions produced by [parserVersion]. The original answer text is
 * never rewritten; a renderer may simply ignore this mask whenever [isValidFor] is false.
 */
data class TeacherGptAnswerMask(
    val parserVersion: Int = PARSER_VERSION,
    val sourceSha256: String,
    val hiddenBlockOrdinals: Set<Int>,
) {
    fun isValidFor(answerText: String): Boolean =
        parserVersion == PARSER_VERSION &&
            SHA256.matches(sourceSha256) &&
            sourceSha256 == sha256Of(answerText) &&
            hiddenBlockOrdinals.size <= MAX_HIDDEN_BLOCKS &&
            hiddenBlockOrdinals.all { it in 0..MAX_BLOCK_ORDINAL }

    companion object {
        const val PARSER_VERSION: Int = 1

        /** Creates canonical metadata for the exact, unmodified [answerText]. */
        fun forAnswer(
            answerText: String,
            hiddenBlockOrdinals: Collection<Int>,
        ): TeacherGptAnswerMask {
            require(hiddenBlockOrdinals.size <= MAX_HIDDEN_BLOCKS) {
                "Too many hidden GPT answer blocks"
            }
            require(hiddenBlockOrdinals.all { it in 0..MAX_BLOCK_ORDINAL }) {
                "GPT answer block ordinal is out of range"
            }
            return TeacherGptAnswerMask(
                sourceSha256 = sha256Of(answerText),
                hiddenBlockOrdinals = hiddenBlockOrdinals.toSortedSet(),
            )
        }

        fun sha256Of(answerText: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(answerText.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

        const val MAX_HIDDEN_BLOCKS: Int = 4_096
        private const val MAX_BLOCK_ORDINAL: Int = 1_000_000
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
