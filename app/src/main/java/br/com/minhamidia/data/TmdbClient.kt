package br.com.minhamidia.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    val voteCount: Int = 0,
    val popularity: Double = 0.0
) {
    fun toMediaItem() = MediaItem(
        tmdbId = id,
        title = title,
        mediaType = type,
        year = year,
        posterPath = posterPath,
        overview = overview,
        releaseDate = releaseDate,
        tmdbVoteAverage = tmdbVoteAverage
    )
}

data class TmdbTrailer(val key: String, val name: String, val language: String)
data class TmdbCastMember(val id: Int, val name: String, val character: String, val profilePath: String?)

data class TmdbShareCardDetails(
    val leadActor: String,
    val genres: List<String>,
    val localizedTitle: String = "",
    val backdropPath: String?,
    val stillPaths: List<String>,
    val fanartBackgroundUrl: String? = null,
    val fanartLogoUrl: String? = null,
    val fanartPosterUrl: String? = null,
    val fanartBannerUrl: String? = null,
    val fanartStillUrls: List<String> = emptyList()
)

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



    suspend fun catalog(type: String, category: String, page: Int, genreId: Int?, credential: String, yearPeriod: String = "Todos"): List<TmdbResult> {
        val mediaType = if (type == "tv") "tv" else "movie"
        val endpoint = when (category) {
            "popular" -> "/$mediaType/popular"
            "top_rated" -> "/$mediaType/top_rated"
            "recent" -> if (mediaType == "tv") "/tv/airing_today" else "/movie/now_playing"
            "upcoming" -> if (mediaType == "tv") "/tv/on_the_air" else "/movie/upcoming"
            else -> "/trending/$mediaType/week"
        }
        val params = mutableMapOf("language" to "pt-BR", "page" to page.coerceIn(1, 500).toString())
        val bounds = yearPeriodBounds(yearPeriod)
        if (bounds != null) {
            if (mediaType == "tv") { params["first_air_date.gte"] = bounds.first; params["first_air_date.lte"] = bounds.second }
            else { params["primary_release_date.gte"] = bounds.first; params["primary_release_date.lte"] = bounds.second }
        }
        if (genreId != null || bounds != null) {
            if (genreId != null) params["with_genres"] = genreId.toString()
            params["sort_by"] = if (category == "top_rated") "vote_average.desc" else "popularity.desc"
            params["vote_count.gte"] = "20"
            params["include_adult"] = "false"
            return request("/discover/$mediaType", credential, params)
        }
        return request(endpoint, credential, params)
    }

    suspend fun discoverRandom(type: String, genreId: Int?, year: String?, credential: String, originalLanguages: List<String> = emptyList()): List<TmdbResult> {
        val endpoint = if (type == "tv") "/discover/tv" else "/discover/movie"
        val base = mutableMapOf(
            "language" to "pt-BR",
            "include_adult" to "false",
            "sort_by" to "popularity.desc",
            "page" to "1"
        )
        if (genreId != null) base["with_genres"] = genreId.toString()
        if (!year.isNullOrBlank()) {
            val bounds = yearPeriodBounds(year)
            if (bounds != null) {
                if (type == "tv") { base["first_air_date.gte"] = bounds.first; base["first_air_date.lte"] = bounds.second }
                else { base["primary_release_date.gte"] = bounds.first; base["primary_release_date.lte"] = bounds.second }
            } else base[if (type == "tv") "first_air_date_year" else "primary_release_year"] = year
        }
        if (originalLanguages.isNotEmpty()) base["with_original_language"] = originalLanguages.joinToString("|")
        val firstPage = requestObject(endpoint, credential, base)
        val totalPages = firstPage.optInt("total_pages", 1).coerceIn(1, 500)
        // Amostra páginas espalhadas pelo catálogo: mantém o universo amplo sem depender só da primeira página.
        val sampledPages = (1..totalPages).shuffled().take(minOf(4, totalPages))
        return sampledPages.flatMap { page ->
            base["page"] = page.toString()
            request(endpoint, credential, base)
        }.filter { it.posterPath != null }.distinctBy { it.id }
    }

    fun pickRouletteTitle(pool: List<TmdbResult>): TmdbResult? {
        if (pool.isEmpty()) return null
        val highRelevance = mutableListOf<TmdbResult>()
        val goodRelevance = mutableListOf<TmdbResult>()
        val discoveries = mutableListOf<TmdbResult>()
        pool.forEach { item ->
            val votes = item.voteCount.coerceAtLeast(0)
            val confidence = votes.toDouble() / (votes + 250.0)
            val bayesianRating = confidence * item.tmdbVoteAverage + (1.0 - confidence) * 6.2
            when {
                (bayesianRating >= 7.0 && votes >= 150) || (bayesianRating >= 6.5 && item.popularity >= 25.0) ->
                    highRelevance.add(item)
                bayesianRating >= 6.0 || item.popularity >= 8.0 -> goodRelevance.add(item)
                else -> discoveries.add(item)
            }
        }
        // 70/20/10 orienta as chances; se uma faixa estiver vazia, as outras recebem a chance disponível.
        val available = listOf(highRelevance, goodRelevance, discoveries).filter { it.isNotEmpty() }
        val selectedBand = when {
            highRelevance.isNotEmpty() && goodRelevance.isNotEmpty() && discoveries.isNotEmpty() ->
                when ((0 until 100).random()) { in 0..69 -> highRelevance; in 70..89 -> goodRelevance; else -> discoveries }
            highRelevance.isNotEmpty() && goodRelevance.isNotEmpty() ->
                if ((0 until 100).random() < 78) highRelevance else goodRelevance
            highRelevance.isNotEmpty() -> highRelevance
            goodRelevance.isNotEmpty() -> goodRelevance
            else -> discoveries
        }
        return selectedBand.random()
    }

    suspend fun shareCardDetails(id: Int, type: String, credential: String): TmdbShareCardDetails {
        val endpoint = if (type == "tv") "tv" else "movie"
        val (details, credits, images) = coroutineScope {
            val detailsTask = async { requestObject("/$endpoint/$id", credential, mapOf("language" to "pt-BR")) }
            val creditsTask = async { requestObject("/$endpoint/$id/credits", credential, mapOf("language" to "pt-BR")) }
            val imagesTask = async { requestObject("/$endpoint/$id/images", credential, mapOf("include_image_language" to "pt,null,en")) }
            Triple(detailsTask.await(), creditsTask.await(), imagesTask.await())
        }
        val genresJson = details.optJSONArray("genres")
        val genres = buildList {
            if (genresJson != null) for (i in 0 until genresJson.length()) {
                val name = genresJson.optJSONObject(i)?.optString("name").orEmpty()
                if (name.isNotBlank()) add(name)
            }
        }
        val leadActor = credits.optJSONArray("cast")?.optJSONObject(0)?.optString("name").orEmpty()
        val backdrops = images.optJSONArray("backdrops")
        val backdropPath = backdrops?.let { array ->
            (0 until array.length()).asSequence()
                .mapNotNull { array.optJSONObject(it)?.optString("file_path") }
                .firstOrNull { it.startsWith("/") }
        }
        val stillPaths = backdrops?.let { array ->
            (0 until array.length()).asSequence()
                .mapNotNull { array.optJSONObject(it)?.optString("file_path") }
                .filter { it.startsWith("/") && it != backdropPath }
                .take(3)
                .toList()
        }.orEmpty()
        val fanart = runCatching { FanartClient.artwork(id, type) }.getOrNull()
        return TmdbShareCardDetails(
            leadActor, genres, backdropPath, stillPaths,
            localizedTitle = details.optString(if (type == "tv") "name" else "title").ifBlank { details.optString(if (type == "tv") "original_name" else "original_title") },
            fanartBackgroundUrl = fanart?.backgroundUrl,
            fanartLogoUrl = fanart?.logoUrl,
            fanartPosterUrl = fanart?.posterUrl,
            fanartBannerUrl = fanart?.bannerUrl,
            fanartStillUrls = fanart?.stillUrls.orEmpty()
        )
    }

    suspend fun cast(id: Int, type: String, credential: String): List<TmdbCastMember> {
        val endpoint = if (type == "tv") "tv" else "movie"
        val cast = requestObject("/$endpoint/$id/credits", credential, mapOf("language" to "pt-BR")).optJSONArray("cast") ?: return emptyList()
        return buildList {
            for (i in 0 until cast.length()) {
                val person = cast.optJSONObject(i) ?: continue
                val name = person.optString("name").trim()
                if (name.isBlank()) continue
                add(TmdbCastMember(person.optInt("id"), name, person.optString("character"), person.optString("profile_path").takeIf { it.startsWith("/") }))
                if (size >= 10) break
            }
        }
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


    suspend fun rating(id: Int, type: String, credential: String): Double {
        val endpoint = if (type == "tv") "tv" else "movie"
        return requestObject("/$endpoint/$id", credential, mapOf("language" to "pt-BR"))
            .optDouble("vote_average", 0.0)
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

    private fun yearPeriodBounds(period: String): Pair<String, String>? = when (period) {
        "2020–atual" -> "2020-01-01" to "${java.time.Year.now().value}-12-31"
        "2010–2019" -> "2010-01-01" to "2019-12-31"
        "2000–2009" -> "2000-01-01" to "2009-12-31"
        "1990–1999" -> "1990-01-01" to "1999-12-31"
        "1980–1989" -> "1980-01-01" to "1989-12-31"
        "Antes de 1980" -> "1900-01-01" to "1979-12-31"
        else -> null
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
                        tmdbVoteAverage = item.optDouble("vote_average", 0.0),
                        voteCount = item.optInt("vote_count", 0),
                        popularity = item.optDouble("popularity", 0.0)
                    ))
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
