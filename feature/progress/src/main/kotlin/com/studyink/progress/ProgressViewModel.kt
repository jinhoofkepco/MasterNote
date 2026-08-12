package com.studyink.progress

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.studyink.annotation.storage.OpenActivityUseCase
import com.studyink.annotation.storage.RoomLearningRepository
import com.studyink.core.model.LearningActivityId
import com.studyink.core.model.ProfileId
import com.studyink.core.model.ActivityProgressState
import com.studyink.core.model.BookRevisionId
import com.studyink.core.model.LearnerProfile
import com.studyink.reader.ReaderLaunchArgs
import com.studyink.reader.SampleLearningContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ProgressUiState {
    data object Loading : ProgressUiState
    data class Content(
        val bookTitle: String,
        val activities: List<ActivityProgressUi>,
    ) : ProgressUiState
    data class Error(val message: String, val retryAllowed: Boolean) : ProgressUiState
}

data class ActivityProgressUi(
    val activityId: LearningActivityId,
    val title: String,
    val markerCount: Int,
    val hasDraftMarker: Boolean,
    val state: ActivityProgressState,
    val enabled: Boolean = true,
)

class ProgressViewModel(application: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow<ProgressUiState>(ProgressUiState.Loading)
    val uiState: StateFlow<ProgressUiState> = _uiState.asStateFlow()
    private val _readerLaunches = MutableSharedFlow<ReaderLaunchArgs>(extraBufferCapacity = 1)
    val readerLaunches: SharedFlow<ReaderLaunchArgs> = _readerLaunches.asSharedFlow()
    private var repository: RoomLearningRepository? = null
    private val requestedRevisionId: String? = savedStateHandle[ProgressActivity.EXTRA_REVISION_ID]
    private val requestedBookTitle: String? = savedStateHandle[ProgressActivity.EXTRA_BOOK_TITLE]
    private val documentUri: String? = savedStateHandle[ProgressActivity.EXTRA_DOCUMENT_URI]

    init {
        load()
    }

    fun retry() {
        if (_uiState.value is ProgressUiState.Loading) return
        load()
    }

    fun openActivity(activityId: LearningActivityId) {
        viewModelScope.launch {
            runCatching {
                val session = OpenActivityUseCase(repository())(
                    ProfileId(SampleLearningContent.PROFILE_ID),
                    activityId,
                )
                ReaderLaunchArgs(
                    profileId = session.attempt.profileId,
                    activityId = session.attempt.activityId,
                    attemptId = session.attempt.attemptId,
                    initialPageId = session.initialPageId,
                    documentUri = documentUri,
                )
            }.onSuccess { _readerLaunches.emit(it) }
                .onFailure { _uiState.value = ProgressUiState.Error(it.message ?: "학습 항목을 열 수 없습니다", true) }
        }
    }

    private fun load() {
        _uiState.value = ProgressUiState.Loading
        viewModelScope.launch {
            try {
                val seed = if(requestedRevisionId==null) withContext(Dispatchers.IO){SampleLearningContent.createSeed(getApplication())} else null
                val profile=seed?.profile?:LearnerProfile(ProfileId(SampleLearningContent.PROFILE_ID),"학생",System.currentTimeMillis())
                if(seed!=null) repository().ensureContent(seed) else repository().ensureProfile(profile)
                val revision=seed?.bookRevision?.revisionId?:BookRevisionId(requireNotNull(requestedRevisionId))
                repository().observeActivitiesWithProgress(profile.profileId, revision)
                    .collect { activities ->
                        _uiState.value = ProgressUiState.Content(
                            bookTitle = requestedBookTitle ?: seed?.bookRevision?.title ?: "학습 진도",
                            activities = activities.map { progress ->
                                ActivityProgressUi(
                                    activityId = progress.activityId,
                                    title = progress.title,
                                    markerCount = progress.submissionCount,
                                    hasDraftMarker = progress.hasDraft,
                                    state = progress.state,
                                )
                            },
                        )
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _uiState.value = ProgressUiState.Error(error.message ?: "진도를 불러올 수 없습니다", true)
            }
        }
    }

    private suspend fun repository(): RoomLearningRepository {
        repository?.let { return it }
        return RoomLearningRepository.open(getApplication()).also { repository = it }
    }

    override fun onCleared() {
        repository?.close()
        super.onCleared()
    }
}
