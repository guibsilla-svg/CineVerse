package br.com.minhamidia.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {
    @Query("SELECT * FROM games ORDER BY addedAt DESC, name COLLATE NOCASE")
    fun observeAll(): Flow<List<GameItem>>

    @Query("SELECT * FROM games WHERE status = :status ORDER BY addedAt DESC, name COLLATE NOCASE")
    fun observeStatus(status: String): Flow<List<GameItem>>

    @Query("SELECT * FROM games WHERE rawgId = :rawgId LIMIT 1")
    suspend fun findByRawgId(rawgId: Int): GameItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: GameItem)

    @Update
    suspend fun update(item: GameItem)

    @Query("DELETE FROM games WHERE rawgId = :rawgId")
    suspend fun deleteByRawgId(rawgId: Int)
}
