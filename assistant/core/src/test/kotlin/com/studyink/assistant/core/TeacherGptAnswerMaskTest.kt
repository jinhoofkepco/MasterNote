package com.studyink.assistant.core

import com.studyink.core.model.PageBounds
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TeacherGptAnswerMaskTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun legacyRevisionWithoutMaskDecodesWithFullVisibility() {
        val root = temporaryFolder.newFolder("legacy-mask")
        val repository = repository(root)
        val page = AssistantPageKey("book", 31)
        val resource = repository.createTeacherResource(page, "예전 답", bounds(), 1, "원문")

        val persisted = JSONObject(repository.teacherPageFileForTest(page).readText(Charsets.UTF_8))
        assertEquals(1, persisted.getInt("formatVersion"))
        assertFalse(
            persisted.getJSONArray("resources")
                .getJSONObject(0)
                .getJSONArray("revisions")
                .getJSONObject(0)
                .has("answerMask"),
        )
        assertNull(repository(root).teacherResource(page, resource.resourceId)?.currentRevision?.answerMask)
    }

    @Test
    fun createAndAppendMasksRoundTripAgainstTheirExactSources() {
        val root = temporaryFolder.newFolder("mask-roundtrip")
        val repository = repository(root)
        val page = AssistantPageKey("book", 32)
        val firstText = "# 풀이\n\n\$x^2 = 4\$\n\n따라서 2"
        val firstMask = TeacherGptAnswerMask.forAnswer(firstText, listOf(0, 2, 2))
        val resource = repository.createTeacherResource(
            page = page,
            title = "수식 답",
            selectionBounds = bounds(),
            promptSlotNumber = 2,
            answerText = firstText,
            answerFormat = TeacherGptAnswerFormat.MARKDOWN_TEX,
            answerMask = firstMask,
        )
        assertEquals(setOf(0, 2), resource.currentRevision.answerMask?.hiddenBlockOrdinals)

        val secondText = "## 고친 풀이\n\n\$x = -2, 2\$"
        val secondMask = TeacherGptAnswerMask.forAnswer(secondText, setOf(1))
        val appended = repository.appendTeacherResourceRevision(
            page = page,
            resourceId = resource.resourceId,
            selectionBounds = bounds(),
            promptSlotNumber = 2,
            answerText = secondText,
            answerFormat = TeacherGptAnswerFormat.MARKDOWN_TEX,
            answerMask = secondMask,
        )
        assertEquals(secondMask, appended.answerMask)

        val reloaded = requireNotNull(repository(root).teacherResource(page, resource.resourceId))
        assertEquals(firstMask, reloaded.revisions.first().answerMask)
        assertEquals(secondMask, reloaded.currentRevision.answerMask)
        assertTrue(reloaded.currentRevision.answerMask?.isValidFor(secondText) == true)
    }

    @Test
    fun malformedOrHashMismatchedMaskFailsOpenWithoutQuarantiningResource() {
        val root = temporaryFolder.newFolder("invalid-mask")
        val repository = repository(root)
        val hashPage = AssistantPageKey("book", 33)
        val shapePage = AssistantPageKey("book", 34)
        val hashResource = repository.createTeacherResource(
            hashPage,
            "hash",
            bounds(),
            1,
            "해시 원문",
            answerMask = TeacherGptAnswerMask.forAnswer("해시 원문", setOf(0)),
        )
        val shapeResource = repository.createTeacherResource(
            shapePage,
            "shape",
            bounds(),
            1,
            "구조 원문",
            answerMask = TeacherGptAnswerMask.forAnswer("구조 원문", setOf(0)),
        )
        mutateCurrentMask(repository.teacherPageFileForTest(hashPage)) { mask ->
            mask.put("sourceSha256", "0".repeat(64))
        }
        val shapeFile = repository.teacherPageFileForTest(shapePage)
        val malformed = JSONObject(shapeFile.readText(Charsets.UTF_8)).also { rootJson ->
            rootJson.getJSONArray("resources")
                .getJSONObject(0)
                .getJSONArray("revisions")
                .getJSONObject(0)
                .put("answerMask", "not-an-object")
        }
        shapeFile.writeText(malformed.toString(), Charsets.UTF_8)

        val reloaded = repository(root)
        val hashRevision = requireNotNull(reloaded.teacherResource(hashPage, hashResource.resourceId))
            .currentRevision
        val shapeRevision = requireNotNull(reloaded.teacherResource(shapePage, shapeResource.resourceId))
            .currentRevision
        assertEquals("해시 원문", hashRevision.answerText)
        assertEquals("구조 원문", shapeRevision.answerText)
        assertNull(hashRevision.answerMask)
        assertNull(shapeRevision.answerMask)
        assertFalse(
            File(root, "gpt-assistant-v1/teacher-pages").walkTopDown()
                .any { it.name.contains(".corrupt-") },
        )
    }

    @Test
    fun maskOnlyRevisionPreservesOriginalAnswerAndProvenance() {
        val root = temporaryFolder.newFolder("mask-only-revision")
        val repository = repository(root)
        val page = AssistantPageKey("book", 35)
        val originalText = "# 원문\n\n첫 단락\n\n\$a+b\$"
        val resource = repository.createTeacherResource(
            page = page,
            title = "보존 검사",
            selectionBounds = bounds(),
            promptSlotNumber = 4,
            answerText = originalText,
            answerHtml = "<p>원문</p>",
            providerName = "ChatGPT",
            promptTitleSnapshot = "질문 제목",
            promptBodySnapshot = "질문 본문",
            answerFormat = TeacherGptAnswerFormat.MARKDOWN_TEX,
        )
        val original = resource.currentRevision
        val mask = TeacherGptAnswerMask.forAnswer(original.answerText, setOf(1, 2))

        val masked = repository.appendTeacherResourceAnswerMaskRevision(
            page = page,
            resourceId = resource.resourceId,
            answerMask = mask,
        )

        assertEquals(original.answerText, masked.answerText)
        assertEquals(original.answerHtml, masked.answerHtml)
        assertEquals(original.answerFormat, masked.answerFormat)
        assertEquals(original.promptSlotNumber, masked.promptSlotNumber)
        assertEquals(original.promptTitle, masked.promptTitle)
        assertEquals(original.promptBody, masked.promptBody)
        assertEquals(original.selectionBounds, masked.selectionBounds)
        assertEquals(original.providerName, masked.providerName)
        assertEquals(mask, masked.answerMask)
        assertEquals(
            original,
            repository.teacherResourceRevision(page, resource.resourceId, original.revisionId),
        )

        val sameAgain = repository.appendTeacherResourceAnswerMaskRevision(
            page = page,
            resourceId = resource.resourceId,
            answerMask = mask,
        )
        assertEquals(masked, sameAgain)
        assertEquals(2, repository.teacherResource(page, resource.resourceId)?.revisions?.size)
    }

    @Test
    fun mismatchedMaskIsRejectedBeforeWritingAnUnexpectedFullAnswer() {
        val root = temporaryFolder.newFolder("mask-write-mismatch")
        val repository = repository(root)
        val page = AssistantPageKey("book", 36)
        val mismatched = TeacherGptAnswerMask.forAnswer("다른 원문", setOf(0))

        assertThrows(IllegalArgumentException::class.java) {
            repository.createTeacherResource(
                page = page,
                title = "저장 실패",
                selectionBounds = bounds(),
                promptSlotNumber = 1,
                answerText = "실제 원문",
                answerMask = mismatched,
            )
        }
        assertTrue(repository.listTeacherResources(page).isEmpty())
    }

    private fun mutateCurrentMask(file: File, mutate: (JSONObject) -> Unit) {
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val mask = json.getJSONArray("resources")
            .getJSONObject(0)
            .getJSONArray("revisions")
            .getJSONObject(0)
            .getJSONObject("answerMask")
        mutate(mask)
        file.writeText(json.toString(), Charsets.UTF_8)
    }

    private fun repository(root: File): AssistantRepository {
        val clock = AtomicInteger(10_000)
        val ids = AtomicInteger(0)
        return AssistantRepository(
            rootDirectory = root,
            nowEpochMillis = { clock.incrementAndGet().toLong() },
            newUuid = { "mask-id-${ids.incrementAndGet()}" },
        )
    }

    private fun bounds(): PageBounds = PageBounds(10f, 20f, 100f, 120f)
}
