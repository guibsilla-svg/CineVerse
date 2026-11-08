package br.com.minhamidia.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "games")
data class GameItem(
    @PrimaryKey val rawgId: Int,
    val name: String,
    val released: String = "",
    val year: String = "",
    val backgroundImage: String = "",
    val overview: String = "",
    val rating: Double = 0.0,
    val metacritic: Int = 0,
    val genres: String = "",
    val platforms: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val status: String = "WANT_TO_PLAY"
)
