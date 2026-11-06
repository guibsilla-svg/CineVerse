package br.com.minhamidia.data

import br.com.minhamidia.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class FanartArtwork(
    val backgroundUrl: String?,
    val logoUrl: String?,
    val posterUrl: String?,
    val bannerUrl: String?,
    val stillUrls: List<String>
)

object FanartClient {
    private const val BASE_URL = "https://webservice.fanart.tv/v3.2"

    suspend fun artwork(tmdbId: Int, type: String): FanartArtwork? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.FANART_API_KEY.trim()
        if (apiKey.isBlank()) return@withContext null

        val resource = if (type == "tv") "tv" else "movies"
        val source = if (type == "tv") "&source=tmdb" else ""
        val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
        val url = "$BASE_URL/$resource/$tmdbId?api_key=$encodedKey$source"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6500
            readTimeout = 8000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "CineVerso-Android")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) return@withContext null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val backgroundKeys = if (type == "tv")
                listOf("showbackground", "tvbackground")
            else listOf("moviebackground", "movie4kbackground")
            val logoKeys = if (type == "tv")
                listOf("hdtvlogo", "clearlogo", "clearart")
            else listOf("hdmovielogo", "movielogo", "hdmovieclearart")
            val posterKeys = if (type == "tv") listOf("tvposter") else listOf("movieposter")
            val bannerKeys = if (type == "tv") listOf("tvbanner") else listOf("moviebanner")
            // Dedicated frame thumbnails only. characterart/movieart are promotional artwork.
            val stillKeys = if (type == "tv")
                listOf("tvthumb", "seasonthumb")
            else listOf("moviethumb")

            fun best(keys: List<String>): String? = keys.asSequence()
                .flatMap { key ->
                    val array = json.optJSONArray(key) ?: return@flatMap emptySequence()
                    (0 until array.length()).asSequence().mapNotNull { array.optJSONObject(it) }
                }
                .sortedWith(compareByDescending<JSONObject> {
                    when (it.optString("lang")) { "en" -> 3; "00" -> 2; "" -> 1; else -> 0 }
                }.thenByDescending { it.optString("likes").toIntOrNull() ?: 0 })
                .map { it.optString("url") }
                .firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
                ?.replaceFirst("http://", "https://")

            val stills = stillKeys.flatMap { key ->
                val array = json.optJSONArray(key) ?: return@flatMap emptyList()
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.optString("url")?.takeIf {
                        it.startsWith("https://") || it.startsWith("http://")
                    }?.replaceFirst("http://", "https://")
                }
            }.distinct().take(6)

            FanartArtwork(
                backgroundUrl = best(backgroundKeys),
                logoUrl = best(logoKeys),
                posterUrl = best(posterKeys),
                bannerUrl = best(bannerKeys),
                stillUrls = stills
            )
        } finally {
            connection.disconnect()
        }
    }
}
