package com.example.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.DownloadDao
import com.example.data.local.dao.AudioLibraryDao
import com.example.data.local.entity.AudioPlaylistEntity
import com.example.data.local.entity.AudioPlaylistTrackEntity
import com.example.data.local.entity.DownloadEntity

@Database(
    entities = [DownloadEntity::class, AudioPlaylistEntity::class, AudioPlaylistTrackEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun audioLibraryDao(): AudioLibraryDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN reportedProgressPercent INTEGER")
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS audio_playlists (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_audio_playlists_name ON audio_playlists(name)")
                db.execSQL("CREATE TABLE IF NOT EXISTS audio_playlist_tracks (playlistId TEXT NOT NULL, downloadId TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(playlistId, downloadId), FOREIGN KEY(playlistId) REFERENCES audio_playlists(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(downloadId) REFERENCES downloads(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audio_playlist_tracks_playlistId ON audio_playlist_tracks(playlistId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audio_playlist_tracks_downloadId ON audio_playlist_tracks(downloadId)")
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN artist TEXT")
            }
        }
    }
}
