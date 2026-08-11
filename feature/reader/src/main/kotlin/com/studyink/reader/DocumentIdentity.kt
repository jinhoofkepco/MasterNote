package com.studyink.reader

import android.content.Context
import android.net.Uri
import java.security.MessageDigest

internal object DocumentIdentity {
    fun create(context: Context, uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(uri.normalizeScheme().toString().toByteArray(Charsets.UTF_8))
        digest.update(0)
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
