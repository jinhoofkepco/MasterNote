package com.studyink.annotation.storage

import android.content.Context
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
    ],
    version = 1,
    exportSchema = true,
)
internal abstract class AnnotationDatabase : RoomDatabase() {
    abstract fun annotationDao(): AnnotationDao

    companion object {
        const val NAME = "master-note-annotations.db"

        fun open(context: Context): AnnotationDatabase =
            Room.databaseBuilder(context.applicationContext, AnnotationDatabase::class.java, NAME)
                .build()
    }
}
