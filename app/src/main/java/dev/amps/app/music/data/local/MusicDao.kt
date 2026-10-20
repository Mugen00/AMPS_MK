package dev.amps.app.music.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Delete
import kotlinx.coroutines.flow.Flow

/** DAO для треків. */
@Dao
interface TrackDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<TrackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: TrackEntity)

    @Update
    suspend fun update(track: TrackEntity)

    @Delete
    suspend fun delete(track: TrackEntity)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getById(id: String): TrackEntity?

    @Query("SELECT * FROM tracks ORDER BY lastPlayedAt DESC")
    fun getAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE source = :source ORDER BY title ASC")
    fun getBySource(source: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE isFavorite = 1 ORDER BY lastPlayedAt DESC")
    fun getFavorites(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE filePath IS NOT NULL AND filePath != '' ORDER BY title ASC")
    fun getLocalTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE downloadedAt > 0 ORDER BY downloadedAt DESC")
    fun getDownloadedTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE title LIKE :query OR artist LIKE :query ORDER BY playCount DESC")
    fun search(query: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<TrackEntity>

    @Query("UPDATE tracks SET playCount = playCount + 1, lastPlayedAt = :now WHERE id = :id")
    suspend fun incrementPlayCount(id: String, now: Long)

    @Query("UPDATE tracks SET isFavorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: String, fav: Boolean)

    @Query("DELETE FROM tracks WHERE source = :source")
    suspend fun deleteBySource(source: String)

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int
}

/** DAO для плейлистів. */
@Dao
interface PlaylistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(playlist: PlaylistEntity)

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Delete
    suspend fun delete(playlist: PlaylistEntity)

    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getById(id: String): PlaylistEntity?

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deleteById(id: String)
}

/** DAO для завантажень. */
@Dao
interface DownloadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: DownloadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tasks: List<DownloadEntity>)

    @Update
    suspend fun update(task: DownloadEntity)

    @Delete
    suspend fun delete(task: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: String): DownloadEntity?

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun getAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status IN (:statuses) ORDER BY createdAt DESC")
    fun getByStatuses(statuses: List<String>): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE trackId = :trackId")
    suspend fun getByTrackId(trackId: String): DownloadEntity?

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM downloads WHERE status = :status")
    suspend fun deleteByStatus(status: String)
}