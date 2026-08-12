package com.studyink.reader

class TeacherAccessSessionController(
    private val clock: () -> Long = System::currentTimeMillis,
    private val backgroundTimeoutMillis: Long = 5 * 60 * 1000L,
) {
    private var authenticated = false
    private var backgroundAt: Long? = null

    fun authenticated() { authenticated = true; backgroundAt = null }
    fun enteredBackground() { if (authenticated) backgroundAt = clock() }
    fun enteredForeground() { if (!isValid()) invalidate() else backgroundAt = null }
    fun isValid(): Boolean = authenticated &&
        (backgroundAt?.let { clock() - it <= backgroundTimeoutMillis } ?: true)
    fun invalidate() { authenticated = false; backgroundAt = null }
}

object TeacherRouteAccess {
    val session = TeacherAccessSessionController()
}

internal fun ReaderScene.requiresTeacherAccess(): Boolean = visibleLayerSources.any { source ->
    when (source) {
        is EditableLiveLayer -> source.target is LiveLayerTarget.TeacherPreparation || source.target is LiveLayerTarget.TeacherFeedback
        is ReadOnlyLiveLayer -> true
        is ReadOnlySnapshot -> source.target is SnapshotTarget.PublishedReview
    }
}
