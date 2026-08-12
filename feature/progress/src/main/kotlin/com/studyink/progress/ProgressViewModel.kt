package com.studyink.progress

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studyink.annotation.storage.OpenActivityUseCase
import com.studyink.annotation.storage.RoomLearningRepository
import com.studyink.core.model.LearningActivityId
import com.studyink.core.model.ProfileId
import com.studyink.core.model.ActivityProgressState
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

class ProgressViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow<ProgressUiState>(ProgressUiState.Loading)
    val uiState: StateFlow<ProgressUiState> = _uiState.asStateFlow()
    private val _readerLaunches = MutableSharedFlow<ReaderLaunchArgs>(extraBufferCapacity = 1)
    val readerLaunches: SharedFlow<ReaderLaunchArgs> = _readerLaunches.asSharedFlow()
    private var repository: RoomLearningRepository? = null

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
                )
            }.onSuccess { _readerLaunches.emit(it) }
                .onFailure { _uiState.value = ProgressUiState.Error(it.message ?: "학습 항목을 열 수 없습니다", true) }
        }
    }

    private fun load() {
        _uiState.value = ProgressUiState.Loading
        viewModelScope.launch {
            try {
                val seed = withContext(Dispatchers.IO) {
                    SampleLearningContent.createSeed(getApplication())
                }
                repository().ensureContent(seed)
                repository().observeActivitiesWithProgress(seed.profile.profileId, seed.bookRevision.revisionId)
                    .collect { activities ->
                        _uiState.value = ProgressUiState.Content(
                            bookTitle = seed.bookRevision.title,
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
