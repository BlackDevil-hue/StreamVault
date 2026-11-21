package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.AudioPlaylistEntity
import com.example.data.local.entity.AudioPlaylistTrackEntity
import com.example.data.local.entity.DownloadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioLibraryDao {
    @Query("SELECT * FROM audio_playlists ORDER BY name COLLATE NOCASE")
    fun observePlaylists(): Flow<List<AudioPlaylistEntity>>

    @Query("SELECT * FROM audio_playlists ORDER BY name COLLATE NOCASE")
    suspend fun getPlaylists(): List<AudioPlaylistEntity>

    @Query("SELECT * FROM downloads WHERE status = 'COMPLETED' AND isAudioOnly = 1 ORDER BY completedAt DESC")
    fun observeAudioDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT downloads.* FROM downloads INNER JOIN audio_playlist_tracks ON downloads.id = audio_playlist_tracks.downloadId WHERE audio_playlist_tracks.playlistId = :playlistId AND downloads.status = 'COMPLETED' AND downloads.isAudioOnly = 1 ORDER BY audio_playlist_tracks.position")
    fun observePlaylistTracks(playlistId: String): Flow<List<DownloadEntity>>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM audio_playlist_tracks WHERE playlistId = :playlistId")
    suspend fun nextPosition(playlistId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun createPlaylist(playlist: AudioPlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTrack(track: AudioPlaylistTrackEntity)

    @Query("DELETE FROM audio_playlist_tracks WHERE playlistId = :playlistId AND downloadId = :downloadId")
    suspend fun removeTrack(playlistId: String, downloadId: String)

    @Query("DELETE FROM audio_playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)
}
