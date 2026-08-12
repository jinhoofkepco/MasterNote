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
    ],
    version = 4,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
    ],
    exportSchema = true,
)
internal abstract class AnnotationDatabase : RoomDatabase() {
    abstract fun annotationDao(): AnnotationDao
    abstract fun learningDao(): LearningDao
    abstract fun teacherDao(): TeacherDao
    abstract fun remoteDao(): RemoteDao

    companion object {
        const val NAME = "master-note-annotations.db"

        fun open(context: Context): AnnotationDatabase =
            Room.databaseBuilder(context.applicationContext, AnnotationDatabase::class.java, NAME)
                .enableMultiInstanceInvalidation()
                .build()
    }
}
