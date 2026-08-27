package com.studyink.reader

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Durable, presentation-only duplicate guard for the five-second peer-chat overlay and TTS.
 *
 * Chat unread state deliberately lives elsewhere. Claiming an announcement therefore never hides
 * a message from the conversation or clears its unread badge. The claim is fsynced before this
 * method returns true, so callers may safely perform visible/audible side effects afterwards.
 */
internal class PeerChatAnnouncementStore(
    private val journal: File,
    private val maximumClaims: Int = DEFAULT_MAXIMUM_CLAIMS,
) {
    private data class Claim(val pairId: String, val messageId: String)

    private val processLock = lockFor(journal)
    private val lockFile = File(journal.parentFile, "${journal.name}.lock")

    init {
        require(maximumClaims > 0)
    }

    /** Returns true for exactly one durable claimant of this pair/message announcement. */
    fun claim(pairId: String, messageId: String): Boolean {
        validateToken(pairId, "pairId")
        validateToken(messageId, "messageId")
        val requested = Claim(pairId, messageId)
        synchronized(processLock) {
            val parent = requireNotNull(journal.parentFile)
            require(parent.mkdirs() || parent.isDirectory) {
                "Cannot create peer-chat announcement directory"
            }
            RandomAccessFile(lockFile, "rw").use { lockOwner ->
                lockOwner.channel.use { channel ->
                    channel.lock().use {
                        val claims = load()
                        if (requested in claims) return false
                        claims += requested
                        while (claims.size > maximumClaims) claims.remove(claims.first())
                        persist(claims)
                        return true
                    }
                }
            }
        }
    }

    private fun load(): LinkedHashSet<Claim> {
        if (!journal.isFile) return linkedSetOf()
        val lines = journal.readLines(StandardCharsets.UTF_8)
        require(lines.firstOrNull() == VERSION) { "Invalid peer-chat announcement journal" }
        val claims = linkedSetOf<Claim>()
        lines.drop(1).forEach { line ->
            val fields = line.split('\t')
            require(fields.size == FIELD_COUNT && fields[0] == CLAIM_RECORD) {
                "Invalid peer-chat announcement record"
            }
            val claim = Claim(decode(fields[1]), decode(fields[2]))
            validateToken(claim.pairId, "pairId")
            validateToken(claim.messageId, "messageId")
            claims.remove(claim)
            claims += claim
        }
        while (claims.size > maximumClaims) claims.remove(claims.first())
        return claims
    }

    private fun persist(claims: Set<Claim>) {
        val parent = requireNotNull(journal.parentFile)
        val temporary = File(parent, "${journal.name}.tmp")
        try {
            FileOutputStream(temporary, false).use { output ->
                output.write("$VERSION\n".toByteArray(StandardCharsets.UTF_8))
                claims.forEach { claim ->
                    output.write(
                        "$CLAIM_RECORD\t${encode(claim.pairId)}\t${encode(claim.messageId)}\n"
                            .toByteArray(StandardCharsets.UTF_8),
                    )
                }
                output.flush()
                output.fd.sync()
            }
            runCatching {
                Files.move(
                    temporary.toPath(),
                    journal.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(
                    temporary.toPath(),
                    journal.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
    }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String = Base64.getUrlDecoder().decode(value)
        .toString(StandardCharsets.UTF_8)

    private fun validateToken(value: String, field: String) {
        require(TOKEN_PATTERN.matches(value)) { "$field must be an opaque token" }
        require(value.toByteArray(StandardCharsets.UTF_8).size <= MAX_TOKEN_BYTES) {
            "$field is too long"
        }
    }

    companion object {
        private const val VERSION = "PCA1"
        private const val CLAIM_RECORD = "CLAIM"
        private const val FIELD_COUNT = 3
        private const val MAX_TOKEN_BYTES = 128
        private const val DEFAULT_MAXIMUM_CLAIMS = 1_024
        private const val JOURNAL_NAME = "peer-chat-announcements.v1"
        private val processLocks = ConcurrentHashMap<String, Any>()
        private val instances = ConcurrentHashMap<String, PeerChatAnnouncementStore>()
        private val TOKEN_PATTERN = Regex("[A-Za-z0-9_-]+")

        fun get(context: Context): PeerChatAnnouncementStore {
            val journal = context.applicationContext.noBackupFilesDir
                .resolve("remote-review")
                .resolve(JOURNAL_NAME)
            val path = journal.canonicalPath
            return instances.computeIfAbsent(path) { PeerChatAnnouncementStore(journal) }
        }

        private fun lockFor(file: File): Any = processLocks.computeIfAbsent(file.canonicalPath) { Any() }
    }
}
