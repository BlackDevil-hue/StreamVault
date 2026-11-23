package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "audio_lyrics",
    foreignKeys = [
        ForeignKey(
            entity = DownloadEntity::class,
            parentColumns = ["id"],
            childColumns = ["downloadId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class AudioLyricsEntity(
    @PrimaryKey val downloadId: String,
    val queryTitle: String,
    val queryArtist: String?,
    val syncedLrc: String?,
    val plainLyrics: String?,
    val isInstrumental: Boolean = false,
    val sourceLabel: String? = null,
    val offsetMs: Long = 0L,
    val fetchedAt: Long
)
