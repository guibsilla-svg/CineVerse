package br.com.minhamidia.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Searches specialist frame-capture archives. Search results are restricted to
 * screenshot/still archives rather than general image hosts, posters or artwork.
 * A result is still checked as an image by ShareCard before it is rendered.
 */
object SceneImageSearchClient {
    private val trustedHosts = setOf(
        "film-grab.com",
        "www.film-grab.com",
        "movie-screencaps.com",
        "www.movie-screencaps.com",
        "screenmusings.org",
        "www.screenmusings.org",
        "caps-a-holic.com",
        "www.caps-a-holic.com",
        "screencapped.net",
        "www.screencapped.net"
    )

    suspend fun search(title: String, type: String, apiKey: String): List<String> =
        withContext(Dispatchers.IO) {
            require(apiKey.isNotBlank()) {
                "Configure a chave da Brave Search em Mais > Config. para localizar capturas reais de cenas."
            }
            val cleanTitle = title.trim()
            require(cleanTitle.isNotBlank()) { "O título original não está disponível para pesquisar as cenas." }
            val mediaTerm = if (type == "tv") "TV episode screencaps" else "movie screencaps film stills"
            val queries = listOf(
                "\"$cleanTitle\" $mediaTerm -poster -fanart -wallpaper -banner -logo",
                "\"$cleanTitle\" scene captures screenshots"
            )
            val found = linkedSetOf<String>()
            for (query in queries) {
                if (found.size >= 12) break
                val encoded = URLEncoder.encode(query, "UTF-8")
                val endpoint = "https://api.search.brave.com/res/v1/images/search?q=$encoded&count=100&country=ALL&search_lang=en&safesearch=strict"
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 12000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-Subscription-Token", apiKey.trim())
                    setRequestProperty("User-Agent", "CineVerso-Android")
                }
                try {
                    val code = connection.responseCode
                    val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) {
                        val detail = runCatching { JSONObject(body).optJSONObject("error")?.optString("detail") }.getOrNull()
                        throw IllegalStateException(
                            when (code) {
                                401, 403 -> "Chave da Brave Search inválida ou sem permissão."
                                429 -> "Limite de pesquisas de imagens atingido na Brave Search. Tente novamente mais tarde."
                                else -> "A pesquisa de cenas falhou ($code): ${detail.orEmpty()}"
                            }
                        )
                    }
                    val results = JSONObject(body).optJSONArray("results") ?: continue
                    for (index in 0 until results.length()) {
                        val result = results.optJSONObject(index) ?: continue
                        val host = result.optJSONObject("meta_url")?.optString("hostname")
                            ?.lowercase().orEmpty()
                        if (host !in trustedHosts) continue
                        val titleText = result.optString("title").lowercase()
                        val pageUrl = result.optString("url").lowercase()
                        val rejectedTerms = listOf("poster", "fanart", "wallpaper", "banner", "logo", "key art", "cover")
                        if (rejectedTerms.any { titleText.contains(it) || pageUrl.contains(it.replace(" ", "-")) }) continue
                        val imageUrl = result.optJSONObject("properties")?.optString("url")
                            ?.takeIf { it.startsWith("https://") }
                            ?: continue
                        found.add(imageUrl)
                        if (found.size >= 12) break
                    }
                } finally {
                    connection.disconnect()
                }
            }
            found.toList()
        }
}
