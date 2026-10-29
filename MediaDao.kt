package br.com.minhamidia.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Query("SELECT * FROM media ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<MediaItem>>

    @Query("SELECT * FROM media WHERE status = :status ORDER BY title COLLATE NOCASE")
    fun observeStatus(status: String): Flow<List<MediaItem>>

    @Query("SELECT * FROM media WHERE mediaType = :type ORDER BY title COLLATE NOCASE")
    fun observeType(type: String): Flow<List<MediaItem>>

    @Query("SELECT COUNT(*) FROM media")
    suspend fun count(): Int

    @Query("SELECT * FROM media WHERE tmdbId = :tmdbId AND mediaType = :type LIMIT 1")
    suspend fun findByTmdbId(tmdbId: Int, type: String): MediaItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: MediaItem): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MediaItem>)

    @Update
    suspend fun update(item: MediaItem)

    @Delete
    suspend fun delete(item: MediaItem)
}
