package com.maternote.studio.project

import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class StudioProject(
    val projectId: String,
    val bookId: String,
    val currentRevisionDraftId: String,
    val title: String,
    val projectFormatVersion: Int = 1,
    val pages: List<PageDraft>,
    val activities: List<ActivityDraft>,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    companion object {
        fun fixture(title: String): StudioProject {
            val now = System.currentTimeMillis()
            val pages = (1..3).map { PageDraft("page-$it", it - 1, 1000, 1414, PageMode.SCAN_PAGE) }
            return StudioProject(
                projectId = UUID.randomUUID().toString(),
                bookId = UUID.randomUUID().toString(),
                currentRevisionDraftId = UUID.randomUUID().toString(),
                title = title,
                pages = pages,
                activities = listOf(
                    ActivityDraft("activity-1", "Unit 1", 0, listOf("page-1", "page-2")),
                    ActivityDraft("activity-2", "Unit 2", 1, listOf("page-3")),
                ),
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
        }
    }
}

@Serializable data class PageDraft(
    val stablePageId: String,
    val position: Int,
    val width: Int,
    val height: Int,
    val pageMode: PageMode,
    val sourceAssetPath: String? = null,
)

@Serializable data class ActivityDraft(
    val activityId: String,
    val title: String,
    val position: Int,
    val pageIds: List<String>,
)

@Serializable enum class PageMode { SCAN_PAGE, REBUILT_READING_PAGE, COMPOSITE_PAGE }

class StudioProjectStore {
    private val json = Json { encodeDefaults = true; explicitNulls = false; prettyPrint = true }
    fun write(file: File, project: StudioProject) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(json.encodeToString(StudioProject.serializer(), project))
        check(temporary.renameTo(file)) { "Could not commit project" }
    }
    fun read(file: File): StudioProject = json.decodeFromString(StudioProject.serializer(), file.readText())
}
