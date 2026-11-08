package br.com.minhamidia.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class RawgGame(
    val id: Int,
    val name: String,
    val released: String,
    val backgroundImage: String,
    val rating: Double,
    val metacritic: Int,
    val genres: String,
    val platforms: String,
    val overview: String = ""
) {
    val year: String get() = released.take(4)
    fun toEntity() = GameItem(
        rawgId = id, name = name, released = released, year = year,
        backgroundImage = backgroundImage, overview = overview, rating = rating,
        metacritic = metacritic, genres = genres, platforms = platforms
    )
}

object RawgClient {
    // RAWG platform IDs: PlayStation 4 = 18; PlayStation 5 = 187.
    private const val PLAYSTATION_PLATFORMS = "18,187"
    private val ps4OnlyExceptions = setOf("afro samurai 2 revenge of kuma volume one", "just deal with it!", "robinson: the journey", "we sing", "hitman go: definitive edition", "shadwen")

    private suspend fun get(path: String, apiKey: String): JSONObject = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Configure sua chave RAWG em Mais > Configurações." }
        val separator = if (path.contains("?")) "&" else "?"
        val url = URL("https://api.rawg.io/api/$path$separator" +
            "key=${URLEncoder.encode(apiKey.trim(), "UTF-8")}&platforms=$PLAYSTATION_PLATFORMS")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "CineVerso-Android")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException(
                    when (code) {
                        401, 403 -> "A chave RAWG é inválida ou não está autorizada."
                        429 -> "Limite de consultas RAWG atingido. Tente novamente mais tarde."
                        else -> "RAWG retornou erro HTTP $code${if (detail.isNotBlank()) ": ${detail.take(160)}" else ""}."
                    }
                )
            }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(item: JSONObject, overview: String = ""): RawgGame {
        val genresJson = item.optJSONArray("genres")
        val genres = (0 until (genresJson?.length() ?: 0))
            .mapNotNull { genresJson?.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
            .joinToString(", ")
        val platformsJson = item.optJSONArray("platforms")
        val platforms = (0 until (platformsJson?.length() ?: 0))
            .mapNotNull { platformsJson?.optJSONObject(it)?.optJSONObject("platform")?.optString("name")?.takeIf(String::isNotBlank) }
            .filter { it.contains("PlayStation 4", true) || it.contains("PlayStation 5", true) }
            .distinct().joinToString(", ")
        return RawgGame(
            id = item.optInt("id"),
            name = item.optString("name"),
            released = item.optString("released"),
            backgroundImage = item.optString("background_image"),
            rating = item.optDouble("rating", 0.0),
            metacritic = item.optInt("metacritic"),
            genres = genres,
            platforms = platforms,
            overview = overview
        )
    }

    suspend fun catalog(apiKey: String, category: String, search: String = ""): List<RawgGame> {
        val params = buildString {
            append("games?page_size=30&ordering=")
            append(when (category) {
                "Mais bem avaliados" -> "-rating"
                "Lançamentos" -> "-released"
                else -> "-added"
            })
            if (search.isNotBlank()) append("&search=${URLEncoder.encode(search.trim(), "UTF-8")}")
        }
        val response = get(params, apiKey)
        val results = response.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { index ->
            results.optJSONObject(index)?.let(::parse)
        }.filter { it.id > 0 && it.name.isNotBlank() && it.backgroundImage.isNotBlank() && it.name.trim().lowercase() !in ps4OnlyExceptions }
    }

    suspend fun details(id: Int, apiKey: String): RawgGame {
        val response = get("games/$id", apiKey)
        val description = response.optString("description_raw").ifBlank {
            android.text.Html.fromHtml(response.optString("description"), android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        }
        return parse(response, description.trim())
    }
}
