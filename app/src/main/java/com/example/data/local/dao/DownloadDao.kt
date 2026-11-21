package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.DownloadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads WHERE status != 'COMPLETED' AND status != 'CANCELLED' ORDER BY createdAt DESC")
    fun observeActiveAndQueued(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status = 'COMPLETED' ORDER BY completedAt DESC")
    fun observeCompleted(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status = 'COMPLETED' ORDER BY completedAt DESC LIMIT :limit")
    fun observeRecentCompleted(limit: Int = 5): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status = 'DOWNLOADING'")
    suspend fun getDownloading(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status = 'QUEUED' ORDER BY createdAt ASC")
    suspend fun getQueued(): List<DownloadEntity>

    @Query("SELECT COUNT(*) FROM downloads WHERE status = 'DOWNLOADING'")
    suspend fun countDownloading(): Int

    @Query(
        "SELECT * FROM downloads WHERE status = 'COMPLETED' AND (normalizedUrl = :normalizedUrl OR streamUrl = :streamUrl) ORDER BY completedAt DESC LIMIT 1"
    )
    suspend fun findDuplicateCompleted(normalizedUrl: String, streamUrl: String): DownloadEntity?

    @Query("SELECT COALESCE(SUM(totalBytes), 0) FROM downloads WHERE status = 'COMPLETED'")
    suspend fun getTotalCompletedBytes(): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DownloadEntity)

    @Update
    suspend fun update(entity: DownloadEntity)

    @Query(
        "UPDATE downloads SET bytesDownloaded = :bytesDownloaded, totalBytes = :totalBytes, reportedProgressPercent = NULL, speedBytesPerSec = :speed, etaSeconds = :eta, status = :status WHERE id = :id"
    )
    suspend fun updateProgress(
        id: String,
        bytesDownloaded: Long,
        totalBytes: Long,
        speed: Long,
        eta: Long,
        status: String
    )

    @Query("UPDATE downloads SET bytesDownloaded = :bytesDownloaded, totalBytes = :totalBytes, speedBytesPerSec = :speed, reportedProgressPercent = :percent, etaSeconds = :eta, status = :status WHERE id = :id")
    suspend fun updateYtDlpProgress(
        id: String,
        bytesDownloaded: Long,
        totalBytes: Long,
        speed: Long,
        percent: Int,
        eta: Long,
        status: String
    )

    @Query("UPDATE downloads SET bytesDownloaded = :bytesDownloaded, totalBytes = CASE WHEN totalBytes > 0 THEN MAX(totalBytes, :bytesDownloaded) ELSE 0 END, speedBytesPerSec = 0, etaSeconds = 0, reportedProgressPercent = CASE WHEN totalBytes > 0 THEN MIN(99, CAST(:bytesDownloaded * 100.0 / MAX(totalBytes, :bytesDownloaded) AS INTEGER)) ELSE reportedProgressPercent END, status = :status WHERE id = :id")
    suspend fun updateYtDlpPausedProgress(id: String, bytesDownloaded: Long, status: String)

    @Query("UPDATE downloads SET bytesDownloaded = 0, totalBytes = 0, reportedProgressPercent = NULL, speedBytesPerSec = 0, etaSeconds = 0 WHERE id = :id")
    suspend fun resetYtDlpProgress(id: String)

    @Query("UPDATE downloads SET status = :status, speedBytesPerSec = 0, etaSeconds = 0, errorMessage = :errorMessage WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, errorMessage: String? = null)

    @Query("UPDATE downloads SET workRequestId = :workId WHERE id = :id")
    suspend fun updateWorkRequestId(id: String, workId: String?)

    @Query(
        "UPDATE downloads SET status = 'COMPLETED', bytesDownloaded = :finalBytes, totalBytes = :finalBytes, reportedProgressPercent = 100, speedBytesPerSec = 0, etaSeconds = 0, localUri = :localUri, localPath = :localPath, fileName = :fileName, sha256 = :sha256, resolutionLabel = :resolutionLabel, resolutionHeight = :resolutionHeight, format = :format, mimeType = :mimeType, durationMs = COALESCE(:durationMs, durationMs), completedAt = :completedAt, errorMessage = NULL WHERE id = :id"
    )
    suspend fun markCompleted(
        id: String,
        finalBytes: Long,
        localUri: String?,
        localPath: String?,
        fileName: String,
        sha256: String?,
        resolutionLabel: String,
        resolutionHeight: Int?,
        format: String,
        mimeType: String,
        durationMs: Long?,
        completedAt: Long
    )

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM downloads WHERE status = 'COMPLETED'")
    suspend fun deleteAllCompleted()

    @Query("SELECT * FROM downloads WHERE status = 'COMPLETED'")
    suspend fun getAllCompleted(): List<DownloadEntity>
}
