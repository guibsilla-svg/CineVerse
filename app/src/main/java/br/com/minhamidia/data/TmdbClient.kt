package br.com.minhamidia.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class TmdbResult(
    val id: Int,
    val title: String,
    val type: String,
    val year: String,
    val posterPath: String?,
    val overview: String,
    val releaseDate: String
) {
    fun toMediaItem() = MediaItem(
        tmdbId = id,
        title = title,
        mediaType = type,
        year = year,
        posterPath = posterPath,
        overview = overview,
        releaseDate = releaseDate
    )
}

data class TmdbTrailer(val key: String, val name: String, val language: String)

object TmdbClient {
    suspend fun search(query: String, credential: String): List<TmdbResult> =
        request("/search/multi", credential, mapOf(
            "query" to query,
            "language" to "pt-BR",
            "include_adult" to "false"
        ))

    suspend fun trendingMovies(credential: String): List<TmdbResult> =
        request("/trending/movie/week", credential, mapOf("language" to "pt-BR"))

    suspend fun trendingShows(credential: String): List<TmdbResult> =
        request("/trending/tv/week", credential, mapOf("language" to "pt-BR"))

    suspend fun popularMovies(credential: String): List<TmdbResult> =
        request("/movie/popular", credential, mapOf("language" to "pt-BR"))

    suspend fun popularShows(credential: String): List<TmdbResult> =
        request("/tv/popular", credential, mapOf("language" to "pt-BR"))



    suspend fun discoverRandom(type: String, genreId: Int?, credential: String): List<TmdbResult> {
        val endpoint = if (type == "tv") "/discover/tv" else "/discover/movie"
        val base = mutableMapOf(
            "language" to "pt-BR",
            "include_adult" to "false",
            "sort_by" to "popularity.desc",
            "page" to "1"
        )
        if (genreId != null) base["with_genres"] = genreId.toString()
        val firstPage = requestObject(endpoint, credential, base)
        val totalPages = firstPage.optInt("total_pages", 1).coerceIn(1, 500)
        val randomPage = (1..totalPages).random()
        base["page"] = randomPage.toString()
        return request(endpoint, credential, base).filter { it.posterPath != null }
    }

    suspend fun trailer(id: Int, type: String, credential: String): TmdbTrailer? {
        val endpoint = if (type == "tv") "tv" else "movie"
        val videos = requestObject("/$endpoint/$id/videos", credential, mapOf(
            "language" to "pt-BR",
            "include_video_language" to "pt-BR,en,null"
        )).optJSONArray("results") ?: return null
        val candidates = (0 until videos.length()).mapNotNull { videos.optJSONObject(it) }
            .filter { it.optString("site") == "YouTube" && it.optString("key").isNotBlank() && it.optString("type") == "Trailer" }
        val selected = candidates.sortedWith(
            compareByDescending<JSONObject> { it.optBoolean("official") }
                .thenBy { when (it.optString("iso_639_1")) { "pt" -> 0; "en" -> 1; else -> 2 } }
                .thenByDescending { it.optString("published_at") }
        ).firstOrNull() ?: return null
        return TmdbTrailer(selected.optString("key"), selected.optString("name"), selected.optString("iso_639_1"))
    }

    suspend fun seriesSeasons(id: Int, credential: String): String =
        requestObject("/tv/$id", credential, mapOf("language" to "pt-BR"))
            .optJSONArray("seasons")?.let { seasons ->
                org.json.JSONArray().apply {
                    for (i in 0 until seasons.length()) {
                        val season = seasons.optJSONObject(i) ?: continue
                        put(org.json.JSONObject()
                            .put("number", season.optInt("season_number"))
                            .put("name", season.optString("name"))
                            .put("episodes", season.optInt("episode_count"))
                            .put("airDate", season.optString("air_date"))
                            .put("status", if (season.optInt("season_number") == 0) "SPECIAL" else "WATCHLIST"))
                    }
                }.toString()
            } ?: "[]"

    private suspend fun requestObject(path: String, credential: String, parameters: Map<String, String>): JSONObject =
        withContext(Dispatchers.IO) {
            require(credential.isNotBlank()) { "Configure seu token de acesso do TMDB nas configurações." }
            val bearer = credential.trim().count { it == '.' } == 2
            val query = parameters.entries.joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" }
            val auth = if (bearer) "" else "&api_key=" + URLEncoder.encode(credential.trim(), "UTF-8")
            val connection = (URL("https://api.themoviedb.org/3$path?$query$auth").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 15000; readTimeout = 15000
                setRequestProperty("Accept", "application/json")
                if (bearer) setRequestProperty("Authorization", "Bearer ${credential.trim()}")
            }
            try {
                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw IllegalStateException("Não foi possível importar as temporadas do TMDB ($code).")
                JSONObject(body)
            } finally { connection.disconnect() }
        }

    private suspend fun request(
        path: String,
        credential: String,
        parameters: Map<String, String>
    ): List<TmdbResult> = withContext(Dispatchers.IO) {
        require(credential.isNotBlank()) { "Configure seu token de acesso do TMDB nas configurações." }
        val isBearerToken = credential.trim().count { it == '.' } == 2
        val query = parameters.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val authQuery = if (isBearerToken) "" else {
            val separator = if (query.isBlank()) "" else "&"
            separator + "api_key=" + URLEncoder.encode(credential.trim(), "UTF-8")
        }
        val endpoint = "https://api.themoviedb.org/3$path" +
            (if (query.isNotBlank() || authQuery.isNotBlank()) "?" else "") + query + authQuery
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
            if (isBearerToken) setRequestProperty("Authorization", "Bearer ${credential.trim()}")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(body).optString("status_message") }.getOrNull().orEmpty()
                val explanation = when (code) {
                    401 -> "Credencial TMDB inválida. Use a API Read Access Token (Bearer) ou a API Key v3."
                    403 -> "O TMDB recusou o acesso. Confira as permissões da credencial."
                    else -> "Erro TMDB ($code): ${detail.ifBlank { "não foi possível consultar o catálogo" }}"
                }
                throw IllegalStateException(explanation)
            }
            val results = JSONObject(body).optJSONArray("results") ?: return@withContext emptyList()
            buildList {
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val type = item.optString("media_type").let {
                        if (it == "movie" || it == "tv") it
                        else if (path.contains("/movie/") || path.endsWith("/movie")) "movie"
                        else if (path.contains("/tv/") || path.endsWith("/tv")) "tv"
                        else ""
                    }
                    if (type != "movie" && type != "tv") continue
                    val title = item.optString(if (type == "movie") "title" else "name").trim()
                    if (title.isBlank()) continue
                    val date = item.optString(if (type == "movie") "release_date" else "first_air_date")
                    add(TmdbResult(
                        id = item.optInt("id"),
                        title = title,
                        type = type,
                        year = date.take(4),
                        posterPath = item.optString("poster_path").takeIf { it.isNotBlank() && it != "null" },
                        overview = item.optString("overview"),
                        releaseDate = date
                    ))
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
