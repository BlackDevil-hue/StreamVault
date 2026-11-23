package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.AudioLyricsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioLyricsDao {
    @Query("SELECT * FROM audio_lyrics WHERE downloadId = :downloadId LIMIT 1")
    fun observeByDownloadId(downloadId: String): Flow<AudioLyricsEntity?>

    @Query("SELECT * FROM audio_lyrics WHERE downloadId = :downloadId LIMIT 1")
    suspend fun getByDownloadId(downloadId: String): AudioLyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AudioLyricsEntity)

    @Query("UPDATE audio_lyrics SET offsetMs = :offsetMs WHERE downloadId = :downloadId")
    suspend fun updateOffset(downloadId: String, offsetMs: Long)

    @Query("DELETE FROM audio_lyrics WHERE downloadId = :downloadId")
    suspend fun deleteByDownloadId(downloadId: String)
}
