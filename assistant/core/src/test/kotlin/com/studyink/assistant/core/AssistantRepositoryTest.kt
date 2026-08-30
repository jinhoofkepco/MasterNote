package com.studyink.assistant.core

import com.studyink.core.model.MasterNoteDataCommitBus
import com.studyink.core.model.MasterNoteOptionalDataRootGuard
import com.studyink.core.model.PageBounds
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AssistantRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun promptsAlwaysHaveFourSlotsAndEditsSurviveReload() {
        val root = temporaryFolder.newFolder("prompts")
        val repository = repository(root)

        val seeds = repository.promptSlots()
        assertEquals(4, seeds.size)
        assertEquals(listOf(1, 2, 3, 4), seeds.map(AssistantPromptSlot::slotNumber))
        assertTrue(seeds.first().body.endsWith("HTML 태그는 작성하지 마."))

        val beforeCommit = MasterNoteDataCommitBus.currentGeneration()
        val edited = repository.updatePromptSlot(2, "나의 질문", "학생의 풀이를 분석해줘.")
        assertEquals(1L, edited.revision)
        assertEquals(beforeCommit + 1L, MasterNoteDataCommitBus.currentGeneration())

        val reloaded = repository(root).promptSlots()
        assertEquals(4, reloaded.size)
        assertEquals("학생의 풀이를 분석해줘.", reloaded[1].body)
        assertEquals(seeds[0], reloaded[0])
        assertEquals(seeds[2], reloaded[2])
        assertEquals(seeds[3], reloaded[3])
        assertThrows(IllegalArgumentException::class.java) {
            repository.updatePromptSlot(5, "없음", "없음")
        }
    }

    @Test
    fun stalePromptEditorCannotOverwriteANewerSavedRevision() {
        val repository = repository(temporaryFolder.newFolder("prompt-cas"))
        val opened = repository.promptSlot(1)
        val newer = repository.updatePromptSlot(
            slotNumber = 1,
            title = opened.title,
            body = "먼저 저장된 새 질문",
            expectedRevision = opened.revision,
        )

        assertThrows(IllegalStateException::class.java) {
            repository.updatePromptSlot(
                slotNumber = 1,
                title = opened.title,
                body = "뒤늦게 도착한 오래된 질문",
                expectedRevision = opened.revision,
            )
        }
        assertEquals(newer, repository.promptSlot(1))
    }

    @Test
    fun teacherResourcesArePageScopedAndOldRevisionsStayReadable() {
        val root = temporaryFolder.newFolder("teacher")
        val repository = repository(root)
        val page = AssistantPageKey("book", 94)
        val otherPage = AssistantPageKey("book", 95)

        val resource = repository.createTeacherResource(
            page = page,
            title = "94쪽 설명",
            selectionBounds = bounds(),
            promptSlotNumber = 3,
            answerText = "첫 번째 풀이",
            answerHtml = "<p>첫 번째 풀이</p>",
            providerName = "ChatGPT WebView",
        )
        val first = resource.currentRevision
        val second = repository.appendTeacherResourceRevision(
            page = page,
            resourceId = resource.resourceId,
            selectionBounds = PageBounds(120f, 220f, 360f, 440f),
            promptSlotNumber = 4,
            answerText = "교사가 고친 풀이",
        )

        assertNotEquals(first.revisionId, second.revisionId)
        assertEquals(1L, first.revisionNumber)
        assertEquals(2L, second.revisionNumber)
        assertEquals(
            "첫 번째 풀이",
            repository.teacherResourceRevision(page, resource.resourceId, first.revisionId)?.answerText,
        )
        assertEquals(
            "교사가 고친 풀이",
            repository(root).teacherResource(page, resource.resourceId)?.currentRevision?.answerText,
        )
        assertTrue(repository.listTeacherResources(otherPage).isEmpty())
        assertNull(repository.teacherResource(otherPage, resource.resourceId))
    }

    @Test
    fun answerFormatIsCallerSelectableAndSurvivesReload() {
        val root = temporaryFolder.newFolder("teacher-answer-format")
        val repository = repository(root)
        val page = AssistantPageKey("book", 94)

        val markdownResource = repository.createTeacherResource(
            page = page,
            title = "수식 풀이",
            selectionBounds = bounds(),
            promptSlotNumber = 3,
            answerText = "## 풀이\n\n\$x^2\$",
            answerFormat = TeacherGptAnswerFormat.MARKDOWN_TEX,
        )
        assertEquals(TeacherGptAnswerFormat.MARKDOWN_TEX, markdownResource.currentRevision.answerFormat)

        val edited = repository.appendTeacherResourceRevision(
            page = page,
            resourceId = markdownResource.resourceId,
            selectionBounds = bounds(),
            promptSlotNumber = 3,
            answerText = "## 수정 풀이\n\n\$x = 2\$",
            answerFormat = TeacherGptAnswerFormat.MARKDOWN_TEX,
        )
        assertEquals(TeacherGptAnswerFormat.MARKDOWN_TEX, edited.answerFormat)
        assertEquals(
            TeacherGptAnswerFormat.MARKDOWN_TEX,
            repository(root).teacherResource(page, markdownResource.resourceId)?.currentRevision?.answerFormat,
        )

        val legacyDefault = repository.createTeacherResource(page, "일반 메모", bounds(), 1, "일반 답변")
        assertEquals(TeacherGptAnswerFormat.PLAIN_TEXT, legacyDefault.currentRevision.answerFormat)
    }

    @Test
    fun missingAnswerFormatInLegacyJsonDecodesAsPlainText() {
        val root = temporaryFolder.newFolder("legacy-answer-format")
        val repository = repository(root)
        val page = AssistantPageKey("book", 95)
        val resource = repository.createTeacherResource(
            page = page,
            title = "예전 답변",
            selectionBounds = bounds(),
            promptSlotNumber = 1,
            answerText = "저장된 답변",
            answerFormat = TeacherGptAnswerFormat.MARKDOWN_TEX,
        )
        val file = repository.teacherPageFileForTest(page)
        val legacyJson = JSONObject(file.readText(Charsets.UTF_8)).also { rootJson ->
            rootJson.getJSONArray("resources")
                .getJSONObject(0)
                .getJSONArray("revisions")
                .getJSONObject(0)
                .remove("answerFormat")
        }
        file.writeText(legacyJson.toString(), Charsets.UTF_8)

        assertEquals(
            TeacherGptAnswerFormat.PLAIN_TEXT,
            repository(root).teacherResource(page, resource.resourceId)?.currentRevision?.answerFormat,
        )
    }

    @Test
    fun unknownStoredAnswerFormatIsRejected() {
        val root = temporaryFolder.newFolder("invalid-answer-format")
        val repository = repository(root)
        val page = AssistantPageKey("book", 96)
        repository.createTeacherResource(page, "답변", bounds(), 1, "내용")
        val encoded = JSONObject(repository.teacherPageFileForTest(page).readText(Charsets.UTF_8)).also {
            it.getJSONArray("resources")
                .getJSONObject(0)
                .getJSONArray("revisions")
                .getJSONObject(0)
                .put("answerFormat", "UNKNOWN_FORMAT")
        }.toString().toByteArray(Charsets.UTF_8)

        assertThrows(IllegalArgumentException::class.java) {
            AssistantJsonCodec.decodeTeacherPage(encoded, page, AssistantStorageLimits())
        }
    }

    @Test
    fun malformedTeacherPageDoesNotHideAnotherPage() {
        val root = temporaryFolder.newFolder("teacher-corruption")
        val repository = repository(root)
        val pageOne = AssistantPageKey("book", 97)
        val pageTwo = AssistantPageKey("book", 98)
        repository.createTeacherResource(pageOne, "97", bounds(), 1, "97쪽 답변")
        repository.createTeacherResource(pageTwo, "98", bounds(), 1, "98쪽 답변")

        repository.teacherPageFileForTest(pageOne).writeText("{broken", Charsets.UTF_8)
        val reloaded = repository(root)

        assertTrue(reloaded.listTeacherResources(pageOne).isEmpty())
        assertEquals(1, reloaded.listTeacherResources(pageTwo).size)
        assertTrue(
            File(root, "gpt-assistant-v1/teacher-pages").walkTopDown()
                .any { it.name.contains(".corrupt-") },
        )
    }

    @Test
    fun studentLayerReplacementIsMonotonicDigestCheckedAndIdempotent() {
        val sourceRoot = temporaryFolder.newFolder("student-source")
        val source = repository(sourceRoot)
        val page = AssistantPageKey("book", 94)
        val resource = source.createTeacherResource(page, "설명", bounds(), 1, "긴 답변")
        val card = source.newStudentExplanationCard(
            page = page,
            sourceResourceId = resource.resourceId,
            sourceResourceRevisionId = resource.currentRevisionId,
            title = "힌트",
            text = "이 항을 먼저 묶어 보세요.",
            anchorBounds = bounds(),
        )
        val target = StudentExplanationTarget(page, attemptNo = 4)
        val first = source.replaceStudentExplanationCards(target, listOf(card), expectedRevision = 0L)
        assertEquals(1L, first.revision)
        assertTrue(Regex("[0-9a-f]{64}").matches(first.digestSha256))
        assertEquals(first, source.replaceStudentExplanationCards(target, listOf(card)))
        val pending = source.pendingStudentExplanationPublications().single()
        assertEquals(target, pending.target)
        assertEquals(first.revision, pending.revision)
        assertEquals(first.digestSha256, pending.digestSha256)
        assertFalse(source.pendingPublicationFileForTest().readText().contains(card.text))
        assertEquals(pending, repository(sourceRoot).pendingStudentExplanationPublications().single())
        val advanced = requireNotNull(
            source.advancePendingStudentExplanationDeliveryAttempt(pending.publicationId),
        )
        assertEquals(1L, advanced.deliveryAttempt)
        assertEquals(1L, repository(sourceRoot).pendingStudentExplanationPublications().single().deliveryAttempt)
        assertFalse(
            source.resolvePendingStudentExplanationPublication(
                pending.publicationId,
                target,
                first.revision,
                "0".repeat(64),
            ),
        )
        assertTrue(
            source.resolvePendingStudentExplanationPublication(
                pending.publicationId,
                target,
                first.revision,
                first.digestSha256,
            ),
        )
        assertEquals(first, source.replaceStudentExplanationCards(target, listOf(card)))
        assertEquals(pending.publicationId, source.pendingStudentExplanationPublications().single().publicationId)

        val destination = repository(temporaryFolder.newFolder("student-destination"))
        val checkpoint = source.exportStudentExplanationLayer(target)
        assertEquals(
            StudentLayerApplyStatus.APPLIED,
            destination.applyStudentExplanationLayer(target, checkpoint).status,
        )
        assertEquals(
            StudentLayerApplyStatus.ALREADY_CURRENT,
            destination.applyStudentExplanationLayer(target, checkpoint).status,
        )

        val conflictingCard = card.copy(text = "같은 revision의 다른 내용")
        val conflict = first.copy(
            digestSha256 = StudentExplanationDigest.sha256(target, listOf(conflictingCard)),
            cards = listOf(conflictingCard),
        )
        assertEquals(
            StudentLayerApplyStatus.CONFLICT,
            destination.applyStudentExplanationLayer(target, conflict).status,
        )

        val second = source.replaceStudentExplanationCards(target, listOf(card.copy(text = "수정")))
        assertEquals(StudentLayerApplyStatus.APPLIED, destination.applyStudentExplanationLayer(target, second).status)
        assertEquals(StudentLayerApplyStatus.STALE, destination.applyStudentExplanationLayer(target, first).status)
        assertThrows(IllegalArgumentException::class.java) {
            destination.applyStudentExplanationLayer(
                StudentExplanationTarget(page, attemptNo = 3),
                second,
            )
        }
    }

    @Test
    fun startupRecoversLayerCommittedBeforeIntentButNotAnAcknowledgedLayer() {
        val root = temporaryFolder.newFolder("publication-crash-recovery")
        val source = repository(root)
        val page = AssistantPageKey("book", 95)
        val resource = source.createTeacherResource(page, "설명", bounds(), 1, "답")
        val card = source.newStudentExplanationCard(
            page, resource.resourceId, resource.currentRevisionId, "힌트", "복구할 설명", bounds(),
        )
        val target = StudentExplanationTarget(page, 2)
        val layer = source.replaceStudentExplanationCards(target, listOf(card))
        val pendingFile = source.pendingPublicationFileForTest()
        listOf(pendingFile, File(pendingFile.path + ".bak"), File(pendingFile.path + ".new"))
            .forEach(File::delete)

        val afterCrash = repository(root)
        val recovered = afterCrash.pendingStudentExplanationPublications().single()
        assertEquals(layer.revision, recovered.revision)
        assertEquals(layer.digestSha256, recovered.digestSha256)
        assertEquals(1, afterCrash.publicationRecoveryScanCountForTest())
        afterCrash.pendingStudentExplanationPublications()
        assertEquals(1, afterCrash.publicationRecoveryScanCountForTest())

        assertTrue(
            afterCrash.resolvePendingStudentExplanationPublication(
                recovered.publicationId,
                target,
                recovered.revision,
                recovered.digestSha256,
            ),
        )
        assertTrue(afterCrash.pendingStudentExplanationPublications().isEmpty())
        val afterAcknowledgedRestart = repository(root)
        assertTrue(afterAcknowledgedRestart.pendingStudentExplanationPublications().isEmpty())
        assertEquals(1, afterAcknowledgedRestart.publicationRecoveryScanCountForTest())
    }

    @Test
    fun authorityEpochReplacesOldRevisionNamespaceButConflictsWithinOneAuthority() {
        val source = repository(temporaryFolder.newFolder("authority-source"))
        val page = AssistantPageKey("book", 2)
        val resource = source.createTeacherResource(page, "설명", bounds(), 1, "답")
        val card = source.newStudentExplanationCard(
            page, resource.resourceId, resource.currentRevisionId, "설명", "첫 권위", bounds(),
        )
        val target = StudentExplanationTarget(page, 1)
        val destinationRoot = temporaryFolder.newFolder("authority-destination")
        val destination = repository(destinationRoot)
        val oldAuthority = source.replaceStudentExplanationCards(target, listOf(card)).copy(
            revision = 99L,
            authorityEpoch = "a".repeat(64),
        )
        assertEquals(StudentLayerApplyStatus.APPLIED, destination.applyStudentExplanationLayer(target, oldAuthority).status)

        val newCard = card.copy(text = "새 권위")
        val newAuthority = StudentExplanationLayer(
            target = target,
            revision = 1L,
            digestSha256 = StudentExplanationDigest.sha256(target, listOf(newCard)),
            cards = listOf(newCard),
            authorityEpoch = "b".repeat(64),
        )
        assertEquals(StudentLayerApplyStatus.APPLIED, destination.applyStudentExplanationLayer(target, newAuthority).status)
        assertEquals("새 권위", destination.studentExplanationLayer(target).cards.single().text)
        assertEquals(setOf(oldAuthority.authorityEpoch), destination.studentExplanationLayer(target).retiredAuthorityEpochs)

        assertEquals(
            StudentLayerApplyStatus.STALE,
            destination.applyStudentExplanationLayer(target, oldAuthority).status,
        )
        assertEquals("새 권위", destination.studentExplanationLayer(target).cards.single().text)
        val afterRestart = repository(destinationRoot)
        assertEquals(
            StudentLayerApplyStatus.STALE,
            afterRestart.applyStudentExplanationLayer(target, oldAuthority).status,
        )
        assertEquals("새 권위", afterRestart.studentExplanationLayer(target).cards.single().text)

        val conflictCard = newCard.copy(text = "충돌")
        val conflict = newAuthority.copy(
            digestSha256 = StudentExplanationDigest.sha256(target, listOf(conflictCard)),
            cards = listOf(conflictCard),
        )
        assertEquals(StudentLayerApplyStatus.CONFLICT, afterRestart.applyStudentExplanationLayer(target, conflict).status)
    }

    @Test
    fun teacherResourceRecordsTheExactPromptSnapshotUsedForTheRequest() {
        val repository = repository(temporaryFolder.newFolder("prompt-snapshot"))
        val page = AssistantPageKey("book", 7)
        repository.updatePromptSlot(1, "현재 제목", "현재 저장소 문구")

        val resource = repository.createTeacherResource(
            page = page,
            title = "설명",
            selectionBounds = bounds(),
            promptSlotNumber = 1,
            answerText = "답변",
            promptTitleSnapshot = "실제 전송 제목",
            promptBodySnapshot = "실제로 전송한 질문",
        )

        assertEquals("실제 전송 제목", resource.currentRevision.promptTitle)
        assertEquals("실제로 전송한 질문", resource.currentRevision.promptBody)
    }

    @Test
    fun teacherAuthorityEpochIsDurableAndIndependentFromLayerRevision() {
        val root = temporaryFolder.newFolder("teacher-authority")
        val first = repository(root).teacherAuthorityEpoch()
        val afterRestart = repository(root).teacherAuthorityEpoch()

        assertTrue(Regex("[0-9a-f]{64}").matches(first))
        assertEquals(first, afterRestart)
    }

    @Test
    fun publicationLimitFailsBeforeLayerOrIntentCommit() {
        val root = temporaryFolder.newFolder("publish-limit")
        val repository = repository(root)
        val page = AssistantPageKey("book", 3)
        val resource = repository.createTeacherResource(page, "설명", bounds(), 1, "답")
        val target = StudentExplanationTarget(page, 1)
        val text = "가".repeat(90_000) // 270 KiB UTF-8 per card would exceed the per-card bound.
        assertThrows(IllegalArgumentException::class.java) {
            repository.newStudentExplanationCard(
                page, resource.resourceId, resource.currentRevisionId, "설명", text, bounds(),
            )
        }

        val boundedText = "x".repeat(180_000)
        val base = repository.newStudentExplanationCard(
            page, resource.resourceId, resource.currentRevisionId, "설명", boundedText, bounds(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            repository.replaceStudentExplanationCards(
                target,
                listOf(base, base.copy(cardId = "card-two"), base.copy(cardId = "card-three")),
            )
        }
        assertEquals(0L, repository.studentExplanationLayer(target).revision)
        assertTrue(repository.pendingStudentExplanationPublications().isEmpty())

        val control = repository.newStudentExplanationCard(
            page, resource.resourceId, resource.currentRevisionId, "설명", "금지\u0001문자", bounds(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            repository.replaceStudentExplanationCards(target, listOf(control))
        }
        assertEquals(0L, repository.studentExplanationLayer(target).revision)
        assertTrue(repository.pendingStudentExplanationPublications().isEmpty())
    }

    @Test
    fun optionalRootGuardBlocksAssistantAtomicWritesDuringSnapshot() {
        val root = temporaryFolder.newFolder("stable-root")
        val repository = repository(root)
        val guardEntered = CountDownLatch(1)
        val releaseGuard = CountDownLatch(1)
        val writeFinished = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            pool.submit {
                MasterNoteOptionalDataRootGuard.withStableDataRoot(root) {
                    guardEntered.countDown()
                    releaseGuard.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue(guardEntered.await(5, TimeUnit.SECONDS))
            pool.submit {
                repository.updatePromptSlot(2, "잠금", "백업 뒤 기록")
                writeFinished.countDown()
            }
            assertFalse(writeFinished.await(150, TimeUnit.MILLISECONDS))
            releaseGuard.countDown()
            assertTrue(writeFinished.await(5, TimeUnit.SECONDS))
        } finally {
            releaseGuard.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun studentLayerCorruptionIsLimitedToOneExactAttempt() {
        val root = temporaryFolder.newFolder("student-corruption")
        val repository = repository(root)
        val page = AssistantPageKey("book", 99)
        val resource = repository.createTeacherResource(page, "설명", bounds(), 1, "답변")
        val card = repository.newStudentExplanationCard(
            page,
            resource.resourceId,
            resource.currentRevisionId,
            "설명",
            "공개할 내용",
            bounds(),
        )
        val attemptOne = StudentExplanationTarget(page, 1)
        val attemptTwo = StudentExplanationTarget(page, 2)
        repository.replaceStudentExplanationCards(attemptOne, listOf(card))
        repository.replaceStudentExplanationCards(attemptTwo, listOf(card.copy(cardId = "card-two")))
        repository.studentLayerFileForTest(attemptOne).writeText("not-json")

        val reloaded = repository(root)
        assertEquals(0L, reloaded.studentExplanationLayer(attemptOne).revision)
        assertTrue(reloaded.studentExplanationLayer(attemptOne).cards.isEmpty())
        assertEquals(1L, reloaded.studentExplanationLayer(attemptTwo).revision)
        assertEquals("공개할 내용", reloaded.studentExplanationLayer(attemptTwo).cards.single().text)
    }

    @Test
    fun boundedValidationRejectsOversizedContentBeforeCommit() {
        val root = temporaryFolder.newFolder("limits")
        val repository = repository(
            root = root,
            limits = AssistantStorageLimits(maxAnswerTextUtf8Bytes = 8),
        )
        val before = MasterNoteDataCommitBus.currentGeneration()

        assertThrows(IllegalArgumentException::class.java) {
            repository.createTeacherResource(
                page = AssistantPageKey("book", 1),
                title = "설명",
                selectionBounds = bounds(),
                promptSlotNumber = 1,
                answerText = "123456789",
            )
        }
        assertEquals(before, MasterNoteDataCommitBus.currentGeneration())
        assertFalse(File(root, "gpt-assistant-v1/teacher-pages").exists())
    }

    @Test
    fun removingOneTeacherResourceKeepsOtherResourcesAndPublishedStudentCards() {
        val root = temporaryFolder.newFolder("remove-one-teacher-resource")
        val repository = repository(root)
        val page = AssistantPageKey("book", 13)
        val removed = repository.createTeacherResource(page, "삭제할 자료", bounds(), 1, "원문")
        val kept = repository.createTeacherResource(page, "남길 자료", bounds(), 2, "다른 원문")
        val target = StudentExplanationTarget(page, 1)
        val card = repository.newStudentExplanationCard(
            page,
            removed.resourceId,
            removed.currentRevisionId,
            "이미 보낸 설명",
            "학생에게 보낸 내용",
            bounds(),
        )
        repository.replaceStudentExplanationCards(target, listOf(card))

        assertTrue(repository.removeTeacherResource(page, removed.resourceId))

        val reloaded = repository(root)
        assertNull(reloaded.teacherResource(page, removed.resourceId))
        assertEquals(listOf(kept.resourceId), reloaded.listTeacherResources(page).map { it.resourceId })
        assertEquals("학생에게 보낸 내용", reloaded.studentExplanationLayer(target).cards.single().text)
        assertTrue(reloaded.pendingStudentExplanationPublications().isNotEmpty())
    }

    private fun repository(
        root: File,
        limits: AssistantStorageLimits = AssistantStorageLimits(),
    ): AssistantRepository {
        val clock = AtomicInteger(1_000)
        val ids = AtomicInteger(0)
        return AssistantRepository(
            rootDirectory = root,
            limits = limits,
            nowEpochMillis = { clock.incrementAndGet().toLong() },
            newUuid = { "assistant-id-${ids.incrementAndGet()}" },
        )
    }

    private fun bounds(): PageBounds = PageBounds(100f, 200f, 300f, 400f)
}
