package com.studyink.reader

import com.studyink.core.model.AttemptId
import com.studyink.core.model.BookRevisionId
import com.studyink.core.model.PageId
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSceneTest {
    @Test fun sceneRejectsMoreThanOneEditableLayer() {
        val error = runCatching {
            ReaderScene.create(
                BookRevisionId("revision"), PageId("page"),
                listOf(
                    EditableLiveLayer(LiveLayerTarget.StudentAttempt(AttemptId("a"))),
                    EditableLiveLayer(LiveLayerTarget.StudentAttempt(AttemptId("b"))),
                ),
                ReaderInteractionPolicy.EDIT,
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test fun observationSceneCannotSmuggleAnEditableLayer() {
        val error = runCatching {
            ReaderScene.create(
                BookRevisionId("revision"), PageId("page"),
                listOf(EditableLiveLayer(LiveLayerTarget.StudentAttempt(AttemptId("a")))),
                ReaderInteractionPolicy.OBSERVE,
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
