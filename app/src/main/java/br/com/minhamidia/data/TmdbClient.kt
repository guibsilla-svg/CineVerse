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
    val releaseDate: String,
    val tmdbVoteAverage: Double = 0.0,
    val imdbRating: Double = 0.0
) {
    fun toMediaItem() = MediaItem(
        tmdbId = id,
        title = title,
        mediaType = type,
        year = year,
        posterPath = posterPath,
        overview = overview,
        releaseDate = releaseDate,
        tmdbVoteAverage = tmdbVoteAverage,
        imdbRating = imdbRating
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



    suspend fun catalog(type: String, category: String, page: Int, genreId: Int?, credential: String): List<TmdbResult> {
        val mediaType = if (type == "tv") "tv" else "movie"
        val endpoint = when (category) {
            "popular" -> "/$mediaType/popular"
            "top_rated" -> "/$mediaType/top_rated"
            "recent" -> if (mediaType == "tv") "/tv/airing_today" else "/movie/now_playing"
            "upcoming" -> if (mediaType == "tv") "/tv/on_the_air" else "/movie/upcoming"
            else -> "/trending/$mediaType/week"
        }
        val params = mutableMapOf("language" to "pt-BR", "page" to page.coerceIn(1, 500).toString())
        if (genreId != null) {
            params["with_genres"] = genreId.toString()
            params["sort_by"] = if (category == "top_rated") "vote_average.desc" else "popularity.desc"
            params["vote_count.gte"] = "20"
            params["include_adult"] = "false"
            return request("/discover/$mediaType", credential, params)
        }
        return request(endpoint, credential, params)
    }

    suspend fun discoverRandom(type: String, genreId: Int?, year: String?, credential: String): List<TmdbResult> {
        val endpoint = if (type == "tv") "/discover/tv" else "/discover/movie"
        val base = mutableMapOf(
            "language" to "pt-BR",
            "include_adult" to "false",
            "sort_by" to "popularity.desc",
            "page" to "1"
        )
        if (genreId != null) base["with_genres"] = genreId.toString()
        if (!year.isNullOrBlank()) base[if (type == "tv") "first_air_date_year" else "primary_release_year"] = year
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

    suspend fun imageBackdrops(id: Int, type: String, credential: String): List<String> {
        val endpoint = if (type == "tv") "tv" else "movie"
        val images = requestObject("/$endpoint/$id/images", credential, mapOf(
            "include_image_language" to "pt,null"
        )).optJSONArray("backdrops") ?: return emptyList()
        return buildList {
            for (i in 0 until images.length()) {
                val path = images.optJSONObject(i)?.optString("file_path").orEmpty()
                if (path.startsWith("/") && !contains(path)) add(path)
                if (size == 3) break
            }
        }
    }


    suspend fun imdbRating(id: Int, title: String, year: String, type: String, tmdbCredential: String, apiKey: String): Double =
        withContext(Dispatchers.IO) {
            require(apiKey.isNotBlank()) { "Configure sua chave OMDb em Configurações > Avaliações IMDb." }
            val mediaType = if (type == "tv") "tv" else "movie"
            val imdbId = runCatching {
                requestObject("/$mediaType/$id/external_ids", tmdbCredential, emptyMap())
                    .optString("imdb_id").takeIf { it.startsWith("tt") }
            }.getOrNull()

            suspend fun omdb(params: List<String>): Double {
                val query = (params + ("apikey=" + URLEncoder.encode(apiKey.trim(), "UTF-8"))).joinToString("&")
                val connection = (URL("https://www.omdbapi.com/?$query").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    val code = connection.responseCode
                    val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) throw IllegalStateException("OMDb respondeu HTTP $code.")
                    val json = JSONObject(body)
                    if (!json.optBoolean("Response", false)) {
                        val error = json.optString("Error", "Título não encontrado no OMDb.")
                        if (error.contains("Invalid API key", true)) {
                            throw IllegalStateException("Chave OMDb inválida. Confira e salve novamente em Configurações.")
                        }
                        throw IllegalStateException(error)
                    }
                    return json.optString("imdbRating").toDoubleOrNull()?.takeIf { it in 0.1..10.0 } ?: 0.0
                } finally {
                    connection.disconnect()
                }
            }

            if (!imdbId.isNullOrBlank()) {
                val byId = try {
                    omdb(listOf("i=" + URLEncoder.encode(imdbId, "UTF-8")))
                } catch (e: Exception) {
                    if (e.message?.contains("Chave OMDb inválida", true) == true) throw e
                    0.0
                }
                if (byId > 0.0) return@withContext byId
            }

            val encodedTitle = URLEncoder.encode(title.trim(), "UTF-8")
            val typeParam = if (type == "tv") "series" else "movie"
            val attempts = buildList {
                if (year.length >= 4) add(listOf("t=$encodedTitle", "y=${URLEncoder.encode(year.take(4), "UTF-8")}", "type=$typeParam"))
                add(listOf("t=$encodedTitle", "type=$typeParam"))
                if (year.length >= 4) add(listOf("t=$encodedTitle", "y=${URLEncoder.encode(year.take(4), "UTF-8")}"))
                add(listOf("t=$encodedTitle"))
            }
            var lastError: Exception? = null
            for (params in attempts) {
                try {
                    val rating = omdb(params)
                    if (rating > 0.0) return@withContext rating
                } catch (e: Exception) {
                    if (e.message?.contains("Chave OMDb inválida", true) == true) throw e
                    lastError = e
                }
            }
            if (lastError != null && attempts.isNotEmpty()) throw lastError
            0.0
        }

    suspend fun nextEpisodeInfo(id: Int, credential: String): JSONObject =
        requestObject("/tv/$id", credential, mapOf("language" to "pt-BR"))

    suspend fun movieReleaseInfo(id: Int, credential: String): JSONObject =
        requestObject("/movie/$id", credential, mapOf("language" to "pt-BR"))

    suspend fun seriesSeasons(id: Int, credential: String): String {
        val seasons = requestObject("/tv/$id", credential, mapOf("language" to "pt-BR"))
            .optJSONArray("seasons") ?: return "[]"
        return org.json.JSONArray().apply {
            for (i in 0 until seasons.length()) {
                val season = seasons.optJSONObject(i) ?: continue
                val number = season.optInt("season_number")
                val episodeResults = runCatching {
                    requestObject("/tv/$id/season/$number", credential, mapOf("language" to "pt-BR"))
                        .optJSONArray("episodes") ?: org.json.JSONArray()
                }.getOrDefault(org.json.JSONArray())
                val episodes = org.json.JSONArray().apply {
                    for (j in 0 until episodeResults.length()) {
                        val episode = episodeResults.optJSONObject(j) ?: continue
                        put(org.json.JSONObject()
                            .put("number", episode.optInt("episode_number"))
                            .put("name", episode.optString("name"))
                            .put("airDate", episode.optString("air_date"))
                            .put("watched", false))
                    }
                }
                put(org.json.JSONObject()
                    .put("number", number)
                    .put("name", season.optString("name"))
                    .put("episodes", season.optInt("episode_count"))
                    .put("airDate", season.optString("air_date"))
                    .put("episodeList", episodes)
                    .put("status", if (number == 0) "SPECIAL" else "WATCHLIST"))
            }
        }.toString()
    }

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
                        releaseDate = date,
                        tmdbVoteAverage = item.optDouble("vote_average", 0.0)
                    ))
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
