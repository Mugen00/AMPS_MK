package dev.amps.app.music.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.amps.app.music.model.DownloadStatus
import dev.amps.app.music.model.TrackSource

/**
 * Room Database для музичної бібліотеки.
 *
 * Версія 1 — початкова схема.
 * При зміні схеми: збільшити version і додати Migration.
 */
@Database(
    entities = [TrackEntity::class, PlaylistEntity::class, DownloadEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class MusicDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var INSTANCE: MusicDatabase? = null

        fun getInstance(context: Context): MusicDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "music_database"
                )
                    .fallbackToDestructiveMigration() // для dev; у проді — міграції
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

/** Конвертери для enum типів. */
class Converters {
    @androidx.room.TypeConverter
    fun fromTrackSource(value: TrackSource?): String? = value?.name

    @androidx.room.TypeConverter
    fun toTrackSource(value: String?): TrackSource? = value?.let { TrackSource.valueOf(it) }

    @androidx.room.TypeConverter
    fun fromDownloadStatus(value: DownloadStatus?): String? = value?.name

    @androidx.room.TypeConverter
    fun toDownloadStatus(value: String?): DownloadStatus? = value?.let { DownloadStatus.valueOf(it) }
}