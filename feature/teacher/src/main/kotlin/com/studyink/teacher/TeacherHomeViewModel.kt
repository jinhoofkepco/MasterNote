package com.studyink.teacher

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studyink.annotation.storage.RoomLearningRepository
import com.studyink.annotation.storage.RoomTeacherRepository
import com.studyink.core.model.BookRevisionId
import com.studyink.core.model.ReviewQueueItem
import com.studyink.core.model.ReviewStatus
import com.studyink.core.model.TeacherId
import com.studyink.reader.OpenSubmissionReviewUseCase
import com.studyink.reader.OpenTeacherPreparationUseCase
import com.studyink.reader.ReaderScene
import com.studyink.reader.SampleLearningContent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface TeacherHomeUiState {
    data object Loading : TeacherHomeUiState
    data class Content(val queue: List<ReviewQueueItem>) : TeacherHomeUiState
    data class Error(val message: String) : TeacherHomeUiState
}

class TeacherHomeViewModel(application: Application) : AndroidViewModel(application) {
    private val teacherId = TeacherId(RoomTeacherRepository.DEFAULT_TEACHER_ID)
    private val _uiState = MutableStateFlow<TeacherHomeUiState>(TeacherHomeUiState.Loading)
    val uiState: StateFlow<TeacherHomeUiState> = _uiState.asStateFlow()
    private val _readerScenes = MutableSharedFlow<ReaderScene>(extraBufferCapacity = 1)
    val readerScenes: SharedFlow<ReaderScene> = _readerScenes.asSharedFlow()
    private var teacherRepository: RoomTeacherRepository? = null
    private var learningRepository: RoomLearningRepository? = null

    init { load() }

    fun openPreparation() = viewModelScope.launch {
        runCatching {
            OpenTeacherPreparationUseCase(teacher())(
                teacherId, BookRevisionId(SampleLearningContent.REVISION_ID),
            )
        }.onSuccess { _readerScenes.emit(it) }
            .onFailure { _uiState.value = TeacherHomeUiState.Error(it.message ?: "교재 준비를 열 수 없습니다") }
    }

    fun openQueueItem(item: ReviewQueueItem) = viewModelScope.launch {
        runCatching {
            val repository = teacher()
            val existing = item.latestReviewId?.let { repository.getReview(it) }
            if (existing?.review?.status == ReviewStatus.PUBLISHED) {
                ReaderScene.publishedReview(
                    existing.attempt.revisionId,
                    item.submissionId,
                    existing.review.reviewId,
                    existing.review.lastVisitedPageId ?: existing.pages.first().pageId,
                )
            } else {
                OpenSubmissionReviewUseCase(repository)(item.submissionId, teacherId).first
            }
        }.onSuccess { _readerScenes.emit(it) }
            .onFailure { _uiState.value = TeacherHomeUiState.Error(it.message ?: "검토를 열 수 없습니다") }
    }

    private fun load() = viewModelScope.launch {
        runCatching {
            val seed = SampleLearningContent.createSeed(getApplication())
            learning().ensureContent(seed)
            teacher().ensureDefaultTeacher()
            teacher().observeReviewQueue(teacherId).collect { _uiState.value = TeacherHomeUiState.Content(it) }
        }.onFailure { _uiState.value = TeacherHomeUiState.Error(it.message ?: "검토 목록을 불러올 수 없습니다") }
    }

    private suspend fun teacher(): RoomTeacherRepository = teacherRepository
        ?: RoomTeacherRepository.open(getApplication()).also { teacherRepository = it }

    private suspend fun learning(): RoomLearningRepository = learningRepository
        ?: RoomLearningRepository.open(getApplication()).also { learningRepository = it }

    override fun onCleared() {
        teacherRepository?.close()
        learningRepository?.close()
        super.onCleared()
    }
}
