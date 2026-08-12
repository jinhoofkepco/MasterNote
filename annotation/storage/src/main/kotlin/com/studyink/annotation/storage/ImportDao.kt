package com.studyink.annotation.storage

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao internal interface ImportDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(value: ImportSessionEntity)
    @Update suspend fun update(value: ImportSessionEntity)
    @Query("SELECT * FROM import_sessions WHERE importSessionId=:id") suspend fun session(id: String): ImportSessionEntity?
    @Query("SELECT * FROM import_sessions WHERE importSessionId=:id") fun observe(id: String): Flow<ImportSessionEntity?>
    @Query("SELECT * FROM import_sessions WHERE state IN ('COPYING','INVENTORY','DECODING','VALIDATING','VERIFYING_ASSETS','PROBING_DOCUMENT','MATERIALIZING','COMMITTING')") suspend fun interrupted(): List<ImportSessionEntity>
}
