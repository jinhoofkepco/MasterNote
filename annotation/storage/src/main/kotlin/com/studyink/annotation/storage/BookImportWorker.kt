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
                val imported=assets.importUri(uri); val type=when(imported.mimeType){"application/pdf"->ImportSourceType.RAW_PDF;"application/zip"->ImportSourceType.RAW_IMAGE_ZIP;"application/vnd.maternote.book+zip"->ImportSourceType.MATERNOTE_PACKAGE;else->error("IMPORT_UNSUPPORTED_TYPE")}
                session=requireNotNull(dao.session(id)).copy(managedAssetId=imported.assetId.value,detectedSourceType=type.name,progressCurrent=imported.byteSize,progressTotal=imported.byteSize,title=imported.originalFileName.substringBeforeLast('.'),updatedAtEpochMillis=now()); dao.update(session)
            }
            session=requireNotNull(dao.session(id)); val type=ImportSourceType.valueOf(requireNotNull(session.detectedSourceType))
            if (type == ImportSourceType.MATERNOTE_PACKAGE) { processPackage(id, session); return@withContext }
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
                database.libraryDao().insertRevisionSource(LibraryRevisionSourceEntity(revisionId,handle.asset.assetId.value,sourceHandle.asset.assetId.value,type.name,"1.0",null,null,now()))
                val activityId="$revisionId:all"; database.learningDao().insertActivity(LearningActivityEntity(activityId,revisionId,"전체 학습",0,"INK_AND_STRUCTURED"))
                database.learningDao().insertActivityPages((0 until requireNotNull(handle.asset.pageCount)).map { ActivityPageRefEntity(activityId,"$documentId:page:$it",it,it) })
                val library=LibraryRepository(database,now); library.ensureRoot(); library.registerBook(bookId,session.title ?: "가져온 책",revisionId,session.requestedFolderId)
            }
            val done=requireNotNull(dao.session(id)); dao.update(done.copy(state=ImportState.SUCCEEDED.name,bookId=bookId,revisionId=revisionId,updatedAtEpochMillis=now(),completedAtEpochMillis=now()))
        } catch (t: Throwable) { val failed=requireNotNull(dao.session(id)); dao.update(failed.copy(state=ImportState.FAILED.name,errorCode=t.message?.takeIf { it.startsWith("IMPORT_") } ?: "IMPORT_FAILED",errorDetail=t.message,updatedAtEpochMillis=now(),completedAtEpochMillis=now())); throw t }
    }
    private suspend fun update(value: ImportSessionEntity,state:ImportState){dao.update(value.copy(state=state.name,updatedAtEpochMillis=now()))}
    private suspend fun processPackage(id:String, initial:ImportSessionEntity) {
        update(initial,ImportState.VALIDATING); val source=assets.open(ManagedAssetId(requireNotNull(initial.managedAssetId))); val prepared=MaternotePackageImporter.validate(source.file); val m=prepared.manifest
        var session=requireNotNull(dao.session(id)).copy(packageId=m.packageId,bookId=m.book.bookId,revisionId=m.book.revisionId,title=m.book.title,updatedAtEpochMillis=now());dao.update(session)
        val existing=database.learningDao().bookRevision(m.book.revisionId)
        if(existing!=null){require(existing.contentHash==source.asset.sha256){"IMPORT_REVISION_CONFLICT"};LibraryRepository(database,now).registerBook(m.book.bookId,m.book.title,m.book.revisionId,session.requestedFolderId);dao.update(session.copy(state=ImportState.SUCCEEDED.name,completedAtEpochMillis=now(),updatedAtEpochMillis=now()));return}
        if(!session.confirmed){update(session,ImportState.WAITING_USER_CONFIRMATION);return}
        update(session,ImportState.VERIFYING_ASSETS);val imported=MaternotePackageImporter.importAssets(source.file,assets,m);val documentAsset=requireNotNull(imported[m.document.assetId]);require(documentAsset.mimeType=="application/pdf"){"IMPORT_UNSUPPORTED_DOCUMENT"}
        val documentHandle=assets.open(documentAsset.assetId);val pageCount=requireNotNull(documentAsset.pageCount);m.pages.forEach{p->require(p.source.type=="pdfPage"&&(p.source.pageIndex?:-1) in 0 until pageCount){"PKG_PAGE_INDEX_OUT_OF_RANGE:${p.pageId}"}}
        val current=database.libraryDao().book(m.book.bookId);if(current!=null&&m.book.previousRevisionId!=null)require(current.currentRevisionId==m.book.previousRevisionId){"IMPORT_REVISION_CHAIN_MISMATCH"}
        update(requireNotNull(dao.session(id)),ImportState.COMMITTING);val documentId=documentIdentity(documentHandle.file);val t=now()
        database.withTransaction {
            database.learningDao().insertBookRevision(BookRevisionEntity(m.book.revisionId,m.book.bookId,documentId,m.book.revisionNumber,source.asset.sha256,m.book.title,t))
            database.libraryDao().insertRevisionSource(LibraryRevisionSourceEntity(m.book.revisionId,documentAsset.assetId.value,source.asset.assetId.value,ImportSourceType.MATERNOTE_PACKAGE.name,"${m.formatVersion.major}.${m.formatVersion.minor}",m.packageId,m.book.previousRevisionId,t))
            m.activities.forEach{a->database.learningDao().insertActivity(LearningActivityEntity(a.activityId,m.book.revisionId,a.title,a.position,a.submissionMode));database.learningDao().insertActivityPages(a.pageIds.mapIndexed{i,pageId->val page=requireNotNull(m.pages.find{it.pageId==pageId});ActivityPageRefEntity(a.activityId,pageId,requireNotNull(page.source.pageIndex),i)})}
            database.teacherDao().insertTeacher(TeacherProfileEntity("package-author","Package Author",t))
            m.answerDocuments.forEach{a->val asset=requireNotNull(imported[a.assetId]);database.answerDao().insertDocument(AnswerDocumentEntity(a.answerDocumentId,m.book.revisionId,asset.assetId.value,if(asset.mimeType=="application/pdf")"PDF" else "IMAGE_SEQUENCE",a.type,asset.pageCount?:1,a.answerDocumentId,true,t))}
            m.answerLinks.forEachIndexed{i,a->database.answerDao().insertLink(AnswerPageLinkEntity(a.linkId,m.book.revisionId,a.answerDocumentId,null,a.problemPageId,null,null,null,null,a.answerPageIndex,null,null,null,null,i,t,t))}
            m.teachingResources.forEach{r->val rid="${m.book.revisionId}:${r.resourceId}";val revision="$rid:1";database.teachingResourceDao().insertResource(TeachingResourceEntity(rid,m.book.revisionId,r.type,"GENERAL",r.title,"TEACHER_ONLY","PUBLISHED","CONTENT_PACKAGE",revision,"package-author",t,t));database.teachingResourceDao().insertRevision(TeachingResourceRevisionEntity(revision,rid,1,r.text,null,r.imageAssetId?.let{requireNotNull(imported[it]).assetId.value},null,"Maternote Package",t))}
            m.pageResourceLinks.forEachIndexed{i,l->database.teachingResourceDao().insertLink(BookPageResourceLinkEntity(l.linkId,m.book.revisionId,l.pageId,"${m.book.revisionId}:${l.resourceId}",null,null,null,null,"PAGE_RESOURCE_LIST",i,t))}
            LibraryRepository(database,now).ensureRoot();LibraryRepository(database,now).registerBook(m.book.bookId,m.book.title,m.book.revisionId,session.requestedFolderId)
        }
        session=requireNotNull(dao.session(id));dao.update(session.copy(state=ImportState.SUCCEEDED.name,completedAtEpochMillis=now(),updatedAtEpochMillis=now()))
    }
    private fun documentIdentity(file: java.io.File): String { val d=MessageDigest.getInstance("SHA-256"); d.update(Uri.fromFile(file).normalizeScheme().toString().toByteArray()); d.update(0); FileInputStream(file).use{input->val b=ByteArray(64*1024);while(true){val n=input.read(b);if(n<0)break;d.update(b,0,n)}};return d.digest().joinToString(""){"%02x".format(it)} }
    fun close()=database.close()
    companion object{fun open(context:Context)=BookImportRepository(context,AnnotationDatabase.open(context))}
}
private fun ImportSessionEntity.model()=ImportSession(importSessionId,sourceUri,requestedFolderId,detectedSourceType?.let(ImportSourceType::valueOf),ImportState.valueOf(state),progressCurrent,progressTotal,title,bookId,revisionId,errorCode,errorDetail)

class BookImportWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params){
    override suspend fun doWork(): Result { val id=inputData.getString(KEY)?:return Result.failure(); return runCatching { val db=AnnotationDatabase.open(applicationContext); try { BookImportRepository(applicationContext,db).process(id) } finally { db.close() }; Result.success() }.getOrElse { Result.failure(workDataOf("error" to (it.message?:"failed"))) } }
    companion object { const val KEY="importSessionId" }
}
object BookImportScheduler {
    fun enqueue(context:Context,id:String) { WorkManager.getInstance(context).enqueueUniqueWork("import-book:$id",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<BookImportWorker>().setInputData(workDataOf(BookImportWorker.KEY to id)).build()) }
}
