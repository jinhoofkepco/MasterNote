package com.studyink.sync.lan

import android.net.Uri
import org.json.JSONObject

enum class LanPeerRole { STUDENT_SERVER, TEACHER_CLIENT }

data class PairingPayload(
    val host: String,
    val port: Int,
    val bookId: String,
    val token: String,
) {
    fun toUri(): Uri = Uri.Builder().scheme("masternote").authority("pair")
        .appendQueryParameter("host", host)
        .appendQueryParameter("port", port.toString())
        .appendQueryParameter("book", bookId)
        .appendQueryParameter("token", token)
        .build()

    companion object {
        fun parse(uri: Uri): PairingPayload {
            require(uri.scheme == "masternote" && uri.host == "pair")
            return PairingPayload(
                host = requireNotNull(uri.getQueryParameter("host")),
                port = requireNotNull(uri.getQueryParameter("port")).toInt(),
                bookId = requireNotNull(uri.getQueryParameter("book")),
                token = requireNotNull(uri.getQueryParameter("token")),
            )
        }
    }
}

internal object LanWire {
    const val PROTOCOL_VERSION = 1
    const val MAX_LINE_CHARS = 800_000

    fun message(type: String, configure: JSONObject.() -> Unit = {}): String = JSONObject()
        .put("protocolVersion", PROTOCOL_VERSION)
        .put("type", type)
        .apply(configure)
        .toString()

    fun decode(line: String): JSONObject {
        require(line.length <= MAX_LINE_CHARS) { "LAN message too large" }
        val root = JSONObject(line)
        require(root.getInt("protocolVersion") == PROTOCOL_VERSION) { "Unsupported LAN protocol" }
        return root
    }
}

object LanSyncBus {
    interface Listener {
        fun onLocalOperation(bookId: String, pageNumber: Int) {}
        fun onPageChanged(bookId: String, pageNumber: Int, revision: Long) {}
        fun onRemoteOperation(bookId: String, pageNumber: Int) {}
        fun onRemotePageChanged(bookId: String, pageNumber: Int) {}
        fun onPairingReady(bookId: String, pairingUri: String) {}
        fun onSessionIssue(message: String) {}
    }

    private val listeners = linkedSetOf<Listener>()

    @Synchronized fun addListener(listener: Listener) { listeners += listener }
    @Synchronized fun removeListener(listener: Listener) { listeners -= listener }
    @Synchronized fun operationWritten(bookId: String, pageNumber: Int) = listeners.toList().forEach {
        it.onLocalOperation(bookId, pageNumber)
    }
    @Synchronized fun pageChanged(bookId: String, pageNumber: Int, revision: Long) = listeners.toList().forEach {
        it.onPageChanged(bookId, pageNumber, revision)
    }
    @Synchronized internal fun remoteOperation(bookId: String, pageNumber: Int) = listeners.toList().forEach {
        it.onRemoteOperation(bookId, pageNumber)
    }
    @Synchronized internal fun remotePageChanged(bookId: String, pageNumber: Int) = listeners.toList().forEach {
        it.onRemotePageChanged(bookId, pageNumber)
    }
    @Synchronized internal fun pairingReady(bookId: String, pairingUri: String) = listeners.toList().forEach {
        it.onPairingReady(bookId, pairingUri)
    }
    @Synchronized internal fun sessionIssue(message: String) = listeners.toList().forEach {
        it.onSessionIssue(message)
    }
}
