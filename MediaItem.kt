package br.com.minhamidia.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media")
data class MediaItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tmdbId: Int? = null,
    val title: String,
    val mediaType: String = "movie", // movie or tv
    val year: String = "",
    val posterPath: String? = null,
    val overview: String = "",
    val status: String = "WATCHLIST", // WATCHLIST, WATCHED, WATCHING
    val rating: Int = 0,
    val watchedAt: Long? = null,
    val runtimeMinutes: Int = 0,
    val genres: String = "",
    val totalSeasons: Int = 0,
    val watchedEpisodes: Int = 0,
    val totalEpisodes: Int = 0,
    val releaseDate: String = ""
)
