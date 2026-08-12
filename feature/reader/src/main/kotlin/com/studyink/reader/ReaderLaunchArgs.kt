package com.studyink.reader

import android.content.Intent
import com.studyink.core.model.AttemptId
import com.studyink.core.model.LearningActivityId
import com.studyink.core.model.PageId
import com.studyink.core.model.ProfileId
import com.studyink.core.model.SubmissionId

data class ReaderLaunchArgs(
    val profileId: ProfileId,
    val activityId: LearningActivityId,
    val attemptId: AttemptId,
    val initialPageId: PageId,
    val submissionId: SubmissionId? = null,
    val documentUri: String? = null,
) {
    fun putInto(intent: Intent): Intent = intent
        .putExtra(EXTRA_PROFILE_ID, profileId.value)
        .putExtra(EXTRA_ACTIVITY_ID, activityId.value)
        .putExtra(EXTRA_ATTEMPT_ID, attemptId.value)
        .putExtra(EXTRA_INITIAL_PAGE_ID, initialPageId.value)
        .also { target -> submissionId?.let { target.putExtra(EXTRA_SUBMISSION_ID, it.value) } }
        .also { target -> documentUri?.let { target.putExtra(EXTRA_DOCUMENT_URI, it) } }

    companion object {
        private const val EXTRA_PROFILE_ID = "com.studyink.reader.PROFILE_ID"
        private const val EXTRA_ACTIVITY_ID = "com.studyink.reader.ACTIVITY_ID"
        private const val EXTRA_ATTEMPT_ID = "com.studyink.reader.ATTEMPT_ID"
        private const val EXTRA_INITIAL_PAGE_ID = "com.studyink.reader.INITIAL_PAGE_ID"
        private const val EXTRA_SUBMISSION_ID = "com.studyink.reader.SUBMISSION_ID"
        private const val EXTRA_DOCUMENT_URI = "com.studyink.reader.DOCUMENT_URI"

        fun from(intent: Intent): ReaderLaunchArgs? {
            val profileId = intent.getStringExtra(EXTRA_PROFILE_ID) ?: return null
            val activityId = intent.getStringExtra(EXTRA_ACTIVITY_ID) ?: return null
            val attemptId = intent.getStringExtra(EXTRA_ATTEMPT_ID) ?: return null
            val initialPageId = intent.getStringExtra(EXTRA_INITIAL_PAGE_ID) ?: return null
            return ReaderLaunchArgs(
                ProfileId(profileId),
                LearningActivityId(activityId),
                AttemptId(attemptId),
                PageId(initialPageId),
                intent.getStringExtra(EXTRA_SUBMISSION_ID)?.let(::SubmissionId),
                intent.getStringExtra(EXTRA_DOCUMENT_URI),
            )
        }
    }
}
