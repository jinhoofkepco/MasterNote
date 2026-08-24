package com.studyink.monitor.telegram

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

interface TelegramBotApi : AutoCloseable {
    fun getMe(): TelegramBotIdentity
    fun deleteWebhook()
    fun getUpdates(offset: Long, timeoutSeconds: Int = 50): List<TelegramInboundUpdate>
    fun sendMessage(chatId: Long, text: String): TelegramSendResult
    fun sendDocument(
        chatId: Long,
        document: File,
        caption: String,
        mimeType: String,
        displayName: String,
    ): TelegramSendResult
    fun sendVoice(
        chatId: Long,
        voice: File,
        caption: String,
        mimeType: String,
        displayName: String,
    ): TelegramSendResult
    fun sendPeerMessage(peerUsername: String, text: String): TelegramSendResult =
        throw UnsupportedOperationException("Peer bot messaging is unavailable.")
    fun sendPeerDocument(
        peerUsername: String,
        document: File,
        caption: String,
        mimeType: String,
        displayName: String,
    ): TelegramSendResult = throw UnsupportedOperationException("Peer bot documents are unavailable.")
    fun downloadFile(fileId: String, destination: File, maxBytes: Long): TelegramDownloadedFile =
        throw UnsupportedOperationException("Telegram file download is unavailable.")
    override fun close() = Unit
}

/**
 * Small synchronous Bot API client adapted from FocusMonitor2 TelegramBotApi at commit e5809ebc.
 * Invoke it only from worker threads. Files are streamed and never buffered as a whole.
 */
class HttpTelegramBotApi(
    botToken: String,
    endpointRoot: String = "https://api.telegram.org",
) : TelegramBotApi {
    private val baseUrl = endpointRoot.trimEnd('/') + "/bot" + botToken + "/"
    private val fileBaseUrl = endpointRoot.trimEnd('/') + "/file/bot" + botToken + "/"
    private val closed = AtomicBoolean(false)
    private val activeConnections = ConcurrentHashMap.newKeySet<HttpURLConnection>()

    override fun getMe(): TelegramBotIdentity {
        val result = postForm("getMe", emptyMap()).body.getJSONObject("result")
        return TelegramBotIdentity(
            id = result.getLong("id"),
            username = result.optString("username").takeIf(String::isNotBlank),
            displayName = listOfNotNull(
                result.optString("first_name").takeIf(String::isNotBlank),
                result.optString("last_name").takeIf(String::isNotBlank),
            ).joinToString(" ").ifBlank { "Telegram bot" },
        )
    }

    override fun deleteWebhook() {
        postForm("deleteWebhook", mapOf("drop_pending_updates" to "false"))
    }

    override fun getUpdates(offset: Long, timeoutSeconds: Int): List<TelegramInboundUpdate> {
        // Telegram supports a negative offset during setup to tail the queue and forget history.
        require(offset >= -100L)
        require(timeoutSeconds in 0..50)
        val result = postForm(
            "getUpdates",
            mapOf(
                "offset" to offset.toString(),
                "timeout" to timeoutSeconds.toString(),
                "allowed_updates" to "[\"message\"]",
            ),
            readTimeoutMs = (timeoutSeconds + 10) * 1_000,
        ).body.getJSONArray("result")
        return buildList(result.length()) {
            repeat(result.length()) { index ->
                val update = result.getJSONObject(index)
                add(parseTelegramInboundUpdate(update))
            }
        }
    }

    override fun sendMessage(chatId: Long, text: String): TelegramSendResult {
        require(text.isNotBlank())
        require(text.length <= MAX_MESSAGE_CHARS) { "Telegram text exceeds $MAX_MESSAGE_CHARS characters." }
        return postForm(
            "sendMessage",
            mapOf(
                "chat_id" to chatId.toString(),
                "text" to text,
                "protect_content" to "true",
            ),
        ).result()
    }

    override fun sendDocument(
        chatId: Long,
        document: File,
        caption: String,
        mimeType: String,
        displayName: String,
    ): TelegramSendResult = multipart(
        method = "sendDocument",
        destination = chatId.toString(),
        field = "document",
        file = document,
        caption = caption,
        mimeType = mimeType,
        displayName = displayName,
    )

    override fun sendVoice(
        chatId: Long,
        voice: File,
        caption: String,
        mimeType: String,
        displayName: String,
    ): TelegramSendResult = multipart(
        method = "sendVoice",
        destination = chatId.toString(),
        field = "voice",
        file = voice,
        caption = caption,
        mimeType = mimeType,
        displayName = displayName,
    )

    override fun sendPeerMessage(peerUsername: String, text: String): TelegramSendResult {
        require(text.isNotBlank())
        require(text.length <= MAX_MESSAGE_CHARS)
        return postForm(
            "sendMessage",
            mapOf(
                "chat_id" to "@${normalizeTelegramUsername(peerUsername)}",
                "text" to text,
                "protect_content" to "false",
            ),
        ).result()
    }

    override fun sendPeerDocument(
        peerUsername: String,
        document: File,
        caption: String,
        mimeType: String,
        displayName: String,
    ): TelegramSendResult = multipart(
        method = "sendDocument",
        destination = "@${normalizeTelegramUsername(peerUsername)}",
        field = "document",
        file = document,
        caption = caption,
        mimeType = mimeType,
        displayName = displayName,
        // The recipient bot must be able to call getFile. Confidentiality comes from AES-GCM,
        // while the existing human-parent documents remain protected_content=true.
        protectContent = false,
    )

    override fun downloadFile(fileId: String, destination: File, maxBytes: Long): TelegramDownloadedFile {
        require(fileId.isNotBlank() && fileId.length <= 512)
        require(maxBytes in 1..MAX_DOWNLOAD_BYTES)
        val result = postForm("getFile", mapOf("file_id" to fileId)).body.getJSONObject("result")
        val telegramPath = result.optString("file_path").takeIf(String::isNotBlank)
            ?: throw TelegramApiException(502, "Telegram did not return a file path.", indicatesConnectionFailure = true)
        require(!telegramPath.startsWith('/') && !telegramPath.contains("..")) { "Unsafe Telegram file path." }
        result.optLong("file_size").takeIf { result.has("file_size") }?.let { declared ->
            if (declared > maxBytes) throw TelegramApiException(413, "Telegram file exceeds the receive limit.")
        }
        val parent = requireNotNull(destination.parentFile)
        require(parent.mkdirs() || parent.isDirectory)
        val temporary = parent.resolve(destination.name + ".part")
        runCatching { temporary.delete() }
        val connection = openAbsolute(fileBaseUrl + telegramPath).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = DOWNLOAD_TIMEOUT_MS
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val body = connection.errorStream?.let(::readLimited).orEmpty()
                throw TelegramApiResponsePolicy.httpFailure(
                    status,
                    body,
                    runCatching { JSONObject(body) }.getOrNull(),
                )
            }
            connection.contentLengthLong.takeIf { it >= 0L }?.let { length ->
                if (length > maxBytes) throw TelegramApiException(413, "Telegram file exceeds the receive limit.")
            }
            var count = 0L
            try {
                FileOutputStream(temporary).use { rawOutput ->
                    BufferedInputStream(connection.inputStream, STREAM_CHUNK_BYTES).use { input ->
                        BufferedOutputStream(rawOutput, STREAM_CHUNK_BYTES).use { output ->
                            val buffer = ByteArray(STREAM_CHUNK_BYTES)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                count += read
                                if (count > maxBytes) {
                                    throw TelegramApiException(413, "Telegram file exceeds the receive limit.")
                                }
                                output.write(buffer, 0, read)
                            }
                            output.flush()
                        }
                    }
                }
                FileOutputStream(temporary, true).use { it.fd.sync() }
                AtomicDiskFile.replace(temporary, destination)
            } catch (error: Throwable) {
                temporary.delete()
                throw error
            }
            return TelegramDownloadedFile(destination, count, telegramPath)
        } finally {
            closeConnection(connection)
        }
    }

    private fun multipart(
        method: String,
        destination: String,
        field: String,
        file: File,
        caption: String,
        mimeType: String,
        displayName: String,
        protectContent: Boolean = true,
    ): TelegramSendResult {
        require(file.isFile && file.canRead()) { "Upload file is missing or unreadable." }
        require(caption.length <= MAX_CAPTION_CHARS) { "Telegram caption is too long." }
        require(MIME_TYPE.matches(mimeType)) { "Invalid MIME type." }
        val boundary = "----MasterNote${UUID.randomUUID()}"
        val connection = open(method).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = UPLOAD_TIMEOUT_MS
            setChunkedStreamingMode(STREAM_CHUNK_BYTES)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            BufferedOutputStream(connection.outputStream, STREAM_CHUNK_BYTES).use { output ->
                fun textPart(name: String, value: String) {
                    output.write("--$boundary\r\n".toByteArray(StandardCharsets.UTF_8))
                    output.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
                    output.write(value.toByteArray(StandardCharsets.UTF_8))
                    output.write("\r\n".toByteArray(StandardCharsets.UTF_8))
                }
                textPart("chat_id", destination)
                textPart("caption", caption)
                textPart("protect_content", protectContent.toString())
                output.write("--$boundary\r\n".toByteArray(StandardCharsets.UTF_8))
                output.write(
                    "Content-Disposition: form-data; name=\"$field\"; filename=\"${safeFilename(displayName)}\"\r\n"
                        .toByteArray(StandardCharsets.UTF_8),
                )
                output.write("Content-Type: $mimeType\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
                file.inputStream().buffered(STREAM_CHUNK_BYTES).use { input ->
                    input.copyTo(output, STREAM_CHUNK_BYTES)
                }
                output.write("\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8))
            }
            return parseEnvelope(connection).result()
        } finally {
            closeConnection(connection)
        }
    }

    private fun postForm(
        method: String,
        values: Map<String, String>,
        readTimeoutMs: Int = REQUEST_TIMEOUT_MS,
    ): ApiEnvelope {
        val body = values.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
            .toByteArray(StandardCharsets.UTF_8)
        val connection = open(method).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setFixedLengthStreamingMode(body.size)
        }
        try {
            BufferedOutputStream(connection.outputStream).use { it.write(body) }
            return parseEnvelope(connection)
        } finally {
            closeConnection(connection)
        }
    }

    private fun parseEnvelope(connection: HttpURLConnection): ApiEnvelope {
        val status = connection.responseCode
        val input = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = input?.let(::readLimited).orEmpty()
        val parsed = runCatching { JSONObject(body) }.getOrNull()
        if (status !in 200..299) {
            throw TelegramApiResponsePolicy.httpFailure(status, body, parsed)
        }
        val json = parsed ?: throw TelegramApiResponsePolicy.invalidSuccessBody(status, body)
        if (!json.optBoolean("ok")) {
            throw TelegramApiResponsePolicy.httpFailure(status, body, json)
        }
        return ApiEnvelope(json)
    }

    private fun readLimited(input: InputStream): String {
        BufferedInputStream(input).use { source ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1_024)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_RESPONSE_BYTES) {
                    throw TelegramApiException(
                        502,
                        "Telegram response exceeded the safety limit.",
                        indicatesConnectionFailure = true,
                    )
                }
                output.write(buffer, 0, count)
            }
            return output.toString(StandardCharsets.UTF_8.name())
        }
    }

    private fun open(method: String): HttpURLConnection {
        check(!closed.get()) { "TelegramBotApi is closed." }
        val connection = URL(baseUrl + method).openConnection() as HttpURLConnection
        activeConnections += connection
        if (closed.get()) {
            closeConnection(connection)
            error("TelegramBotApi is closed.")
        }
        return connection
    }

    private fun openAbsolute(url: String): HttpURLConnection {
        check(!closed.get()) { "TelegramBotApi is closed." }
        val connection = URL(url).openConnection() as HttpURLConnection
        activeConnections += connection
        if (closed.get()) {
            closeConnection(connection)
            error("TelegramBotApi is closed.")
        }
        return connection
    }

    private fun closeConnection(connection: HttpURLConnection) {
        activeConnections.remove(connection)
        connection.disconnect()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeConnections.toList().forEach(::closeConnection)
    }
    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun safeFilename(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(120)
        .ifBlank { "attachment.bin" }

    private data class ApiEnvelope(val body: JSONObject) {
        fun result() = TelegramSendResult(body.optJSONObject("result")?.optLong("message_id"))
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val REQUEST_TIMEOUT_MS = 30_000
        const val UPLOAD_TIMEOUT_MS = 90_000
        const val DOWNLOAD_TIMEOUT_MS = 90_000
        const val STREAM_CHUNK_BYTES = 32 * 1_024
        const val MAX_RESPONSE_BYTES = 1 * 1_024 * 1_024
        const val MAX_MESSAGE_CHARS = 4_096
        const val MAX_CAPTION_CHARS = 1_024
        const val MAX_DOWNLOAD_BYTES = 3L * 1_024L * 1_024L
        val MIME_TYPE = Regex("^[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+$")
    }
}

internal fun parseTelegramInboundUpdate(update: JSONObject): TelegramInboundUpdate {
    val message = update.optJSONObject("message")
    val chat = message?.optJSONObject("chat")
    val sender = message?.optJSONObject("from")
    val document = message?.optJSONObject("document")
    val reply = message?.optJSONObject("reply_to_message")
    return TelegramInboundUpdate(
        updateId = update.getLong("update_id"),
        messageId = message?.optLong("message_id"),
        chatId = chat?.optLong("id"),
        chatType = chat?.optString("type")?.takeIf(String::isNotBlank),
        text = message?.optString("text")?.takeIf(String::isNotBlank),
        senderIsBot = sender?.optBoolean("is_bot") == true,
        senderDisplayName = sender?.let(::telegramDisplayName),
        senderUsername = sender?.optString("username")?.takeIf(String::isNotBlank),
        sentAtEpochSeconds = message?.optLong("date"),
        senderId = sender?.optLong("id"),
        caption = message?.optString("caption")?.takeIf(String::isNotBlank),
        document = document?.let { value ->
            val fileId = value.optString("file_id")
            val uniqueId = value.optString("file_unique_id")
            if (fileId.isBlank() || uniqueId.isBlank()) null else TelegramInboundDocument(
                fileId = fileId,
                fileUniqueId = uniqueId,
                fileName = value.optString("file_name").takeIf(String::isNotBlank),
                mimeType = value.optString("mime_type").takeIf(String::isNotBlank),
                fileSizeBytes = value.optLong("file_size").takeIf { value.has("file_size") },
            )
        },
        replyToMessageId = reply?.optLong("message_id"),
    )
}

private fun telegramDisplayName(sender: JSONObject): String = listOfNotNull(
    sender.optString("first_name").takeIf(String::isNotBlank),
    sender.optString("last_name").takeIf(String::isNotBlank),
).joinToString(" ").ifBlank { sender.optString("username", "Telegram") }

/** JVM-testable response policy; descriptions are bounded and never include request URLs/tokens. */
internal object TelegramApiResponsePolicy {
    fun httpFailure(statusCode: Int, rawBody: String, parsed: JSONObject?): TelegramApiException {
        val description = parsed?.optString("description")?.takeIf(String::isNotBlank)
        val retryAfter = parsed?.optJSONObject("parameters")?.optLong("retry_after")
            ?.takeIf { it > 0L }
        val reason = description?.let(::safeDescription) ?: if (rawBody.isBlank()) {
            "Telegram HTTP $statusCode · empty response"
        } else {
            "Telegram HTTP $statusCode · unreadable response (${rawBody.toByteArray().size} bytes)"
        }
        return TelegramApiException(
            statusCode = statusCode,
            message = reason,
            retryAfterSeconds = retryAfter,
            indicatesConnectionFailure = statusCode in 500..599,
        )
    }

    fun invalidSuccessBody(statusCode: Int, body: String): TelegramApiException =
        TelegramApiException(
            statusCode,
            if (body.isBlank()) "Telegram returned an empty response."
            else "Telegram returned an invalid response (${body.toByteArray().size} bytes).",
            indicatesConnectionFailure = true,
        )

    fun safeDescription(value: String): String = value
        .replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { "Telegram API request failed" }
        .take(160)
}
