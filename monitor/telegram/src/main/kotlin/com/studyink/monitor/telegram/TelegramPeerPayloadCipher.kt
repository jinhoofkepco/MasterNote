package com.studyink.monitor.telegram

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Application-level encryption for bot-to-bot documents. Telegram cloud chats are transport only;
 * filenames and captions carry no page or student metadata and payload bytes are AES-256-GCM.
 */
object TelegramPeerPayloadCipher {
    const val MAX_PLAINTEXT_BYTES = 2L * 1_024L * 1_024L
    const val MAX_CIPHERTEXT_BYTES = 3L * 1_024L * 1_024L

    fun encrypt(
        plaintext: File,
        destination: File,
        key: ByteArray,
        associatedData: String,
    ): Long {
        require(plaintext.isFile && plaintext.canRead())
        require(plaintext.length() in 0..MAX_PLAINTEXT_BYTES) { "Peer payload exceeds 2 MiB." }
        validateKeyAndAad(key, associatedData)
        val iv = ByteArray(IV_BYTES).also(SECURE_RANDOM::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        }
        val temporary = temporaryFor(destination)
        try {
            FileOutputStream(temporary).use { rawOutput ->
                DataOutputStream(BufferedOutputStream(rawOutput, BUFFER_BYTES)).use { header ->
                    header.write(MAGIC)
                    header.writeByte(iv.size)
                    header.write(iv)
                    header.flush()
                    CipherOutputStream(header, cipher).use { encrypted ->
                        plaintext.inputStream().buffered(BUFFER_BYTES).use { input ->
                            input.copyTo(encrypted, BUFFER_BYTES)
                        }
                    }
                }
            }
            FileOutputStream(temporary, true).use { it.fd.sync() }
            require(temporary.length() <= MAX_CIPHERTEXT_BYTES) { "Encrypted peer payload exceeds 3 MiB." }
            AtomicDiskFile.replace(temporary, destination)
            return destination.length()
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    fun decrypt(
        ciphertext: File,
        destination: File,
        key: ByteArray,
        associatedData: String,
    ): Long {
        require(ciphertext.isFile && ciphertext.canRead())
        require(ciphertext.length() in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES) {
            "Encrypted peer payload size is invalid."
        }
        validateKeyAndAad(key, associatedData)
        val temporary = temporaryFor(destination)
        try {
            var written = 0L
            DataInputStream(BufferedInputStream(ciphertext.inputStream(), BUFFER_BYTES)).use { source ->
                val magic = ByteArray(MAGIC.size).also(source::readFully)
                require(magic.contentEquals(MAGIC)) { "Unsupported encrypted peer payload." }
                val ivSize = source.readUnsignedByte()
                require(ivSize == IV_BYTES)
                val iv = ByteArray(ivSize).also(source::readFully)
                val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
                    updateAAD(associatedData.toByteArray(Charsets.UTF_8))
                }
                FileOutputStream(temporary).use { rawOutput ->
                    BufferedOutputStream(rawOutput, BUFFER_BYTES).use { output ->
                        CipherInputStream(source, cipher).use { decrypted ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            while (true) {
                                val count = decrypted.read(buffer)
                                if (count < 0) break
                                written += count
                                require(written <= MAX_PLAINTEXT_BYTES) { "Peer payload exceeds 2 MiB." }
                                output.write(buffer, 0, count)
                            }
                        }
                        output.flush()
                    }
                }
            }
            FileOutputStream(temporary, true).use { it.fd.sync() }
            AtomicDiskFile.replace(temporary, destination)
            return written
        } catch (error: Throwable) {
            temporary.delete()
            // Keep authentication failures free of plaintext paths or Telegram identifiers.
            if (error is AEADBadTagException || error.cause is AEADBadTagException) {
                throw SecurityException("Peer payload authentication failed.")
            }
            throw error
        }
    }

    private fun validateKeyAndAad(key: ByteArray, associatedData: String) {
        require(key.size == 32) { "Peer key must be 256 bits." }
        require(associatedData.isNotBlank() && associatedData.length <= 512)
    }

    private fun temporaryFor(destination: File): File {
        val parent = requireNotNull(destination.parentFile)
        require(parent.mkdirs() || parent.isDirectory)
        return parent.resolve(destination.name + ".part").also { it.delete() }
    }

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val BUFFER_BYTES = 32 * 1_024
    private val MAGIC = byteArrayOf('M'.code.toByte(), 'N'.code.toByte(), 'E'.code.toByte(), 1)
    private const val MIN_CIPHERTEXT_BYTES = 4L + 1L + IV_BYTES + 16L
    private val SECURE_RANDOM = SecureRandom()
}
