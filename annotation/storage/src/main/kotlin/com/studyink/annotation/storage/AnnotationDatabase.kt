package com.studyink.annotation.storage

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AnnotationDocumentEntity::class,
        AnnotationPageEntity::class,
        AnnotationLayerEntity::class,
        StrokeAssetEntity::class,
        LayerStrokeEntity::class,
        AnnotationOperationEntity::class,
        LearnerProfileEntity::class,
        BookRevisionEntity::class,
        LearningActivityEntity::class,
        ActivityPageRefEntity::class,
        AttemptEntity::class,
        AttemptPageEntity::class,
        SubmissionEntity::class,
        SubmissionStrokeRefEntity::class,
        DraftAnswerEntity::class,
        SubmissionAnswerEntity::class,
        TeacherProfileEntity::class,
        TeacherPrepPageEntity::class,
        SubmissionReviewEntity::class,
        ReviewPageEntity::class,
        ReviewStrokeRefEntity::class,
        ReviewAnswerEvaluationEntity::class,
        RemoteOutboxEntity::class,
        RemoteInboxSequenceEntity::class,
        RemoteAppliedOperationEntity::class,
        RemoteReplicaPageEntity::class,
        RemoteReplicaStrokeEntity::class,
        ManagedAssetEntity::class,
        AnswerDocumentEntity::class,
        AnswerPageLinkEntity::class,
        AnswerBookmarkEntity::class,
        TeachingResourceEntity::class,
        TeachingResourceRevisionEntity::class,
        BookPageResourceLinkEntity::class,
        AssistantPromptTemplateEntity::class,
        AssistantJobEntity::class,
    ],
    version = 9,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
    ],
    exportSchema = true,
)
internal abstract class AnnotationDatabase : RoomDatabase() {
    abstract fun annotationDao(): AnnotationDao
    abstract fun learningDao(): LearningDao
    abstract fun teacherDao(): TeacherDao
    abstract fun remoteDao(): RemoteDao
    abstract fun managedAssetDao(): ManagedAssetDao
    abstract fun answerDao(): AnswerDao
    abstract fun teachingResourceDao(): TeachingResourceDao
    abstract fun assistantDao(): AssistantDao

    companion object {
        const val NAME = "master-note-annotations.db"

        fun open(context: Context): AnnotationDatabase =
            Room.databaseBuilder(context.applicationContext, AnnotationDatabase::class.java, NAME)
                .enableMultiInstanceInvalidation()
                .build()
    }
}
