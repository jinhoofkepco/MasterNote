package com.studyink.annotation.storage

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import androidx.work.*
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

enum class ImportState { CREATED, COPYING, INVENTORY, DECODING, VALIDATING, VERIFYING_ASSETS, PROBING_DOCUMENT, WAITING_USER_CONFIRMATION, MATERIALIZING, COMMITTING, SUCCEEDED, FAILED, CANCELLED }
enum class ImportSourceType { RAW_PDF, RAW_IMAGE_ZIP, MATERNOTE_PACKAGE }
data class ImportSession(
    val id: String, val sourceUri: String, val folderId: String, val sourceType: ImportSourceType?, val state: ImportState,
    val current: Long, val total: Long, val title: String?, val bookId: String?, val revisionId: String?, val errorCode: String?, val errorDetail: String?,
)

class BookImportRepository internal constructor(private val context: Context, private val database: AnnotationDatabase, private val now: () -> Long = System::currentTimeMillis) {
    private val dao = database.importDao(); private val assets = ManagedAssetRepository(context, database, now)
    suspend fun create(uri: Uri, folderId: String): String {
        requireNotNull(database.libraryDao().folder(folderId)); val id = UUID.randomUUID().toString(); val t = now()
        dao.insert(ImportSessionEntity(id, uri.toString(), folderId, null, ImportState.CREATED.name, 0, 0, null, null, null, null, null, null, null, null, false, t, t, null)); return id
    }
    fun observe(id: String): Flow<ImportSession?> = dao.observe(id).map { it?.model() }
    suspend fun confirm(id: String) { val s=requireNotNull(dao.session(id)); require(s.state==ImportState.WAITING_USER_CONFIRMATION.name); dao.update(s.copy(confirmed=true,updatedAtEpochMillis=now())) }
    suspend fun cancel(id: String) { val s=requireNotNull(dao.session(id)); dao.update(s.copy(state=ImportState.CANCELLED.name,completedAtEpochMillis=now(),updatedAtEpochMillis=now())) }
    suspend fun resumeInterrupted(): List<String> = dao.interrupted().map { row -> dao.update(row.copy(state=ImportState.CREATED.name, updatedAtEpochMillis=now())); row.importSessionId }

    suspend fun process(id: String) = withContext(Dispatchers.IO) {
        var session = requireNotNull(dao.session(id)); if (session.state in setOf(ImportState.SUCCEEDED.name,ImportState.CANCELLED.name)) return@withContext
        try {
            if (session.managedAssetId == null) {
                update(session, ImportState.COPYING); val uri=Uri.parse(session.sourceUri)
                val imported=assets.importUri(uri); val type=when(imported.mimeType){"application/pdf"->ImportSourceType.RAW_PDF;"application/zip"->ImportSourceType.RAW_IMAGE_ZIP;else->error("IMPORT_UNSUPPORTED_TYPE")}
                session=requireNotNull(dao.session(id)).copy(managedAssetId=imported.assetId.value,detectedSourceType=type.name,progressCurrent=imported.byteSize,progressTotal=imported.byteSize,title=imported.originalFileName.substringBeforeLast('.'),updatedAtEpochMillis=now()); dao.update(session)
            }
            session=requireNotNull(dao.session(id)); val type=ImportSourceType.valueOf(requireNotNull(session.detectedSourceType))
            update(session, if(type==ImportSourceType.RAW_IMAGE_ZIP) ImportState.INVENTORY else ImportState.PROBING_DOCUMENT)
            val sourceHandle=assets.open(ManagedAssetId(requireNotNull(session.managedAssetId))); require((sourceHandle.asset.pageCount ?: 0)>0) { "IMPORT_DOCUMENT_PROBE_FAILED" }
            if (!session.confirmed) { update(requireNotNull(dao.session(id)),ImportState.WAITING_USER_CONFIRMATION); return@withContext }
            update(requireNotNull(dao.session(id)),ImportState.MATERIALIZING)
            val handle = if (type == ImportSourceType.RAW_IMAGE_ZIP) {
                val generated=java.io.File(context.cacheDir,"imports/$id.pdf")
                try { ImageZipImporter.materialize(sourceHandle.file,generated); val asset=java.io.FileInputStream(generated).use { assets.importStream(it,"${session.title ?: "images"}.pdf","application/pdf") }; assets.open(asset.assetId) } finally { generated.delete() }
            } else sourceHandle
            val bookId=session.bookId ?: UUID.randomUUID().toString(); val revisionId=session.revisionId ?: UUID.randomUUID().toString(); val documentId=documentIdentity(handle.file)
            update(requireNotNull(dao.session(id)).copy(bookId=bookId,revisionId=revisionId),ImportState.COMMITTING)
            database.withTransaction {
                check(database.learningDao().insertBookRevision(BookRevisionEntity(revisionId,bookId,documentId,1,sourceHandle.asset.sha256,session.title ?: "가져온 책",now())) != -1L)
                val activityId="$revisionId:all"; database.learningDao().insertActivity(LearningActivityEntity(activityId,revisionId,"전체 학습",0,"INK_AND_STRUCTURED"))
                database.learningDao().insertActivityPages((0 until requireNotNull(handle.asset.pageCount)).map { ActivityPageRefEntity(activityId,"$documentId:page:$it",it,it) })
                val library=LibraryRepository(database,now); library.ensureRoot(); library.registerBook(bookId,session.title ?: "가져온 책",revisionId,session.requestedFolderId)
            }
            val done=requireNotNull(dao.session(id)); dao.update(done.copy(state=ImportState.SUCCEEDED.name,bookId=bookId,revisionId=revisionId,updatedAtEpochMillis=now(),completedAtEpochMillis=now()))
        } catch (t: Throwable) { val failed=requireNotNull(dao.session(id)); dao.update(failed.copy(state=ImportState.FAILED.name,errorCode=t.message?.takeIf { it.startsWith("IMPORT_") } ?: "IMPORT_FAILED",errorDetail=t.message,updatedAtEpochMillis=now(),completedAtEpochMillis=now())); throw t }
    }
    private suspend fun update(value: ImportSessionEntity,state:ImportState){dao.update(value.copy(state=state.name,updatedAtEpochMillis=now()))}
    private fun documentIdentity(file: java.io.File): String { val d=MessageDigest.getInstance("SHA-256"); d.update(Uri.fromFile(file).normalizeScheme().toString().toByteArray()); d.update(0); FileInputStream(file).use{input->val b=ByteArray(64*1024);while(true){val n=input.read(b);if(n<0)break;d.update(b,0,n)}};return d.digest().joinToString(""){"%02x".format(it)} }
}
private fun ImportSessionEntity.model()=ImportSession(importSessionId,sourceUri,requestedFolderId,detectedSourceType?.let(ImportSourceType::valueOf),ImportState.valueOf(state),progressCurrent,progressTotal,title,bookId,revisionId,errorCode,errorDetail)

class BookImportWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params){
    override suspend fun doWork(): Result { val id=inputData.getString(KEY)?:return Result.failure(); return runCatching { val db=AnnotationDatabase.open(applicationContext); try { BookImportRepository(applicationContext,db).process(id) } finally { db.close() }; Result.success() }.getOrElse { Result.failure(workDataOf("error" to (it.message?:"failed"))) } }
    companion object { const val KEY="importSessionId" }
}
object BookImportScheduler {
    fun enqueue(context:Context,id:String)=WorkManager.getInstance(context).enqueueUniqueWork("import-book:$id",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<BookImportWorker>().setInputData(workDataOf(BookImportWorker.KEY to id)).build())
}
