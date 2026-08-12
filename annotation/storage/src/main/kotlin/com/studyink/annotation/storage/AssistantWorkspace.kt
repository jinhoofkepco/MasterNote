package com.studyink.annotation.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import java.util.UUID

enum class AssistantJobStatus { DRAFT, REQUEST_READY, EXTERNAL_OPENED, RESULT_IMPORTED, SAVED_AS_RESOURCE, CANCELLED, FAILED }
enum class AssistantProviderType { EXTERNAL_SHARE, CUSTOM_TAB, WEBVIEW_LAB, BACKEND, FAKE }
enum class AssistantRequestType { VOCABULARY, SENTENCE, EASY_EXPLANATION, EXAMPLES, PASSAGE, HINT, IMAGE }

data class PageSelection(
    val bookRevisionId: String,
    val pageId: String,
    val bounds: CanonicalRect,
    val includeBasePage: Boolean = true,
    val includeStudentInk: Boolean = false,
    val includeTeacherFeedback: Boolean = false,
)

data class AssistantJob(
    val id: String,
    val selection: PageSelection,
    val requestType: AssistantRequestType,
    val promptText: String,
    val imageAssetId: ManagedAssetId?,
    val provider: AssistantProviderType,
    val status: AssistantJobStatus,
)

@Dao
internal interface AssistantDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTemplate(entity: AssistantPromptTemplateEntity)
    @Query("SELECT * FROM assistant_prompt_templates WHERE templateId = :id") suspend fun template(id: String): AssistantPromptTemplateEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertJob(entity: AssistantJobEntity)
    @Query("SELECT * FROM assistant_jobs WHERE assistantJobId = :id") suspend fun job(id: String): AssistantJobEntity?
    @Query("SELECT * FROM assistant_jobs WHERE status IN ('DRAFT','REQUEST_READY','EXTERNAL_OPENED','RESULT_IMPORTED') ORDER BY createdAtEpochMillis DESC") suspend fun unfinishedJobs(): List<AssistantJobEntity>
    @Query("UPDATE assistant_jobs SET requestImageAssetId = :assetId, status = 'REQUEST_READY' WHERE assistantJobId = :id AND status = 'DRAFT'") suspend fun markReady(id: String, assetId: String): Int
    @Query("UPDATE assistant_jobs SET status = :status, externalOpenedAtEpochMillis = CASE WHEN :status = 'EXTERNAL_OPENED' THEN :now ELSE externalOpenedAtEpochMillis END, resultImportedAtEpochMillis = CASE WHEN :status = 'RESULT_IMPORTED' THEN :now ELSE resultImportedAtEpochMillis END WHERE assistantJobId = :id") suspend fun updateStatus(id: String, status: String, now: Long): Int
}

class AssistantWorkspace internal constructor(private val database: AnnotationDatabase, private val clock: () -> Long) {
    private val dao = database.assistantDao()

    suspend fun ensureDefaultTemplates() {
        defaultTemplates.forEach { dao.upsertTemplate(it) }
    }

    suspend fun prepareJob(selection: PageSelection, requestType: AssistantRequestType, templateId: String, variables: Map<String, String> = emptyMap()): String {
        check(database.learningDao().bookRevision(selection.bookRevisionId) != null)
        val template = dao.template(templateId) ?: error("Unknown prompt template")
        val prompt = variables.entries.fold(template.promptBody) { value, (key, replacement) -> value.replace("{{$key}}", replacement) }
        val id = UUID.randomUUID().toString()
        dao.insertJob(AssistantJobEntity(id, selection.bookRevisionId, selection.pageId, selection.bounds.left, selection.bounds.top, selection.bounds.right, selection.bounds.bottom, requestType.name, templateId, prompt, null, AssistantProviderType.EXTERNAL_SHARE.name, AssistantJobStatus.DRAFT.name, selection.includeStudentInk, selection.includeTeacherFeedback, clock(), null, null))
        return id
    }

    suspend fun attachRequestImage(jobId: String, assetId: ManagedAssetId) {
        val asset = database.managedAssetDao().asset(assetId.value) ?: error("Unknown asset")
        require(asset.mimeType in setOf("image/png", "image/jpeg", "image/webp"))
        check(dao.markReady(jobId, assetId.value) == 1)
    }

    suspend fun getJob(id: String): AssistantJob = requireNotNull(dao.job(id)).toModel()
    suspend fun unfinishedJobs(): List<AssistantJob> = dao.unfinishedJobs().map(AssistantJobEntity::toModel)
    suspend fun markExternalOpened(id: String) { check(dao.updateStatus(id, AssistantJobStatus.EXTERNAL_OPENED.name, clock()) == 1) }
    suspend fun markResultImported(id: String) { check(dao.updateStatus(id, AssistantJobStatus.RESULT_IMPORTED.name, clock()) == 1) }
    suspend fun markSavedAsResource(id: String) { check(dao.updateStatus(id, AssistantJobStatus.SAVED_AS_RESOURCE.name, clock()) == 1) }
    suspend fun cancel(id: String) { check(dao.updateStatus(id, AssistantJobStatus.CANCELLED.name, clock()) == 1) }
    fun close() = database.close()

    companion object {
        fun open(context: Context, clock: () -> Long = System::currentTimeMillis) = AssistantWorkspace(AnnotationDatabase.open(context), clock)
        private val defaultTemplates = listOf(
            AssistantPromptTemplateEntity("easy-child", "7세에게 쉽게 설명", "GENERAL", "첨부한 교재 영역을 7세 아이가 이해할 수 있게 쉬운 한국어로 설명해 주세요. 원문 철자는 보존하세요.", "TEXT", 1),
            AssistantPromptTemplateEntity("vocabulary", "어려운 단어 설명", "VOCABULARY", "첨부한 영역의 어려운 영어 단어를 골라 쉬운 뜻과 짧은 예문을 한국어와 영어로 설명해 주세요.", "TEXT", 1),
            AssistantPromptTemplateEntity("sentence", "문장 구조 설명", "SENTENCE", "첨부한 영어 문장의 구조를 초등학생이 이해할 수 있게 단계별로 설명해 주세요.", "TEXT", 1),
            AssistantPromptTemplateEntity("image", "그림 자료 요청", "GENERAL", "첨부한 내용을 설명하는 교육용 그림을 만들어 주세요. 학생 개인정보나 필기는 포함하지 마세요.", "IMAGE", 1),
        )
    }
}

private fun AssistantJobEntity.toModel() = AssistantJob(
    assistantJobId,
    PageSelection(bookRevisionId, pageId, CanonicalRect(selectionLeft, selectionTop, selectionRight, selectionBottom), true, includeStudentInk, includeTeacherFeedback),
    AssistantRequestType.valueOf(requestType), promptText, requestImageAssetId?.let(::ManagedAssetId), AssistantProviderType.valueOf(providerType), AssistantJobStatus.valueOf(status),
)
