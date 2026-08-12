package com.studyink.annotation.storage

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

@JvmInline value class ManagedAssetId(val value: String)

data class ManagedAsset(
    val assetId: ManagedAssetId,
    val sha256: String,
    val mimeType: String,
    val originalFileName: String,
    val byteSize: Long,
    val relativePath: String,
    val widthPx: Int?,
    val heightPx: Int?,
    val pageCount: Int?,
    val createdAtEpochMillis: Long,
    val lastVerifiedAtEpochMillis: Long,
)

data class AssetHandle(val asset: ManagedAsset, val file: File)
sealed interface AssetVerificationResult {
    data object Valid : AssetVerificationResult
    data class Invalid(val reason: String) : AssetVerificationResult
}

fun interface AssetImportFaultInjector {
    fun afterFileCommitted(file: File)
    companion object { val NONE = AssetImportFaultInjector {} }
}

class ManagedAssetRepository internal constructor(
    context: Context,
    private val database: AnnotationDatabase,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val faultInjector: AssetImportFaultInjector = AssetImportFaultInjector.NONE,
) {
    private val appContext = context.applicationContext
    private val dao = database.managedAssetDao()
    private val root = File(appContext.filesDir, "managed-assets/v1")
    private val staging = File(root, ".staging")
    private val hashLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun importUri(uri: Uri, originalFileName: String? = null): ManagedAsset = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        val name = originalFileName ?: queryName(uri) ?: "imported-asset"
        val declared = resolver.getType(uri)
        resolver.openInputStream(uri)?.use { input -> importStream(input, name, declared) }
            ?: error("선택한 파일을 열 수 없습니다")
    }

    suspend fun importStream(input: InputStream, originalFileName: String, declaredMimeType: String? = null): ManagedAsset =
        withContext(Dispatchers.IO) {
            staging.mkdirs()
            val stage = File(staging, "${UUID.randomUUID()}.part")
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            try {
                FileOutputStream(stage).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        size += read
                        require(size <= MAX_ASSET_BYTES) { "자산 크기 제한을 초과했습니다" }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
                require(size > 0) { "빈 파일은 가져올 수 없습니다" }
                val hash = digest.digest().toHex()
                val lock = hashLocks.getOrPut(hash) { Mutex() }
                try {
                    lock.withLock {
                        dao.assetByHash(hash)?.let { existing ->
                            stage.delete()
                            return@withContext existing.toDomain()
                        }
                        val inspected = AssetInspector.inspect(stage, declaredMimeType)
                        val extension = inspected.mimeType.extension()
                        val relative = "${hash.take(2)}/$hash.$extension"
                        val finalFile = File(root, relative)
                        finalFile.parentFile?.mkdirs()
                        atomicMove(stage, finalFile)
                        faultInjector.afterFileCommitted(finalFile)
                        val now = nowEpochMillis()
                        val entity = ManagedAssetEntity(
                            assetId = UUID.randomUUID().toString(), sha256 = hash,
                            mimeType = inspected.mimeType,
                            originalFileName = originalFileName.take(MAX_FILE_NAME_CHARS), byteSize = size,
                            relativePath = relative, widthPx = inspected.widthPx, heightPx = inspected.heightPx,
                            pageCount = inspected.pageCount, createdAtEpochMillis = now, lastVerifiedAtEpochMillis = now,
                        )
                        dao.insert(entity)
                        entity.toDomain()
                    }
                } finally {
                    hashLocks.remove(hash, lock)
                }
            } finally {
                stage.delete()
            }
        }

    suspend fun open(assetId: ManagedAssetId): AssetHandle = withContext(Dispatchers.IO) {
        val asset = requireNotNull(dao.asset(assetId.value)) { "자산을 찾을 수 없습니다" }.toDomain()
        val file = safeFile(asset.relativePath)
        require(file.isFile) { "자산 파일이 없습니다" }
        AssetHandle(asset, file)
    }

    suspend fun verify(assetId: ManagedAssetId): AssetVerificationResult = withContext(Dispatchers.IO) {
        val row = dao.asset(assetId.value) ?: return@withContext AssetVerificationResult.Invalid("자산 메타데이터 없음")
        val file = runCatching { safeFile(row.relativePath) }.getOrElse {
            return@withContext AssetVerificationResult.Invalid("잘못된 자산 경로")
        }
        if (!file.isFile || file.length() != row.byteSize) return@withContext AssetVerificationResult.Invalid("파일 크기 불일치")
        val hash = FileInputStream(file).use(::sha256)
        if (hash != row.sha256) return@withContext AssetVerificationResult.Invalid("SHA-256 불일치")
        runCatching { AssetInspector.inspect(file, row.mimeType) }.getOrElse {
            return@withContext AssetVerificationResult.Invalid(it.message ?: "자산 디코딩 실패")
        }
        check(dao.markVerified(assetId.value, nowEpochMillis()) == 1)
        AssetVerificationResult.Valid
    }

    suspend fun collectGarbage(gracePeriodMillis: Long = DEFAULT_GC_GRACE_MILLIS): Int = withContext(Dispatchers.IO) {
        val known = dao.all().mapTo(hashSetOf()) { safeFile(it.relativePath).canonicalPath }
        val cutoff = nowEpochMillis() - gracePeriodMillis
        var deleted = 0
        root.walkTopDown().filter { it.isFile && it.parentFile != staging }.forEach { file ->
            if (file.canonicalPath !in known && file.lastModified() <= cutoff && file.delete()) deleted++
        }
        staging.listFiles()?.filter { it.lastModified() <= cutoff }?.forEach { if (it.delete()) deleted++ }
        deleted
    }

    fun close() = database.close()

    private fun safeFile(relativePath: String): File {
        require(!relativePath.startsWith('/') && !relativePath.contains(".."))
        val file = File(root, relativePath).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator))
        return file
    }

    private fun queryName(uri: Uri): String? = appContext.contentResolver.query(
        uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    companion object {
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val MAX_FILE_NAME_CHARS = 240
        private const val MAX_ASSET_BYTES = 2L * 1024 * 1024 * 1024
        const val DEFAULT_GC_GRACE_MILLIS = 24L * 60 * 60 * 1000
        fun open(context: Context) = ManagedAssetRepository(context, AnnotationDatabase.open(context))
    }
}

private data class InspectedAsset(val mimeType: String, val widthPx: Int? = null, val heightPx: Int? = null, val pageCount: Int? = null)

private object AssetInspector {
    fun inspect(file: File, declaredMimeType: String?): InspectedAsset {
        val header = FileInputStream(file).use { input -> ByteArray(16).also { input.read(it) } }
        val mime = when {
            header.startsWithAscii("%PDF-") -> "application/pdf"
            header[0] == 0x50.toByte() && header[1] == 0x4b.toByte() -> "application/zip"
            header.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) -> "image/png"
            header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() -> "image/jpeg"
            header.startsWithAscii("RIFF") && header.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> error("지원하지 않거나 손상된 파일 형식입니다: ${declaredMimeType ?: "unknown"}")
        }
        return when (mime) {
            "application/pdf" -> inspectPdf(file)
            "application/zip" -> inspectZip(file)
            else -> inspectImage(file, mime)
        }
    }

    private fun inspectPdf(file: File): InspectedAsset {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return descriptor.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                require(renderer.pageCount > 0) { "페이지가 없는 PDF입니다" }
                InspectedAsset("application/pdf", pageCount = renderer.pageCount)
            }
        }
    }

    private fun inspectImage(file: File, mime: String): InspectedAsset {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "이미지를 디코딩할 수 없습니다" }
        return InspectedAsset(mime, options.outWidth, options.outHeight)
    }

    private fun inspectZip(file: File): InspectedAsset {
        var entries = 0
        var expanded = 0L
        val names = hashSetOf<String>()
        var containsPackageManifest = false
        var containsNonImagePackageEntry = false
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(file))).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++entries <= MAX_ZIP_ENTRIES) { "ZIP 항목이 너무 많습니다" }
                    require(!entry.isDirectory) { "ZIP에는 이미지 파일만 허용됩니다" }
                    val name = entry.name.replace('\\', '/')
                    require(!name.startsWith('/') && name.split('/').none { it == ".." }) { "안전하지 않은 ZIP 경로입니다" }
                    require(names.add(name)) { "ZIP에 중복 파일명이 있습니다" }
                    val isOrder = name == "page-order.json"
                    val isImage = name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp")
                    val isManifest = name == "manifest.json"
                    val isPackageAsset = name.matches(Regex("assets/[0-9a-f]{64}\\.(pdf|png|jpg|jpeg|webp)"))
                    val isPreview = name == "metadata/preview.png"
                    require(isOrder || isImage || isManifest || isPackageAsset || isPreview) {
                        "ZIP에는 PNG, JPEG, WebP만 허용됩니다"
                    }
                    if (isManifest) containsPackageManifest = true
                    if (!isOrder && !isImage) containsNonImagePackageEntry = true
                    val signature = ByteArray(16)
                    var signatureBytes = 0
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        if (signatureBytes < signature.size) {
                            val copied = minOf(read, signature.size - signatureBytes)
                            buffer.copyInto(signature, signatureBytes, 0, copied)
                            signatureBytes += copied
                        }
                        expanded += read
                        require(expanded <= MAX_ZIP_EXPANDED_BYTES) { "ZIP 압축 해제 크기가 너무 큽니다" }
                    }
                    if (isImage) require(isAllowedImageHeader(signature)) { "ZIP 안에 손상되었거나 지원하지 않는 이미지가 있습니다" }
                    zip.closeEntry()
                }
            }
        } catch (error: ZipException) {
            throw IllegalArgumentException("암호화되었거나 손상된 ZIP입니다", error)
        }
        require(entries > 0) { "빈 ZIP입니다" }
        require(expanded <= maxOf(file.length() * MAX_ZIP_RATIO, MIN_ZIP_RATIO_ALLOWANCE)) { "ZIP 압축 비율이 비정상적입니다" }
        if (containsPackageManifest) return InspectedAsset("application/vnd.maternote.book+zip")
        require(!containsNonImagePackageEntry) { "manifest가 없는 콘텐츠 패키지입니다" }
        return InspectedAsset("application/zip", pageCount = entries - if ("page-order.json" in names) 1 else 0)
    }

    private fun isAllowedImageHeader(bytes: ByteArray) =
        bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) ||
            (bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) ||
            (bytes.startsWithAscii("RIFF") && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP")

    private const val MAX_ZIP_ENTRIES = 10_000
    private const val MAX_ZIP_EXPANDED_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_ZIP_RATIO = 200L
    private const val MIN_ZIP_RATIO_ALLOWANCE = 16L * 1024 * 1024
}

private fun ByteArray.startsWithAscii(text: String): Boolean =
    size >= text.length && copyOfRange(0, text.length).contentEquals(text.toByteArray(Charsets.US_ASCII))
private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
private fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count > 0) digest.update(buffer, 0, count)
    }
    return digest.digest().toHex()
}
private fun atomicMove(source: File, target: File) {
    try {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), target.toPath())
    }
}
private fun String.extension() = when (this) {
    "application/pdf" -> "pdf"
    "application/zip" -> "zip"
    "application/vnd.maternote.book+zip" -> "mnote"
    "image/png" -> "png"
    "image/jpeg" -> "jpg"
    "image/webp" -> "webp"
    else -> error("지원하지 않는 MIME: $this")
}
private fun ManagedAssetEntity.toDomain() = ManagedAsset(
    ManagedAssetId(assetId), sha256, mimeType, originalFileName, byteSize, relativePath,
    widthPx, heightPx, pageCount, createdAtEpochMillis, lastVerifiedAtEpochMillis,
)
