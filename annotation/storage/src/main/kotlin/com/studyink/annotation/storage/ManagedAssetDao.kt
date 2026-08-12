package com.studyink.annotation.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface ManagedAssetDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(asset: ManagedAssetEntity)

    @Query("SELECT * FROM managed_assets WHERE assetId = :assetId")
    suspend fun asset(assetId: String): ManagedAssetEntity?

    @Query("SELECT * FROM managed_assets WHERE sha256 = :sha256")
    suspend fun assetByHash(sha256: String): ManagedAssetEntity?

    @Query("SELECT * FROM managed_assets")
    suspend fun all(): List<ManagedAssetEntity>

    @Query("UPDATE managed_assets SET lastVerifiedAtEpochMillis = :verifiedAt WHERE assetId = :assetId")
    suspend fun markVerified(assetId: String, verifiedAt: Long): Int

    @Query("DELETE FROM managed_assets WHERE assetId = :assetId")
    suspend fun delete(assetId: String): Int
}
