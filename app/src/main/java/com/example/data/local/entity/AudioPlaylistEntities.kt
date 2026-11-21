package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "audio_playlists", indices = [Index(value = ["name"], unique = true)])
data class AudioPlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long
)

@Entity(
    tableName = "audio_playlist_tracks",
    primaryKeys = ["playlistId", "downloadId"],
    indices = [Index(value = ["playlistId"]), Index(value = ["downloadId"])],
    foreignKeys = [
        ForeignKey(
            entity = AudioPlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = DownloadEntity::class,
            parentColumns = ["id"],
            childColumns = ["downloadId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class AudioPlaylistTrackEntity(
    val playlistId: String,
    val downloadId: String,
    val position: Int
)
