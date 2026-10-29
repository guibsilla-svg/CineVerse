package br.com.minhamidia.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class TmdbResult(val id: Int, val title: String, val type: String, val year: String, val posterPath: String?, val overview: String, val releaseDate: String) {
    fun toMediaItem() = MediaItem(tmdbId=id, title=title, mediaType=type, year=year, posterPath=posterPath, overview=overview, releaseDate=releaseDate)
}
object TmdbClient {
    suspend fun search(query: String, apiKey: String): List<TmdbResult> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val endpoint = "https://api.themoviedb.org/3/search/multi?query=$encoded&language=pt-BR&include_adult=false&api_key=${apiKey.trim()}"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply { requestMethod="GET"; connectTimeout=12000; readTimeout=12000 }
            try {
                if (connection.responseCode !in 200..299) throw IllegalStateException("TMDB respondeu com código ${connection.responseCode}. Confira a chave de API.")
                val results = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optJSONArray("results") ?: return@withContext emptyList()
                buildList {
                    for (i in 0 until results.length()) {
                        val item = results.optJSONObject(i) ?: continue
                        val type = item.optString("media_type")
                        if (type != "movie" && type != "tv") continue
                        val title = item.optString(if (type=="movie") "title" else "name").trim()
                        if (title.isBlank()) continue
                        val date = item.optString(if (type=="movie") "release_date" else "first_air_date")
                        add(TmdbResult(item.optInt("id"), title, type, date.take(4), item.optString("poster_path").takeIf { it.isNotBlank() && it!="null" }, item.optString("overview"), date))
                    }
                }
            } finally { connection.disconnect() }
        }
}