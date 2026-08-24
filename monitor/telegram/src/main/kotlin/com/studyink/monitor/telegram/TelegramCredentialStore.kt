package com.studyink.monitor.telegram

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Adapted from FocusMonitor2's TelegramCredentialStore at commit e5809ebc. MasterNote stores the
 * ciphertext itself in noBackupFilesDir and authenticates every credential field as one payload.
 */
interface TelegramCredentialPersistence {
    fun save(credentials: TelegramCredentials)
    fun load(): TelegramCredentials?
    fun clear()
}

class TelegramCredentialStore(
    private val paths: TelegramStoragePaths,
) : TelegramCredentialPersistence {
    @Synchronized
    override fun save(credentials: TelegramCredentials) {
        val payload = listOf(
            credentials.botToken,
            credentials.allowedPrivateChatId.toString(),
            credentials.chatLabel,
            credentials.peerBotId?.toString().orEmpty(),
            credentials.peerBotUsername.orEmpty(),
            credentials.localBotId?.toString().orEmpty(),
            credentials.localBotUsername.orEmpty(),
            credentials.remoteReviewRole?.name.orEmpty(),
            credentials.peerPairId.orEmpty(),
            credentials.peerSharedKeyBase64.orEmpty(),
            credentials.peerPairingExpiresAtEpochMs?.toString().orEmpty(),
        ).joinToString("\n") { Base64.encodeToString(it.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key())
            updateAAD(AAD)
        }
        val encrypted = cipher.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
        val envelope = buildString {
            append(VERSION).append('\n')
            append(Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).append('\n')
            append(Base64.encodeToString(encrypted, Base64.NO_WRAP)).append('\n')
        }
        AtomicDiskFile.writeText(paths.credentialsFile, envelope)
    }

    @Synchronized
    override fun load(): TelegramCredentials? = runCatching {
        val lines = paths.credentialsFile.readLines(StandardCharsets.UTF_8)
        if (lines.size < 3 || lines[0] != VERSION) return null
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(lines[1], Base64.NO_WRAP)),
            )
            updateAAD(AAD)
        }
        val decoded = cipher.doFinal(Base64.decode(lines[2], Base64.NO_WRAP))
            .toString(StandardCharsets.UTF_8)
            .split('\n')
            .map { Base64.decode(it, Base64.NO_WRAP).toString(StandardCharsets.UTF_8) }
        // MNTG1 originally contained three fields. Accept that representation so installing the
        // bidirectional transport never invalidates an existing parent connection.
        if (decoded.size !in setOf(3, 5, 7, 11)) return null
        val peerId = decoded.getOrNull(3)?.takeIf(String::isNotBlank)?.toLongOrNull()
        val peerUsername = decoded.getOrNull(4)?.takeIf(String::isNotBlank)
        if ((peerId == null) != (peerUsername == null)) return null
        val localId = decoded.getOrNull(5)?.takeIf(String::isNotBlank)?.toLongOrNull()
        val localUsername = decoded.getOrNull(6)?.takeIf(String::isNotBlank)
        if ((localId == null) != (localUsername == null)) return null
        val role = decoded.getOrNull(7)?.takeIf(String::isNotBlank)?.let(RemoteReviewRole::valueOf)
        val pairId = decoded.getOrNull(8)?.takeIf(String::isNotBlank)
        val sharedKey = decoded.getOrNull(9)?.takeIf(String::isNotBlank)
        val expiresAt = decoded.getOrNull(10)?.takeIf(String::isNotBlank)?.toLongOrNull()
        if (listOf(role, pairId, sharedKey, expiresAt).count { it != null } !in setOf(0, 4)) return null
        TelegramCredentials(
            botToken = decoded[0],
            allowedPrivateChatId = decoded[1].toLong(),
            chatLabel = decoded[2],
            peerBotId = peerId,
            peerBotUsername = peerUsername?.let(::normalizeTelegramUsername),
            localBotId = localId,
            localBotUsername = localUsername?.let(::normalizeTelegramUsername),
            remoteReviewRole = role,
            peerPairId = pairId,
            peerSharedKeyBase64 = sharedKey,
            peerPairingExpiresAtEpochMs = expiresAt,
        )
    }.getOrNull()

    @Synchronized
    override fun clear() {
        paths.credentialsFile.delete()
        runCatching {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val VERSION = "MNTG1"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "masternote.remote-monitor.telegram.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val AAD = "MasterNote.TelegramCredentials.v1".toByteArray(StandardCharsets.UTF_8)
    }
}
