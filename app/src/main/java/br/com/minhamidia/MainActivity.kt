package br.com.minhamidia

import android.content.Intent
import android.app.DownloadManager
import android.os.Environment
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import android.widget.Toast
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import br.com.minhamidia.data.AppDatabase
import br.com.minhamidia.data.MediaItem
import br.com.minhamidia.data.TmdbClient
import br.com.minhamidia.data.TmdbFranchise
import br.com.minhamidia.data.TmdbResult
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.ensureActive
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

private val Night = Color(0xFF08070D)
private val Panel = Color(0xFF171321)
private val Purple = Color(0xFFAC65FF)
private val TextMain = Color(0xFFF7F3FF)
private val Muted = Color(0xFFB7AEC6)
private fun cinePalette(name: String): Triple<Color, Color, Color> = when (name) {
    "Azul" -> Triple(Color(0xFF07101C), Color(0xFF111F32), Color(0xFF54B8FF))
    "Rubi" -> Triple(Color(0xFF18090E), Color(0xFF2B111B), Color(0xFFFF6685))
    "Esmeralda" -> Triple(Color(0xFF07140F), Color(0xFF12251C), Color(0xFF55D6A0))
    "Dourado" -> Triple(Color(0xFF171108), Color(0xFF29200F), Color(0xFFE6B95C))
    else -> Triple(Night, Panel, Purple)
}


private fun markAllSeriesEpisodesWatched(progressJson: String): String {
    val seasons = runCatching { JSONArray(progressJson) }.getOrElse { JSONArray() }
    for (i in 0 until seasons.length()) {
        val season = seasons.optJSONObject(i) ?: continue
        season.put("status", "WATCHED")
        val episodes = season.optJSONArray("episodeList") ?: continue
        for (j in 0 until episodes.length()) {
            episodes.optJSONObject(j)?.put("watched", true)
        }
    }
    return seasons.toString()
}

private fun mergeSavedSeasonProgress(importedJson: String, savedJson: String): String {
    val fresh = JSONArray(importedJson)
    val saved = runCatching { JSONArray(savedJson) }.getOrNull() ?: return fresh.toString()
    for (i in 0 until fresh.length()) {
        val season = fresh.optJSONObject(i) ?: continue
        val oldSeason = (0 until saved.length()).mapNotNull { saved.optJSONObject(it) }
            .firstOrNull { it.optInt("number") == season.optInt("number") } ?: continue
        season.put("status", oldSeason.optString("status", "WATCHLIST"))
        val oldEpisodes = oldSeason.optJSONArray("episodeList") ?: continue
        val newEpisodes = season.optJSONArray("episodeList") ?: continue
        for (e in 0 until newEpisodes.length()) {
            val episode = newEpisodes.optJSONObject(e) ?: continue
            val oldEpisode = (0 until oldEpisodes.length()).mapNotNull { oldEpisodes.optJSONObject(it) }
                .firstOrNull { it.optInt("number") == episode.optInt("number") }
            if (oldEpisode != null) episode.put("watched", oldEpisode.optBoolean("watched", false))
        }
    }
    return fresh.toString()
}

class MainActivity : ComponentActivity() {
    private fun installUpdateFile(file: File) {
        if (!file.exists()) {
            Toast.makeText(this, "O arquivo da atualização não foi encontrado. Tente novamente.", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            getSharedPreferences("cineverse_settings", MODE_PRIVATE).edit()
                .putString("pending_update_apk", file.absolutePath).apply()
            Toast.makeText(this, "Autorize a instalação do CineVerso nesta tela e volte ao aplicativo.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        runCatching {
            val contentUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(installIntent)
        }.onFailure {
            Toast.makeText(this, "Não foi possível abrir o instalador. Verifique a permissão para instalar apps.", Toast.LENGTH_LONG).show()
        }
    }

    private fun downloadAndInstallUpdate(url: String, version: String) {
        Toast.makeText(this, "Baixando atualização $version.", Toast.LENGTH_LONG).show()
        lifecycleScope.launch {
            var installed = false
            var lastError = "falha de conexão"
            for (attempt in 1..3) {
                val destination = File(cacheDir, "updates/cineverso-update-$version.apk")
                destination.parentFile?.mkdirs()
                destination.delete()
                try {
                    withContext(Dispatchers.IO) {
                        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                            instanceFollowRedirects = true
                            connectTimeout = 20000
                            readTimeout = 120000
                            requestMethod = "GET"
                            setRequestProperty("User-Agent", "CineVerso-Android")
                            setRequestProperty("Accept", "application/octet-stream")
                            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                            setRequestProperty("Cache-Control", "no-cache")
                        }
                        try {
                            val responseCode = connection.responseCode
                            if (responseCode != HttpURLConnection.HTTP_OK) {
                                throw java.io.IOException("HTTP $responseCode")
                            }
                            connection.inputStream.use { input ->
                                destination.outputStream().buffered().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            if (destination.length() < 1024L * 1024L) {
                                throw java.io.IOException("arquivo incompleto (${destination.length()} bytes)")
                            }
                            val signature = destination.inputStream().use { input ->
                                ByteArray(4).also { input.read(it) }
                            }
                            if (signature[0] != 0x50.toByte() || signature[1] != 0x4B.toByte()) {
                                throw java.io.IOException("resposta recebida não é um APK válido")
                            }
                        } finally {
                            connection.disconnect()
                        }
                    }
                    installUpdateFile(destination)
                    installed = true
                    break
                } catch (error: Exception) {
                    lastError = error.message ?: error.javaClass.simpleName
                    destination.delete()
                    val isServerError = lastError.contains("HTTP 5")
                    if (attempt < 3 && (isServerError || error is java.io.IOException)) {
                        delay(1500L * attempt)
                    } else {
                        break
                    }
                }
            }
            if (!installed) {
                Toast.makeText(
                    this@MainActivity,
                    "Não foi possível baixar a atualização após 3 tentativas: $lastError",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("cineverse_settings", MODE_PRIVATE)
        val pendingPath = prefs.getString("pending_update_apk", null) ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
            prefs.edit().remove("pending_update_apk").apply()
            installUpdateFile(File(pendingPath))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = AppDatabase.get(this).mediaDao()
        val prefs = getSharedPreferences("cineverse_settings", MODE_PRIVATE)
        val alertWork = PeriodicWorkRequestBuilder<EpisodeAlertWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("cineverse_episode_alerts", ExistingPeriodicWorkPolicy.KEEP, alertWork)
        setContent {
            val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            LaunchedEffect(Unit) {
                val release = withContext(Dispatchers.IO) {
                    runCatching {
                        val connection = (URL("https://api.github.com/repos/guibsilla-svg/CineVerse/releases?per_page=100").openConnection() as HttpURLConnection).apply {
                            connectTimeout = 15000
                            readTimeout = 20000
                            setRequestProperty("Accept", "application/vnd.github+json")
                            setRequestProperty("User-Agent", "CineVerso-Android")
                            setRequestProperty("Cache-Control", "no-cache")
                        }
                        try {
                            val releases = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
                            val newestRelease = (0 until releases.length())
                                .mapNotNull { releases.optJSONObject(it) }
                                .filter {
                                    it.optString("tag_name").removePrefix("v").startsWith("1.0.") &&
                                        !it.optBoolean("draft") && !it.optBoolean("prerelease")
                                }
                                .maxWithOrNull { a, b ->
                                    compareVersions(
                                        a.optString("tag_name").removePrefix("v"),
                                        b.optString("tag_name").removePrefix("v")
                                    )
                                }
                            val tag = newestRelease?.optString("tag_name")?.removePrefix("v").orEmpty()
                            val apk = newestRelease?.optJSONArray("assets")?.let { assets ->
                                (0 until assets.length()).map { assets.getJSONObject(it) }
                                    .firstOrNull { it.optString("name") == "cineverse.apk" }
                                    ?.optString("url")
                            }
                            if (tag.isNotBlank() && apk?.startsWith("https://api.github.com/") == true &&
                                compareVersions(tag, BuildConfig.VERSION_NAME) > 0
                            ) tag to apk else null
                        } finally { connection.disconnect() }
                    }.getOrNull()
                }
                if (release != null) {
                    downloadAndInstallUpdate(release.second, release.first)
                }
            }
            val palette = cinePalette("Roxo")
            var homeOrder by remember { mutableStateOf(prefs.getString("home_order", "Padrão") ?: "Padrão") }
            var featuredTitle by remember { mutableStateOf(prefs.getString("featured_title", "") ?: "") }
            var apiKey by remember { mutableStateOf(prefs.getString("tmdb_key", "")?.takeIf { it.isNotBlank() } ?: BuildConfig.TMDB_API_KEY) }
            var query by remember { mutableStateOf("") }
            var page by remember { mutableStateOf("Início") }
            var detailReturnPage by remember { mutableStateOf("Explorar") }
            val pageHistory = remember { mutableStateListOf<String>() }
            fun navigateTo(destination: String) {
                if (destination != page) {
                    if (destination == "Detalhes") detailReturnPage = page
                    pageHistory.add(page)
                    page = destination
                }
            }
            var filter by remember { mutableStateOf("Quero assistir") }
            var categoryFilter by remember { mutableStateOf("FILMES") }
            var yearFilter by remember { mutableStateOf("Todos") }
            var searchYearPeriod by remember { mutableStateOf("Todos") }
            var exploreYearPeriod by remember { mutableStateOf("Todos") }
            var detailCast by remember { mutableStateOf(emptyList<br.com.minhamidia.data.TmdbCastMember>()) }
            var detailCastLoading by remember { mutableStateOf(false) }
            var sortMode by remember { mutableStateOf("Manual") }
            var draggedPosterId by remember { mutableStateOf<Long?>(null) }
            var dragOrder by remember { mutableStateOf<List<Long>>(emptyList()) }
            var dragPointer by remember { mutableStateOf(Offset.Zero) }
            val latestDragOrder by rememberUpdatedState(dragOrder)
            var results by remember { mutableStateOf(emptyList<TmdbResult>()) }
            var detailItem by remember { mutableStateOf<TmdbResult?>(null) }
            fun returnFromDetails() {
                val destination = detailReturnPage
                if (pageHistory.lastOrNull() == destination) {
                    pageHistory.removeAt(pageHistory.lastIndex)
                }
                page = destination
            }
            var detailSeasons by remember { mutableStateOf("[]") }
            var detailLoading by remember { mutableStateOf(false) }
            var detailRuntimeMinutes by remember { mutableStateOf<Int?>(null) }
            var detailRuntimeLoading by remember { mutableStateOf(false) }
            var detailTrailer by remember { mutableStateOf<br.com.minhamidia.data.TmdbTrailer?>(null) }
            var detailTrailerLoading by remember { mutableStateOf(false) }
            var personalRatingInput by remember { mutableIntStateOf(0) }
            var franchiseCollections by remember { mutableStateOf(emptyList<br.com.minhamidia.data.TmdbFranchise>()) }
            var myListFranchiseCollections by remember { mutableStateOf(emptyList<TmdbFranchise>()) }
            var franchiseView by remember { mutableStateOf("Em andamento") }
            var ignoredFranchiseIds by remember {
                mutableStateOf(prefs.getStringSet("ignored_franchise_ids", emptySet())?.toSet() ?: emptySet())
            }
            fun setFranchiseIgnored(id: Int, ignored: Boolean) {
                val key = id.toString()
                val updated = if (ignored) ignoredFranchiseIds + key else ignoredFranchiseIds - key
                ignoredFranchiseIds = updated
                prefs.edit().putStringSet("ignored_franchise_ids", updated).apply()
            }
            var lastNormalListFilter by remember { mutableStateOf("Quero assistir") }
            var recommendedMovies by remember { mutableStateOf(emptyList<TmdbResult>()) }
            var recommendedShows by remember { mutableStateOf(emptyList<TmdbResult>()) }
            var recommendationsLoading by remember { mutableStateOf(false) }
            var recommendationsError by remember { mutableStateOf("") }
            var message by remember { mutableStateOf("Sua próxima história começa aqui.") }
            var sharingRecommendation by remember { mutableStateOf(false) }
            var pendingSceneItem by remember { mutableStateOf<TmdbResult?>(null) }
            var pendingHeroUri by remember { mutableStateOf<Uri?>(null) }
            var pendingOriginalTitle by remember { mutableStateOf("") }
            var waitingForHeroSelection by remember { mutableStateOf(false) }
            var waitingForSceneSelection by remember { mutableStateOf(false) }
            val heroImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selectedUri ->
                if (selectedUri != null) {
                    runCatching {
                        contentResolver.takePersistableUriPermission(selectedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    pendingHeroUri = selectedUri
                    waitingForHeroSelection = false
                    val selectedItem = pendingSceneItem
                    if (selectedItem != null) {
                        val sceneTitle = pendingOriginalTitle.ifBlank { selectedItem.title }
                        val sceneTerm = if (selectedItem.type == "tv") "episode screenshots" else "movie scenes screenshots"
                        val sceneSearchUrl = "https://www.google.com/search?tbm=isch&q=" +
                            Uri.encode("$sceneTitle $sceneTerm -poster -fanart -wallpaper -banner -logo")
                        waitingForSceneSelection = true
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sceneSearchUrl)))
                    }
                } else {
                    waitingForHeroSelection = false
                }
            }
            val sceneImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selectedUris ->
                val selectedItem = pendingSceneItem
                waitingForSceneSelection = false
                if (selectedItem != null) {
                    if (selectedUris.size < 3) {
                        Toast.makeText(this@MainActivity, "Selecione três capturas de cenas para continuar.", Toast.LENGTH_LONG).show()
                        sharingRecommendation = false
                        pendingSceneItem = null
                    } else {
                        Toast.makeText(this@MainActivity, "Gerando seu card com as três cenas…", Toast.LENGTH_LONG).show()
                        sharingRecommendation = true
                        lifecycleScope.launch {
                            try {
                                val imageUri = createCineVersoShareCard(this@MainActivity, selectedItem, apiKey, selectedUris.take(3), pendingHeroUri)
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/png"
                                    putExtra(Intent.EXTRA_STREAM, imageUri)
                                    clipData = android.content.ClipData.newUri(contentResolver, "Card CineVerso", imageUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                startActivity(Intent.createChooser(shareIntent, "Compartilhar recomendação"))
                            } catch (error: Exception) {
                                Toast.makeText(this@MainActivity, "Falha ao gerar o card: ${error.message ?: "verifique as imagens selecionadas"}", Toast.LENGTH_LONG).show()
                            } finally {
                                sharingRecommendation = false
                                pendingSceneItem = null
                                pendingHeroUri = null
                                pendingOriginalTitle = ""
                                waitingForHeroSelection = false
                                waitingForSceneSelection = false
                            }
                        }
                    }
                }
            }
            var showKey by remember { mutableStateOf(false) }
            var updateChecking by remember { mutableStateOf(false) }
            var updateMessage by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            var roulettePick by remember { mutableStateOf<MediaItem?>(null) }
            var showRouletteDialog by remember { mutableStateOf(false) }
            var rouletteType by remember { mutableStateOf("movie") }
            var rouletteMode by remember { mutableStateOf("DISCOVER") }
            var rouletteSuggestion by remember { mutableStateOf<TmdbResult?>(null) }
            var rouletteSpinning by remember { mutableStateOf(false) }
            var rouletteGenre by remember { mutableStateOf("Todos") }
            var rouletteGenreMenu by remember { mutableStateOf(false) }
            var rouletteYear by remember { mutableStateOf("Todos") }
            var rouletteYearMenu by remember { mutableStateOf(false) }
            var rouletteCarouselItems by remember { mutableStateOf(emptyList<TmdbResult>()) }
            var rouletteError by remember { mutableStateOf("") }
            var exploreType by remember { mutableStateOf("movie") }
            var exploreCategory by remember { mutableStateOf("trending") }
            var previousExploreCategory by remember { mutableStateOf("trending") }
            var exploreGenre by remember { mutableStateOf("Todos") }
            var exploreQuery by remember { mutableStateOf("") }
            var explorePage by remember { mutableStateOf(1) }
            var exploreResults by remember { mutableStateOf(emptyList<TmdbResult>()) }
            var exploreLoading by remember { mutableStateOf(false) }
            var exploreError by remember { mutableStateOf("") }
            var exploreCanLoadMore by remember { mutableStateOf(true) }
            var exploreSearchLoadedKey by remember { mutableStateOf("") }
            var exploreCatalogLoadedKey by remember { mutableStateOf("") }
            var exploreFranchises by remember { mutableStateOf(emptyList<TmdbFranchise>()) }
            var selectedExploreFranchise by remember { mutableStateOf<TmdbFranchise?>(null) }
            var exploreFranchisesLoading by remember { mutableStateOf(false) }
            var exploreFranchisePage by remember { mutableStateOf(0) }
            var exploreFranchisesCanLoadMore by remember { mutableStateOf(true) }
            val exploreListState = rememberLazyListState()
            val exploreFranchiseCatalogState = rememberLazyListState()
            val exploreFranchiseMoviesState = rememberLazyListState()
            val searchResultsState = rememberLazyListState()
            val myListFranchiseState = rememberLazyListState()
            val statisticsState = rememberLazyListState()
            val calendarState = rememberLazyListState()
            val myListGridState = rememberLazyGridState()
            val rouletteWheelRotation = remember { Animatable(0f) }
            val rouletteScope = rememberCoroutineScope()
            var editingSeries by remember { mutableStateOf<MediaItem?>(null) }
            LaunchedEffect(page, detailItem?.id, detailItem?.type, apiKey) {
                val selected = detailItem
                if (page == "Detalhes" && selected != null && apiKey.isNotBlank()) {
                    detailCast = emptyList(); detailCastLoading = true
                    detailCast = runCatching { TmdbClient.cast(selected.id, selected.type, apiKey).take(10) }.getOrDefault(emptyList())
                    detailCastLoading = false
                } else { detailCast = emptyList(); detailCastLoading = false }
            }
            LaunchedEffect(page, detailItem?.id, detailItem?.type, apiKey) {
                val selected = detailItem
                detailRuntimeMinutes = null
                if (page == "Detalhes" && selected != null && selected.type == "movie" && apiKey.isNotBlank()) {
                    detailRuntimeLoading = true
                    detailRuntimeMinutes = runCatching {
                        TmdbClient.movieReleaseInfo(selected.id, apiKey).optInt("runtime", 0)
                    }.getOrNull()?.takeIf { it > 0 }
                    detailRuntimeLoading = false
                } else {
                    detailRuntimeLoading = false
                }
            }

            LaunchedEffect(page, detailItem?.id, detailItem?.type, apiKey) {
                val selected = detailItem
                if (page == "Detalhes" && selected != null && apiKey.isNotBlank()) {
                    detailTrailer = null
                    detailTrailerLoading = true
                    detailTrailer = runCatching { TmdbClient.trailer(selected.id, selected.type, apiKey) }.getOrNull()
                    detailTrailerLoading = false
                } else {
                    detailTrailer = null
                    detailTrailerLoading = false
                }
            }

            // Always consume Android Back, including at the home screen, so the app
            // never exits accidentally. At the root with no previous destination, stay
            // on Início instead of delegating Back to the system.
            BackHandler(enabled = true) {
                when {
                    showKey -> showKey = false
                    editingSeries != null -> editingSeries = null
                    page == "Detalhes" -> returnFromDetails()
                    page == "Minha lista" && filter == "Franquias" -> filter = lastNormalListFilter
                    // A franquia aberta é uma tela interna de Explorar.
                    // O Back do Android deve fechá-la antes de navegar para fora da seção.
                    selectedExploreFranchise != null -> {
                        selectedExploreFranchise = null
                        if (page != "Explorar") {
                            pageHistory.removeAll { it == "Explorar" }
                            page = "Explorar"
                        }
                    }
                    page == "Explorar" && exploreCategory == "franchises" -> {
                        exploreCategory = previousExploreCategory
                        selectedExploreFranchise = null
                        exploreCatalogLoadedKey = ""
                    }
                    pageHistory.isNotEmpty() -> page = pageHistory.removeAt(pageHistory.lastIndex)
                    page != "Início" -> page = "Início"
                    else -> Unit
                }
            }
            var seasonInput by remember { mutableStateOf("") }
            var totalEpisodesInput by remember { mutableStateOf("") }
            var watchedEpisodesInput by remember { mutableStateOf("") }
            var seasonProgressInput by remember { mutableStateOf("") }
            var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()) }
            val all by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
            LaunchedEffect(all) {
                all.filter { it.mediaType == "movie" && it.status == "WATCHING" }.forEach { movie ->
                    dao.update(movie.copy(status = "WATCHLIST"))
                }
            }
            val selectedPersonalRating = detailItem?.let { selected ->
                all.firstOrNull { it.tmdbId == selected.id && it.mediaType == selected.type }?.personalRating
            } ?: 0
            val franchiseIds = all.mapNotNull { it.franchiseId?.takeIf { id -> id > 0 } }.distinct().sorted()
            val franchiseIdsKey = franchiseIds.joinToString(",")
            val tmdbItemCount = all.count { it.tmdbId != null }
            val movieTmdbIdsKey = all.asSequence()
                .filter { it.mediaType == "movie" && it.tmdbId != null }
                .mapNotNull { it.tmdbId }
                .distinct()
                .sorted()
                .joinToString(",")
            val tasteProfileKey = all.filter { it.rating >= 4 || favorites.contains(it.id.toString()) }
                .joinToString("|") { "${it.id}:${it.mediaType}:${it.title}:${it.overview}:${it.genres}:${it.rating}" }
            LaunchedEffect(detailItem?.id, detailItem?.type, selectedPersonalRating) {
                personalRatingInput = selectedPersonalRating
            }
            LaunchedEffect(apiKey, tmdbItemCount) {
                if (apiKey.isNotBlank()) {
                    all.filter { it.tmdbId != null && it.tmdbVoteAverage <= 0.0 }.forEach { media ->
                        runCatching {
                            val score = TmdbClient.rating(media.tmdbId!!, media.mediaType, apiKey)
                            if (score > 0.0) dao.update(media.copy(tmdbVoteAverage = score))
                        }
                    }
                }
            }
            LaunchedEffect(apiKey, movieTmdbIdsKey) {
                if (apiKey.isNotBlank()) {
                    // Identify missing collections concurrently, with a small limit to avoid
                    // flooding TMDB and delaying the franchise suggestions screen.
                    val pendingMovies = all.filter {
                        it.mediaType == "movie" && it.tmdbId != null && (it.franchiseId == null || it.franchiseId <= 0)
                    }
                    val semaphore = kotlinx.coroutines.sync.Semaphore(4)
                    coroutineScope {
                        pendingMovies.map { media ->
                            async {
                                val franchise = semaphore.withPermit {
                                    runCatching { TmdbClient.franchiseForMovie(media.tmdbId!!, apiKey) }.getOrNull()
                                }
                                if (franchise != null && franchise.id > 0) {
                                    dao.update(media.copy(franchiseId = franchise.id, franchiseName = franchise.name))
                                }
                            }
                        }.awaitAll()
                    }
                }
            }
            LaunchedEffect(page, apiKey, franchiseIdsKey) {
                if (page == "Franquias" && apiKey.isNotBlank()) {
                    val loadedCollections = franchiseIds.mapNotNull { id ->
                        runCatching { TmdbClient.franchiseCollection(id, apiKey) }.getOrNull()
                    }
                    franchiseCollections = loadedCollections
                } else if (page != "Franquias") franchiseCollections = emptyList()
            }
            LaunchedEffect(filter, apiKey, franchiseIdsKey) {
                if (filter == "Franquias" && apiKey.isNotBlank()) {
                    // Keep already loaded collections in memory when opening details.
                    // Fetch only missing collections, concurrently, and merge without duplicates.
                    val loadedIds = myListFranchiseCollections.map { it.id }.toSet()
                    val missingIds = franchiseIds.filterNot { it in loadedIds }
                    if (missingIds.isNotEmpty()) {
                        val semaphore = kotlinx.coroutines.sync.Semaphore(4)
                        val fetched = coroutineScope {
                            missingIds.map { id ->
                                async {
                                    semaphore.withPermit {
                                        runCatching { TmdbClient.franchiseCollection(id, apiKey) }.getOrNull()
                                    }
                                }
                            }.awaitAll().filterNotNull()
                        }
                        myListFranchiseCollections = (myListFranchiseCollections + fetched).distinctBy { it.id }
                    }
                }
            }

            LaunchedEffect(apiKey, tasteProfileKey, favorites, homeOrder) {
                if (apiKey.isNotBlank()) {
                    recommendationsLoading = true
                    recommendationsError = ""
                    val now = System.currentTimeMillis()
                    val lastUpdate = prefs.getLong("trending_last_update", 0L)
                    val cachedMovies = prefs.getString("trending_movies_cache", null)
                    val cachedShows = prefs.getString("trending_shows_cache", null)
                    // Older recommendation caches may contain zero ratings from before
                    // TMDB scores were displayed. Force one fresh fetch after this update.
                    val ratingCacheVersion = prefs.getInt("trending_rating_cache_version", 0)
                    // Refresh TMDB trending lists on every app launch; do not let a stale
                    // local cache make the Home screen look permanently unchanged.
                    val cacheValid = false

                    fun decodeCache(json: String?): List<TmdbResult> = runCatching {
                        val array = JSONArray(json ?: "[]")
                        (0 until array.length()).mapNotNull { index ->
                            val item = array.optJSONObject(index) ?: return@mapNotNull null
                            TmdbResult(
                                id = item.optInt("id"),
                                title = item.optString("title"),
                                type = item.optString("type"),
                                year = item.optString("year"),
                                posterPath = item.optString("posterPath").takeIf { it.isNotBlank() && it != "null" },
                                overview = item.optString("overview"),
                                releaseDate = item.optString("releaseDate"),
                                tmdbVoteAverage = item.optDouble("tmdbVoteAverage", 0.0)
                            )
                        }
                    }.getOrDefault(emptyList())

                    if (cacheValid) {
                        recommendedMovies = decodeCache(cachedMovies)
                        recommendedShows = decodeCache(cachedShows)
                    } else {
                        // Keep the previous rows visible while refreshing to avoid a blank flash.
                        val movieResult = runCatching {
                            val trending = TmdbClient.trendingMovies(apiKey).filter { it.posterPath != null }
                            if (trending.isNotEmpty()) trending else TmdbClient.popularMovies(apiKey).filter { it.posterPath != null }
                        }
                        val showResult = runCatching {
                            val trending = TmdbClient.trendingShows(apiKey).filter { it.posterPath != null }
                            if (trending.isNotEmpty()) trending else TmdbClient.popularShows(apiKey).filter { it.posterPath != null }
                        }

                        val tasteProfile = all.filter { it.rating >= 4 || favorites.contains(it.id.toString()) }
                        fun personalized(items: List<TmdbResult>, mediaType: String): List<TmdbResult> {
                            if (homeOrder == "Descobertas primeiro" || tasteProfile.isEmpty()) return items.take(20)
                            val profile = tasteProfile.filter { it.mediaType == mediaType }
                            if (profile.isEmpty()) return items.take(20)
                            val tokens = profile.flatMap { (it.title + " " + it.overview + " " + it.genres).lowercase().split(Regex("[^\\p{L}\\p{N}]+")) }
                                .filter { it.length >= 4 }.groupingBy { it }.eachCount()
                            return items.sortedByDescending { candidate ->
                                val text = (candidate.title + " " + candidate.overview).lowercase()
                                tokens.entries.sumOf { (word, weight) -> if (text.contains(word)) weight * 2 else 0 } +
                                    if (profile.any { it.title.equals(candidate.title, ignoreCase = true) }) -100 else 0
                            }.take(20)
                        }
                        recommendedMovies = personalized(movieResult.getOrDefault(emptyList()), "movie")
                        recommendedShows = personalized(showResult.getOrDefault(emptyList()), "tv")

                        fun encodeCache(items: List<TmdbResult>) = JSONArray().apply {
                            items.forEach { item ->
                                put(JSONObject().put("id", item.id).put("title", item.title).put("type", item.type)
                                    .put("year", item.year).put("posterPath", item.posterPath)
                                     .put("overview", item.overview).put("releaseDate", item.releaseDate).put("tmdbVoteAverage", item.tmdbVoteAverage))
                            }
                        }.toString()
                        if (recommendedMovies.isNotEmpty() || recommendedShows.isNotEmpty()) {
                            prefs.edit()
                                .putString("trending_movies_cache", encodeCache(recommendedMovies))
                                .putString("trending_shows_cache", encodeCache(recommendedShows))
                                .putLong("trending_last_update", now)
                                .putInt("trending_rating_cache_version", 1)
                                .apply()
                        }
                        val errors = listOfNotNull(
                            movieResult.exceptionOrNull()?.message?.let { "Filmes: $it" },
                            showResult.exceptionOrNull()?.message?.let { "Séries: $it" }
                        )
                        recommendationsError = if (recommendedMovies.isEmpty() && recommendedShows.isEmpty())
                            errors.joinToString(" • ").ifBlank { "O TMDB não retornou títulos com pôsteres." }
                        else errors.joinToString(" • ")
                    }
                    recommendationsLoading = false
                } else {
                    recommendedMovies = emptyList()
                    recommendedShows = emptyList()
                    recommendationsError = ""
                    recommendationsLoading = false
                }
            }
            LaunchedEffect(page, exploreQuery, exploreType, apiKey) {
                val searchKey = "${exploreQuery.trim()}|$exploreType|$apiKey"
                if (page == "Explorar" && exploreQuery.trim().isNotEmpty() && exploreSearchLoadedKey != searchKey) {
                    kotlinx.coroutines.delay(450)
                    if (apiKey.isBlank()) {
                        exploreError = "Configure sua credencial TMDB em Mais > Config."
                        exploreResults = emptyList()
                    } else {
                        exploreLoading = true
                        exploreError = ""
                        runCatching { TmdbClient.search(exploreQuery.trim(), apiKey).filter { it.type == exploreType && it.posterPath != null && yearMatches(it.year, exploreYearPeriod) } }
                            .onSuccess { items -> exploreResults = items; exploreCanLoadMore = false; explorePage = 1; exploreSearchLoadedKey = searchKey }
                            .onFailure { exploreError = it.message ?: "Não foi possível pesquisar os títulos." }
                        exploreLoading = false
                    }
                }
            }
            LaunchedEffect(page, exploreType, exploreCategory, exploreGenre, exploreYearPeriod, apiKey, exploreQuery) {
                val catalogKey = "$exploreType|$exploreCategory|$exploreGenre|$exploreYearPeriod|$apiKey|${exploreQuery.trim()}"
                if (page == "Explorar" && exploreQuery.isBlank() && exploreCategory == "franchises" && exploreFranchisePage == 0 && !exploreFranchisesLoading) {
                    if (apiKey.isBlank()) exploreError = "Configure sua credencial TMDB em Mais > Config."
                    else {
                        exploreFranchisesLoading = true
                        exploreError = ""
                        runCatching { TmdbClient.discoverFranchises(1, apiKey) }
                            .onSuccess { result ->
                                exploreFranchises = result.franchises
                                exploreFranchisePage = result.page
                                exploreFranchisesCanLoadMore = result.page < result.totalPages
                            }
                            .onFailure { exploreError = it.message ?: "Não foi possível carregar as franquias." }
                        exploreFranchisesLoading = false
                    }
                } else if (page == "Explorar" && exploreQuery.isBlank() && exploreCategory != "franchises" && exploreCatalogLoadedKey != catalogKey) {
                    explorePage = 1
                    exploreResults = emptyList()
                    exploreError = ""
                    exploreCanLoadMore = true
                    if (apiKey.isBlank()) exploreError = "Configure sua credencial TMDB em Mais > Config."
                    else {
                        exploreLoading = true
                        val genreId = if (exploreGenre == "Todos") null else exploreGenres(exploreType).firstOrNull { it.first == exploreGenre }?.second
                        runCatching { TmdbClient.catalog(exploreType, exploreCategory, 1, genreId, apiKey, exploreYearPeriod).filter { it.posterPath != null } }
                            .onSuccess { items -> exploreResults = items; exploreCanLoadMore = items.size >= 20; exploreCatalogLoadedKey = catalogKey }
                            .onFailure { exploreError = it.message ?: "Não foi possível carregar o catálogo." }
                        exploreLoading = false
                    }
                }
            }
            val availableYears = all.mapNotNull { it.year.toIntOrNull() }.distinct().sortedDescending()
            val visibleUnsorted = all.filter { media ->
                val categoryMatches = when (categoryFilter) {
                    "FILMES" -> media.mediaType == "movie"
                    "SÉRIES" -> media.mediaType == "tv"
                    else -> true
                }
                val statusMatches = when (filter) {
                    "Quero assistir" -> media.status == "WATCHLIST" || (media.mediaType == "movie" && media.status == "WATCHING")
                    "Tô assistindo" -> media.mediaType == "tv" && media.status == "WATCHING"
                    "Já assisti" -> media.status == "WATCHED"
                    else -> true
                }
                (yearFilter == "Todos" || media.year == yearFilter) && categoryMatches && statusMatches
            }
            val manualVisible = visibleUnsorted.sortedWith(compareBy<MediaItem> { if (it.sortOrder > 0) it.sortOrder else it.id }.thenBy { it.id })
            val visible = when (sortMode) {
                "A–Z" -> visibleUnsorted.sortedBy { it.title.lowercase(java.util.Locale.ROOT) }
                "Ano (mais recente)" -> visibleUnsorted.sortedByDescending { it.year.toIntOrNull() ?: 0 }
                "Inclusão (mais recente)" -> visibleUnsorted.sortedByDescending { it.id }
                "Inclusão (mais antiga)" -> visibleUnsorted.sortedBy { it.id }
                else -> if (draggedPosterId != null && dragOrder.isNotEmpty()) {
                    val byId = manualVisible.associateBy { it.id }
                    dragOrder.mapNotNull { byId[it] } + manualVisible.filter { it.id !in dragOrder }
                } else manualVisible
            }
            // Bounds são escritos por callbacks de layout na thread principal;
            // não precisam integrar o Snapshot observável do Compose.
            val posterBounds = remember { mutableMapOf<Long, Rect>() }
            val latestVisibleIds by rememberUpdatedState(visible.map { it.id }.toSet())
            fun persistManualOrder(orderedIds: List<Long>) {
                if (sortMode != "Manual" || orderedIds.isEmpty()) return
                val orderedVisible = orderedIds.mapNotNull { id -> all.firstOrNull { it.id == id } }
                val visibleIds = orderedVisible.map { it.id }.toSet()
                if (orderedVisible.size != visibleIds.size) return
                val completeOrder = all.sortedWith(compareBy<MediaItem> { if (it.sortOrder > 0) it.sortOrder else it.id }.thenBy { it.id }).toMutableList()
                var nextVisible = 0
                for (index in completeOrder.indices) {
                    if (completeOrder[index].id in visibleIds) {
                        if (nextVisible >= orderedVisible.size) return
                        completeOrder[index] = orderedVisible[nextVisible++]
                    }
                }
                lifecycleScope.launch {
                    completeOrder.forEachIndexed { index, item ->
                        dao.update(item.copy(sortOrder = (index + 1L) * 1000L))
                    }
                }
            }
            MaterialTheme(colorScheme = darkColorScheme(primary = palette.third, background = palette.first, surface = palette.second, onBackground = TextMain, onSurface = TextMain)) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = palette.first) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        Box(
                            Modifier.fillMaxWidth().height(56.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.foundation.Image(
                                painter = painterResource(id = R.drawable.cineverse_logo_horizontal),
                                contentDescription = "CineVerso",
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                modifier = Modifier.width(210.dp).height(52.dp)
                            )
                            Text(
                                when (page) {
                                    "Início" -> "INÍCIO"
                                    "Explorar" -> "EXPLORAR"
                                    "Buscar" -> "BUSCAR"
                                    "Minha lista" -> "MINHA LISTA"
                                    "Detalhes" -> "DETALHES"
                                    "Estatísticas" -> "ESTATÍSTICAS"
                                    "Calendário" -> "CALENDÁRIO"
                                    "Franquias" -> "FRANQUIAS"
                                    "Roleta" -> "ROLETA"
                                    else -> "CONFIGURAÇÕES"
                                },
                                modifier = Modifier.align(Alignment.CenterEnd),
                                color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                            )
                        }
                        when (page) {
                            "Explorar" -> {
                                Column(Modifier.weight(1f)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        FilterChip(selected = exploreType == "movie", onClick = { exploreType = "movie" }, label = { Text("Filmes") })
                                        FilterChip(selected = exploreType == "tv", onClick = { exploreType = "tv" }, label = { Text("Séries") })
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = exploreQuery,
                                        onValueChange = { exploreQuery = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        placeholder = { Text("Buscar filmes ou séries pelo título") },
                                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                        trailingIcon = {
                                            if (exploreQuery.isNotEmpty()) TextButton(onClick = { exploreQuery = "" }) { Text("Limpar") }
                                        },
                                        singleLine = true,
                                        shape = RoundedCornerShape(15.dp)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(if (exploreQuery.isBlank()) "DESCUBRA MAIS" else "RESULTADOS DA BUSCA", color = Purple, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, letterSpacing = 1.sp)
                                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf("Em alta" to "trending", "Populares" to "popular", "Mais bem avaliados" to "top_rated", "Recentes" to "recent", "Em breve" to "upcoming", "Franquias" to "franchises").forEach { (label, value) ->
                                            FilterChip(selected = exploreCategory == value, onClick = { if (value == "franchises" && exploreCategory != "franchises") { previousExploreCategory = exploreCategory; exploreFranchises = emptyList(); exploreFranchisePage = 0; exploreFranchisesCanLoadMore = true }; exploreCategory = value; selectedExploreFranchise = null; exploreCatalogLoadedKey = "" }, label = { Text(label, fontSize = 11.sp) })
                                        }
                                    }
                                    var exploreGenreMenu by remember { mutableStateOf(false) }
                                    var exploreYearMenu by remember { mutableStateOf(false) }
                                    if (exploreCategory != "franchises") {
                                    Box(Modifier.fillMaxWidth()) {
                                        OutlinedButton(onClick = { exploreYearMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("Período: $exploreYearPeriod  ▾") }
                                        DropdownMenu(expanded = exploreYearMenu, onDismissRequest = { exploreYearMenu = false }) {
                                            listOf("Todos", "2020–atual", "2010–2019", "2000–2009", "1990–1999", "1980–1989", "Antes de 1980").forEach { period -> DropdownMenuItem(text = { Text(period) }, onClick = { exploreYearPeriod = period; exploreYearMenu = false; exploreCatalogLoadedKey = ""; exploreSearchLoadedKey = "" }) }
                                        }
                                    }
                                    Box {
                                        OutlinedButton(onClick = { exploreGenreMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("Gênero: $exploreGenre  ▾") }
                                        DropdownMenu(expanded = exploreGenreMenu, onDismissRequest = { exploreGenreMenu = false }) {
                                            exploreGenres(exploreType).forEach { (name, _) -> DropdownMenuItem(text = { Text(name) }, onClick = { exploreGenre = name; exploreGenreMenu = false }) }
                                        }
                                    }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    if (exploreCategory == "franchises") {
                                        if (selectedExploreFranchise == null) {
                                            TextButton(onClick = { exploreCategory = previousExploreCategory; selectedExploreFranchise = null; exploreCatalogLoadedKey = "" }) {
                                                Text("‹ Voltar para Explorar", color = Purple)
                                            }
                                            Text("UNIVERSOS CINEMATOGRÁFICOS", color = Purple, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, letterSpacing = 1.sp)
                                            Text("Explore franquias", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                            Text("Conheça as sagas e descubra todos os filmes de cada universo.", color = Muted, fontSize = 12.sp)
                                            if (exploreFranchisesLoading && exploreFranchises.isEmpty()) {
                                                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) }
                                            } else if (exploreError.isNotBlank() && exploreFranchises.isEmpty()) {
                                                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(exploreError, color = Muted) }
                                            } else {
                                                LazyColumn(state = exploreFranchiseCatalogState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                                                    items(exploreFranchises, key = { "franchise-${it.id}" }) { franchise ->
                                                        Card(onClick = { selectedExploreFranchise = franchise }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                                                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                                AsyncImage(model = franchise.backdropPath?.let { "https://image.tmdb.org/t/p/w342$it" } ?: franchise.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }, contentDescription = franchise.name, modifier = Modifier.width(112.dp).height(76.dp).background(Night, RoundedCornerShape(9.dp)), contentScale = ContentScale.Crop)
                                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                                    Text(franchise.name, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                                                    Text("${franchise.parts.size} filmes", color = Muted, fontSize = 12.sp)
                                                                    Text("Ver franquia  ›", color = Purple, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                                                                }
                                                            }
                                                        }
                                                    }
                                                    item {
                                                        if (exploreFranchisesCanLoadMore) {
                                                            Button(
                                                                onClick = {
                                                                    if (!exploreFranchisesLoading) rouletteScope.launch {
                                                                        exploreFranchisesLoading = true
                                                                        exploreError = ""
                                                                        val nextPage = exploreFranchisePage + 1
                                                                        runCatching { TmdbClient.discoverFranchises(nextPage, apiKey) }
                                                                            .onSuccess { result ->
                                                                                exploreFranchises = (exploreFranchises + result.franchises).distinctBy { it.id }
                                                                                exploreFranchisePage = result.page
                                                                                exploreFranchisesCanLoadMore = result.page < result.totalPages
                                                                            }
                                                                            .onFailure { exploreError = it.message ?: "Não foi possível carregar mais franquias." }
                                                                        exploreFranchisesLoading = false
                                                                    }
                                                                },
                                                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                                                enabled = !exploreFranchisesLoading,
                                                                colors = ButtonDefaults.buttonColors(containerColor = Purple)
                                                            ) {
                                                                if (exploreFranchisesLoading) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                                                else Text("Carregar mais franquias", color = Color.White, fontWeight = FontWeight.Bold)
                                                            }
                                                        } else {
                                                            Text("Você chegou ao fim das páginas disponíveis no TMDB.", color = Muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(12.dp))
                                                        }
                                                        if (exploreError.isNotBlank() && exploreFranchises.isNotEmpty()) Text(exploreError, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                                                    }
                                                }
                                            }
                                        } else {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                TextButton(onClick = { selectedExploreFranchise = null }) { Text("‹ Franquias", color = Purple) }
                                                Text(selectedExploreFranchise!!.name, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                                            }
                                            Text("FILMES EM ORDEM DE LANÇAMENTO", color = Muted, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp)
                                            LazyColumn(state = exploreFranchiseMoviesState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                                                items(selectedExploreFranchise!!.parts.chunked(3), key = { row -> "franchise-films-${row.firstOrNull()?.id ?: 0}" }) { rowItems ->
                                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                                        rowItems.forEach { item ->
                                                            val savedMedia = all.firstOrNull { it.tmdbId == item.id && it.mediaType == "movie" }
                                                            ExploreMediaCard(item, savedMedia, Modifier.weight(1f)) {
                                                                detailItem = item
                                                                detailReturnPage = "Explorar"
                                                                navigateTo("Detalhes")
                                                            }
                                                        }
                                                        repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                    Text(when (exploreCategory) { "trending" -> "Em alta nesta semana"; "popular" -> "Os títulos mais populares"; "top_rated" -> "Os mais bem avaliados"; "recent" -> if (exploreType == "movie") "Em cartaz" else "Exibidos hoje"; else -> if (exploreType == "movie") "Próximos lançamentos" else "No ar" }, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    if (exploreLoading && exploreResults.isEmpty()) {
                                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) }
                                    } else if (exploreError.isNotBlank() && exploreResults.isEmpty()) {
                                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(exploreError, color = Muted) }
                                    } else {
                                        LazyColumn(state = exploreListState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                                            items(exploreResults.chunked(3), key = { row -> "explore-" + (row.firstOrNull()?.id ?: 0) }) { rowItems ->
                                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                                    rowItems.forEach { item ->
                                                        val savedMedia = all.firstOrNull { it.tmdbId == item.id && it.mediaType == item.type }
                                                        ExploreMediaCard(item, savedMedia, Modifier.weight(1f)) {
                                                            detailItem = item
                                                            detailReturnPage = "Explorar"
                                                            navigateTo("Detalhes")
                                                            if (item.type == "tv" && apiKey.isNotBlank()) lifecycleScope.launch {
                                                                detailLoading = true
                                                                val imported = runCatching { TmdbClient.seriesSeasons(item.id, apiKey) }.getOrDefault("[]")
                                                                val savedProgress = dao.findByTmdbId(item.id, "tv")?.seasonProgress.orEmpty()
                                                                detailSeasons = mergeSavedSeasonProgress(imported, savedProgress)
                                                                detailLoading = false
                                                            }
                                                        }
                                                    }
                                                    repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                                                }
                                            }
                                            if (exploreCanLoadMore) item {
                                                OutlinedButton(onClick = { if (!exploreLoading) lifecycleScope.launch { exploreLoading = true; val nextPage = explorePage + 1; val genreId = if (exploreGenre == "Todos") null else exploreGenres(exploreType).firstOrNull { it.first == exploreGenre }?.second; runCatching { TmdbClient.catalog(exploreType, exploreCategory, nextPage, genreId, apiKey, exploreYearPeriod).filter { it.posterPath != null } }.onSuccess { items -> val filteredItems = items.filter { yearMatches(it.year, exploreYearPeriod) }; val newItems = filteredItems.filter { candidate -> exploreResults.none { it.id == candidate.id && it.type == candidate.type } }; exploreResults = exploreResults + newItems; explorePage = nextPage; exploreCanLoadMore = items.size >= 20 }.onFailure { exploreError = it.message ?: "Falha ao carregar mais títulos." }; exploreLoading = false } }, modifier = Modifier.fillMaxWidth(), enabled = !exploreLoading) { if (exploreLoading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Carregar mais títulos") }
                                            }
                                            if (exploreError.isNotBlank()) item { Text(exploreError, color = Color(0xFFFF8A8A), fontSize = 11.sp) }
                                        }
                                    }
                                    }
                                }
                            }
                            "Buscar" -> {
                                OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Nome do filme ou série") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(15.dp))
                                Spacer(Modifier.height(8.dp))
                                var searchPeriodMenu by remember { mutableStateOf(false) }
                                Box(Modifier.fillMaxWidth()) {
                                    OutlinedButton(onClick = { searchPeriodMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("Período: $searchYearPeriod  ▾") }
                                    DropdownMenu(expanded = searchPeriodMenu, onDismissRequest = { searchPeriodMenu = false }) {
                                        listOf("Todos", "2020–atual", "2010–2019", "2000–2009", "1990–1999", "1980–1989", "Antes de 1980").forEach { period -> DropdownMenuItem(text = { Text(period) }, onClick = { searchYearPeriod = period; searchPeriodMenu = false }) }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = {
                                    if (apiKey.isBlank()) showKey = true
                                    else { busy = true; lifecycleScope.launch {
                                        try { results = TmdbClient.search(query.trim(), apiKey); message = if (results.isEmpty()) "Nenhum título encontrado." else "${results.size} resultados encontrados." }
                                        catch (e: Exception) { message = e.message ?: "Falha na pesquisa." }
                                        finally { busy = false }
                                    } }
                                }, enabled = query.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) {
                                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Search, null)
                                    Spacer(Modifier.width(8.dp)); Text(if (busy) "Pesquisando..." else "Pesquisar no TMDB")
                                }
                                Text(message, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                                LazyColumn(state = searchResultsState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                                    items(results.filter { yearMatches(it.year, searchYearPeriod) }, key = { "${it.type}-${it.id}" }) { item ->
                                        val saved = all.any { it.tmdbId == item.id && it.mediaType == item.type }
                                        SearchCard(item, onOpen = {
                                            detailItem = item
                                            navigateTo("Detalhes")
                                            if (item.type == "tv" && apiKey.isNotBlank()) lifecycleScope.launch {
                                                detailLoading = true
                                                val imported = runCatching { TmdbClient.seriesSeasons(item.id, apiKey) }.getOrDefault("[]")
                                                val savedProgress = dao.findByTmdbId(item.id, "tv")?.seasonProgress.orEmpty()
                                                detailSeasons = mergeSavedSeasonProgress(imported, savedProgress)
                                                detailLoading = false
                                            }
                                        })
                                    }
                                }
                            }
                            "Detalhes" -> {
                                val selectedTitle = detailItem
                                if (selectedTitle != null) {
                                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                                        item {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                TextButton(onClick = { returnFromDetails() }) { Text("← Voltar") }
                                                Text("DETALHES", color = Purple, fontWeight = FontWeight.Bold)
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                                                if (!selectedTitle.posterPath.isNullOrBlank()) {
                                                    Box(
                                                        modifier = Modifier
                                                            .width(120.dp)
                                                            .height(178.dp)
                                                            .background(Panel, RoundedCornerShape(12.dp))
                                                            .padding(6.dp),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        AsyncImage(
                                                            model = "https://image.tmdb.org/t/p/w500${selectedTitle.posterPath}",
                                                            contentDescription = "Pôster completo de ${selectedTitle.title}",
                                                            modifier = Modifier.fillMaxSize(),
                                                            contentScale = ContentScale.Fit
                                                        )
                                                    }
                                                }
                                                Column(Modifier.weight(1f)) {
                                                    Text(selectedTitle.title, color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                                    Text("${if (selectedTitle.type == "tv") "Série" else "Filme"} • ${selectedTitle.year}", color = Muted)
                                                    if (selectedTitle.type == "movie") {
                                                        val minutes = detailRuntimeMinutes
                                                        val durationLabel = when {
                                                            detailRuntimeLoading -> "Duração: carregando..."
                                                            minutes == null -> "Duração: indisponível"
                                                            minutes >= 60 && minutes % 60 > 0 -> "Duração: ${minutes / 60}h${minutes % 60}"
                                                            minutes >= 60 -> "Duração: ${minutes / 60}h"
                                                            else -> "Duração: ${minutes}min"
                                                        }
                                                        Text(durationLabel, color = Muted, fontSize = 12.sp)
                                                    }
                                                    Text(tmdbRatingLabel(selectedTitle.tmdbVoteAverage), color = Color(0xFFFFD166), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                    Spacer(Modifier.height(8.dp))
                                                    Text(selectedTitle.overview.ifBlank { "Sinopse indisponível." }, color = Muted, fontSize = 13.sp)
                                                }
                                            }
                                            Spacer(Modifier.height(12.dp))
                                            Text("ELENCO PRINCIPAL", color = Purple, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            when {
                                                detailCastLoading -> CircularProgressIndicator(color = Purple, modifier = Modifier.size(22.dp))
                                                detailCast.isEmpty() -> Text("Elenco indisponível para este título.", color = Muted, fontSize = 12.sp)
                                                else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                    items(detailCast, key = { it.id }) { member ->
                                                        Column(Modifier.width(104.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                            AsyncImage(model = member.profilePath?.let { "https://image.tmdb.org/t/p/w185$it" }, contentDescription = member.name, modifier = Modifier.fillMaxWidth().height(132.dp).background(Panel, RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                                                            Text(member.name, color = TextMain, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                                                            Text(member.character.ifBlank { "Personagem não informado" }, color = Muted, fontSize = 10.sp, maxLines = 2)
                                                        }
                                                    }
                                                }
                                            }
                                            Spacer(Modifier.height(12.dp))
                                            OutlinedButton(
                                                onClick = {
                                                    if (!sharingRecommendation) {
                                                        sharingRecommendation = true
                                                        lifecycleScope.launch {
                                                            try {
                                                                val imageUri = createSimpleCineVersoShareCard(this@MainActivity, selectedTitle, apiKey)
                                                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                                    type = "image/png"
                                                                    putExtra(Intent.EXTRA_STREAM, imageUri)
                                                                    clipData = android.content.ClipData.newUri(contentResolver, "Recomendação CineVerso", imageUri)
                                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                                }
                                                                startActivity(Intent.createChooser(shareIntent, "Compartilhar recomendação"))
                                                            } catch (error: Exception) {
                                                                Toast.makeText(this@MainActivity, "Falha ao gerar a indicação: ${error.message ?: "verifique sua conexão"}", Toast.LENGTH_LONG).show()
                                                            } finally {
                                                                sharingRecommendation = false
                                                            }
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                enabled = !sharingRecommendation,
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC29AFF))
                                            ) {
                                                if (sharingRecommendation) {
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFFC29AFF))
                                                        Spacer(Modifier.width(10.dp))
                                                        Text("GERANDO CARD…", fontWeight = FontWeight.Bold)
                                                    }
                                                } else Text("RECOMENDAR", fontWeight = FontWeight.Bold)
                                            }
                                            Text("Card automático com o pôster, título, nota TMDB e sinopse. Não exige escolher ou salvar imagens.", color = Muted, fontSize = 11.sp)
                                            Spacer(Modifier.height(4.dp))
                                            Text("TRAILER", color = Purple, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            when {
                                                detailTrailerLoading -> Box(
                                                    Modifier.fillMaxWidth().height(190.dp),
                                                    contentAlignment = Alignment.Center
                                                ) { CircularProgressIndicator(color = Purple) }
                                                detailTrailer != null -> {
                                                    val trailer = detailTrailer!!
                                                    Box(
                                                        Modifier.fillMaxWidth().height(190.dp)
                                                            .background(Panel, RoundedCornerShape(14.dp))
                                                            .clickable {
                                                                val youtubeUrl = "https://www.youtube.com/watch?v=${trailer.key}"
                                                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(youtubeUrl)))
                                                            },
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        AsyncImage(
                                                            model = "https://img.youtube.com/vi/${trailer.key}/hqdefault.jpg",
                                                            contentDescription = "Miniatura do trailer de ${selectedTitle.title}",
                                                            modifier = Modifier.fillMaxSize(),
                                                            contentScale = ContentScale.Crop
                                                        )
                                                        Text(
                                                            "▶",
                                                            color = Color.White,
                                                            fontSize = 34.sp,
                                                            fontWeight = FontWeight.ExtraBold,
                                                            modifier = Modifier.background(Color(0xCCB00020), RoundedCornerShape(50)).padding(horizontal = 22.dp, vertical = 10.dp)
                                                        )
                                                        Text(
                                                            "ASSISTIR NO YOUTUBE",
                                                            color = Color.White,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                                                .background(Color(0xCC08070D)).padding(9.dp)
                                                        )
                                                    }
                                                    Text(trailer.name, color = Muted, fontSize = 11.sp, maxLines = 2)
                                                }
                                                else -> Text("Trailer não encontrado no TMDB para este título.", color = Muted, fontSize = 12.sp)
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            val savedTitle = all.firstOrNull { it.tmdbId == selectedTitle.id && it.mediaType == selectedTitle.type }
                                            if (savedTitle != null) {
                                                val statusOptions = if (selectedTitle.type == "tv") {
                                                    listOf("WATCHLIST" to "Quero assistir", "WATCHING" to "Tô assistindo", "WATCHED" to "Já assisti")
                                                } else {
                                                    listOf("WATCHLIST" to "Quero assistir", "WATCHED" to "Já assisti")
                                                }
                                                val currentStatusLabel = statusOptions.firstOrNull { it.first == savedTitle.status }?.second ?: "Na minha lista"
                                                Text("NA MINHA LISTA • STATUS ATUAL: $currentStatusLabel", color = Purple, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                statusOptions.forEach { (newStatus, label) ->
                                                    Button(
                                                        onClick = {
                                                            lifecycleScope.launch {
                                                                val existing = dao.findByTmdbId(selectedTitle.id, selectedTitle.type)
                                                                var media = existing ?: selectedTitle.toMediaItem()
                                                                if (selectedTitle.type == "tv" && newStatus == "WATCHED") {
                                                                    if (media.seasonProgress.isBlank() && apiKey.isNotBlank()) {
                                                                        val imported = runCatching { TmdbClient.seriesSeasons(selectedTitle.id, apiKey) }.getOrDefault("[]")
                                                                        val seasons = JSONArray(imported)
                                                                        var episodes = 0
                                                                        for (i in 0 until seasons.length()) episodes += seasons.optJSONObject(i)?.optInt("episodes", 0) ?: 0
                                                                        media = media.copy(totalSeasons = (0 until seasons.length()).count { seasons.optJSONObject(it)?.optInt("number", 0) ?: 0 > 0 }, totalEpisodes = episodes, seasonProgress = imported)
                                                                    }
                                                                    val completedProgress = markAllSeriesEpisodesWatched(media.seasonProgress.ifBlank { detailSeasons })
                                                                    val completedSeasons = JSONArray(completedProgress)
                                                                    val seasonCount = (0 until completedSeasons.length()).count { (completedSeasons.optJSONObject(it)?.optInt("number") ?: 0) > 0 }
                                                                    val episodeCount = (0 until completedSeasons.length()).sumOf { completedSeasons.optJSONObject(it)?.optJSONArray("episodeList")?.length() ?: 0 }
                                                                    media = media.copy(
                                                                        totalSeasons = seasonCount,
                                                                        totalEpisodes = episodeCount,
                                                                        watchedEpisodes = episodeCount,
                                                                        seasonProgress = completedProgress
                                                                        )
                                                                        detailSeasons = completedProgress
                                                                }
                                                                dao.insert(media.copy(status = newStatus, watchedAt = if (newStatus == "WATCHED") System.currentTimeMillis() else media.watchedAt))
                                                            }
                                                        },
                                                        modifier = Modifier.fillMaxWidth(),
                                                        colors = ButtonDefaults.buttonColors(
                                                            containerColor = if (savedTitle.status == newStatus) Color(0xFF35105F) else Color(0xFF2A2040),
                                                            contentColor = Color.White
                                                        )
                                                    ) {
                                                        Text(if (savedTitle.status == newStatus) "$label • ATUAL" else label, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                                Button(
                                                    onClick = {
                                                        lifecycleScope.launch {
                                                            val existing = dao.findByTmdbId(selectedTitle.id, selectedTitle.type)
                                                            if (existing != null) dao.delete(existing)
                                                        }
                                                    },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2040), contentColor = Color(0xFFC29AFF))
                                                ) { Text("REMOVER DA MINHA LISTA", color = Color.White, fontWeight = FontWeight.Bold) }
                                            } else {
                                                Text("ADICIONAR À MINHA LISTA", color = Purple, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                (if (selectedTitle.type == "tv") listOf("WATCHLIST" to "Quero assistir", "WATCHING" to "Tô assistindo", "WATCHED" to "Já assisti") else listOf("WATCHLIST" to "Quero assistir", "WATCHED" to "Já assisti")).forEach { (status, label) ->
                                                    Button(onClick = {
                                                        lifecycleScope.launch {
                                                            var media = selectedTitle.toMediaItem()
                                                            if (selectedTitle.type == "tv" && media.seasonProgress.isBlank() && apiKey.isNotBlank()) {
                                                                val imported = runCatching { TmdbClient.seriesSeasons(selectedTitle.id, apiKey) }.getOrDefault("[]")
                                                                val seasons = JSONArray(imported)
                                                                var episodes = 0
                                                                for (i in 0 until seasons.length()) episodes += seasons.optJSONObject(i)?.optInt("episodes", 0) ?: 0
                                                                media = media.copy(totalSeasons = (0 until seasons.length()).count { seasons.optJSONObject(it)?.optInt("number", 0) ?: 0 > 0 }, totalEpisodes = episodes, seasonProgress = imported)
                                                            }
                                                            if (selectedTitle.type == "tv" && status == "WATCHED") {
                                                                val completedProgress = markAllSeriesEpisodesWatched(media.seasonProgress.ifBlank { detailSeasons })
                                                                val completedSeasons = JSONArray(completedProgress)
                                                                val seasonCount = (0 until completedSeasons.length()).count { (completedSeasons.optJSONObject(it)?.optInt("number") ?: 0) > 0 }
                                                                val episodeCount = (0 until completedSeasons.length()).sumOf { completedSeasons.optJSONObject(it)?.optJSONArray("episodeList")?.length() ?: 0 }
                                                                media = media.copy(
                                                                    totalSeasons = seasonCount,
                                                                    totalEpisodes = episodeCount,
                                                                    watchedEpisodes = episodeCount,
                                                                    seasonProgress = completedProgress
                                                                    )
                                                                    detailSeasons = completedProgress
                                                            }
                                                            dao.insert(media.copy(status = status, watchedAt = if (status == "WATCHED") System.currentTimeMillis() else media.watchedAt))
                                                            // Mantém a tela de detalhes aberta após salvar o status; o usuário volta manualmente.
                                                        }
                                                    }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                                                }
                                            }
                                            if (savedTitle != null) {
                                                Divider(color = Muted.copy(alpha = .25f))
                                                Text("SUA NOTA", color = Purple, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                Text(if (personalRatingInput == 0) "Ainda não avaliado" else "$personalRatingInput/10", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                                                Slider(value = personalRatingInput.toFloat(), onValueChange = { personalRatingInput = it.toInt().coerceIn(0, 10) }, valueRange = 0f..10f, steps = 9, onValueChangeFinished = {
                                                    lifecycleScope.launch { dao.update(savedTitle.copy(personalRating = personalRatingInput)) }
                                                })
                                                Text("Sua avaliação é privada e independente da nota TMDB.", color = Muted, fontSize = 10.sp)
                                            }
                                            if (selectedTitle.type == "tv") {
                                                Spacer(Modifier.height(8.dp))
                                                Text("TEMPORADAS", color = Purple, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                if (detailLoading) CircularProgressIndicator()
                                                else {
                                                    val seasons = JSONArray(detailSeasons)
                                                    if (seasons.length() == 0) Text("Dados de temporadas indisponíveis. Confira a conexão e a credencial TMDB.", color = Muted, fontSize = 12.sp)
                                                    for (i in 0 until seasons.length()) {
                                                        val season = seasons.optJSONObject(i) ?: continue
                                                        if (season.optInt("number") == 0) continue
                                                        val seasonNumber = season.optInt("number")
                                                        var seasonMenu by remember(seasonNumber) { mutableStateOf(false) }
                                                        var seasonExpanded by remember(seasonNumber) { mutableStateOf(false) }
                                                        val seasonStatus = season.optString("status", "WATCHLIST")
                                                        val episodeList = season.optJSONArray("episodeList") ?: JSONArray()
                                                        val watchedInSeason = (0 until episodeList.length()).count { episodeList.optJSONObject(it)?.optBoolean("watched", false) == true }
                                                        val seasonLabel = when (seasonStatus) { "WATCHED" -> "JÁ ASSISTIDA"; "WATCHING" -> "EM ANDAMENTO"; else -> "QUERO ASSISTIR" }
                                                        val seasonAccent = Color.White
                                                        val seasonBackground = when (seasonStatus) { "WATCHING" -> Color(0xFF35105F); "WATCHED" -> Color(0xFF24143A); else -> Panel }
                                                        Column(Modifier.fillMaxWidth().background(seasonBackground, RoundedCornerShape(12.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                Column(Modifier.weight(1f).clickable { seasonExpanded = !seasonExpanded }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                                    Text(season.optString("name"), color = TextMain, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                                                    Text("${watchedInSeason}/${episodeList.length()} episódios • ${season.optString("airDate")}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                                    Text(seasonLabel, color = seasonAccent, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.7.sp)
                                                                }
                                                                TextButton(onClick = { seasonExpanded = !seasonExpanded }) { Text(if (seasonExpanded) "Recolher" else "Episódios", color = Color.White, fontWeight = FontWeight.Bold) }
                                                                Box {
                                                                    TextButton(onClick = { seasonMenu = true }) { Text("Status", color = Color.White, fontWeight = FontWeight.Bold) }
                                                                    DropdownMenu(expanded = seasonMenu, onDismissRequest = { seasonMenu = false }) {
                                                                        listOf("WATCHLIST" to "Quero assistir", "WATCHING" to "Tô assistindo", "WATCHED" to "Já assisti").forEach { (newStatus, label) ->
                                                                            DropdownMenuItem(text = { Text(label, color = Color.White, fontWeight = FontWeight.Bold) }, onClick = {
                                                                                if (newStatus == "WATCHED") {
                                                                                    for (j in 0 until episodeList.length()) episodeList.optJSONObject(j)?.put("watched", true)
                                                                                }
                                                                                season.put("status", newStatus)
                                                                                season.put("episodeList", episodeList)
                                                                                detailSeasons = seasons.toString()
                                                                                seasonMenu = false
                                                                                lifecycleScope.launch {
                                                                                    val existing = dao.findByTmdbId(selectedTitle.id, selectedTitle.type)
                                                                                    val current = existing ?: selectedTitle.toMediaItem()
                                                                                    val imported = JSONArray(detailSeasons)
                                                                                    val count = (0 until imported.length()).count { (imported.optJSONObject(it)?.optInt("number") ?: 0) > 0 }
                                                                                    val total = (0 until imported.length()).sumOf { imported.optJSONObject(it)?.optJSONArray("episodeList")?.length() ?: 0 }
                                                                                    val watched = (0 until imported.length()).sumOf { s -> val eps = imported.optJSONObject(s)?.optJSONArray("episodeList"); if (eps == null) 0 else (0 until eps.length()).count { eps.optJSONObject(it)?.optBoolean("watched", false) == true } }
                                                                                    dao.insert(current.copy(totalSeasons = count, totalEpisodes = total, watchedEpisodes = watched, seasonProgress = detailSeasons))
                                                                                }
                                                                            })
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                            if (seasonExpanded) {
                                                                if (episodeList.length() == 0) Text("Lista de episódios indisponível.", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                                for (j in 0 until episodeList.length()) {
                                                                    val episode = episodeList.optJSONObject(j) ?: continue
                                                                    val watched = episode.optBoolean("watched", false)
                                                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                                        Checkbox(checked = watched, onCheckedChange = { checked ->
                                                                            episode.put("watched", checked)
                                                                            val allWatched = episodeList.length() > 0 && (0 until episodeList.length()).all { episodeList.optJSONObject(it)?.optBoolean("watched", false) == true }
                                                                            season.put("episodeList", episodeList)
                                                                            season.put("status", if (allWatched) "WATCHED" else if (checked || watchedInSeason > 0) "WATCHING" else "WATCHLIST")
                                                                            detailSeasons = seasons.toString()
                                                                            lifecycleScope.launch {
                                                                                val existing = dao.findByTmdbId(selectedTitle.id, selectedTitle.type)
                                                                                val current = existing ?: selectedTitle.toMediaItem()
                                                                                val imported = JSONArray(detailSeasons)
                                                                                val count = (0 until imported.length()).count { (imported.optJSONObject(it)?.optInt("number") ?: 0) > 0 }
                                                                                val total = (0 until imported.length()).sumOf { imported.optJSONObject(it)?.optJSONArray("episodeList")?.length() ?: 0 }
                                                                                val totalWatched = (0 until imported.length()).sumOf { s -> val eps = imported.optJSONObject(s)?.optJSONArray("episodeList"); if (eps == null) 0 else (0 until eps.length()).count { eps.optJSONObject(it)?.optBoolean("watched", false) == true } }
                                                                                dao.insert(current.copy(totalSeasons = count, totalEpisodes = total, watchedEpisodes = totalWatched, seasonProgress = detailSeasons))
                                                                            }
                                                                        })
                                                                        Column(Modifier.weight(1f)) {
                                                                            Text("E${episode.optInt("number")} • ${episode.optString("name")}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                                            if (episode.optString("airDate").isNotBlank()) Text(episode.optString("airDate"), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            "Minha lista" -> {
                                if (filter == "Franquias") {
                                    val franchiseGroups = all
                                        .filter { it.mediaType == "movie" && it.franchiseId != null && it.franchiseId!! > 0 }
                                        .groupBy { it.franchiseId!! }
                                        .toList()
                                    val today = java.time.LocalDate.now().toString()
                                    val franchiseRows = franchiseGroups.mapNotNull { group ->
                                        val savedMovies = group.second.sortedWith(
                                            compareBy<MediaItem> { it.year.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.title }
                                        )
                                        val franchiseData = myListFranchiseCollections.firstOrNull { it.id == group.first }
                                        val allParts = franchiseData?.parts ?: savedMovies.map { media ->
                                            TmdbResult(media.tmdbId ?: 0, media.title, media.mediaType, media.year, media.posterPath, media.overview, media.releaseDate, media.tmdbVoteAverage)
                                        }
                                        val releasedParts = allParts.filter { part ->
                                            val releaseDate = part.releaseDate.ifBlank {
                                                part.year.takeIf { it.length >= 4 }?.let { "$it-12-31" }.orEmpty()
                                            }
                                            releaseDate.isBlank() || releaseDate <= today
                                        }.distinctBy { it.id }
                                        if (releasedParts.isEmpty()) null else {
                                            val completed = franchiseData != null && releasedParts.all { part ->
                                                all.firstOrNull { it.tmdbId == part.id && it.mediaType == "movie" }?.status == "WATCHED"
                                            }
                                            Triple(group, releasedParts, completed)
                                        }
                                    }.sortedBy { row ->
                                        row.first.second.firstOrNull()?.franchiseName?.lowercase().orEmpty()
                                    }
                                    val visibleRows = franchiseRows.filter { row ->
                                        val ignored = ignoredFranchiseIds.contains(row.first.first.toString())
                                        when (franchiseView) {
                                            "Concluídas" -> row.third && !ignored
                                            "Ignoradas" -> ignored
                                            else -> !row.third && !ignored
                                        }
                                    }
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(onClick = { filter = lastNormalListFilter }) { Text("← Voltar para minha lista", color = Purple) }
                                    }
                                    Text("FRANQUIAS DA MINHA LISTA", color = Purple, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                                        modifier = Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 3.dp)
                                    ) {
                                        items(listOf("Em andamento", "Concluídas", "Ignoradas")) { option ->
                                            FilterChip(
                                                selected = franchiseView == option,
                                                onClick = { franchiseView = option },
                                                label = { Text(option, fontSize = 10.sp) }
                                            )
                                        }
                                    }
                                    Text(
                                        when (franchiseView) {
                                            "Concluídas" -> "Franquias com todos os filmes lançados já assistidos."
                                            "Ignoradas" -> "Franquias ocultadas por você. É possível reativá-las."
                                            else -> "Somente filmes já lançados; próximos lançamentos entram automaticamente na estreia."
                                        },
                                        color = Muted,
                                        fontSize = 10.sp
                                    )
                                    if (visibleRows.isEmpty()) {
                                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                            Text(
                                                when {
                                                    apiKey.isBlank() -> "Configure o TMDB para identificar as franquias."
                                                    franchiseView == "Concluídas" -> "Nenhuma franquia concluída ainda."
                                                    franchiseView == "Ignoradas" -> "Você não tem franquias ignoradas."
                                                    else -> "Não há franquias em andamento. As concluídas ficam na aba Concluídas."
                                                },
                                                color = Muted,
                                                fontSize = 12.sp
                                            )
                                        }
                                    } else {
                                        LazyColumn(
                                            state = myListFranchiseState,
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(12.dp),
                                            contentPadding = PaddingValues(vertical = 12.dp)
                                        ) {
                                            items(visibleRows, key = { it.first.first }) { row ->
                                                val group = row.first
                                                val franchiseParts = row.second
                                                val completed = row.third
                                                val franchiseMovies = group.second
                                                val franchiseData = myListFranchiseCollections.firstOrNull { it.id == group.first }
                                                val ignored = ignoredFranchiseIds.contains(group.first.toString())
                                                Column(
                                                    Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(12.dp),
                                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(
                                                            franchiseData?.name?.takeIf { it.isNotBlank() }
                                                                ?: franchiseMovies.firstOrNull()?.franchiseName?.takeIf { it.isNotBlank() }
                                                                ?: "Franquia",
                                                            color = TextMain,
                                                            fontSize = 16.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                        TextButton(
                                                            onClick = { setFranchiseIgnored(group.first, !ignored) },
                                                            contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)
                                                        ) {
                                                            Text(if (ignored) "Reativar" else "Ignorar", color = Purple, fontSize = 10.sp)
                                                        }
                                                    }
                                                    Text(
                                                        "${franchiseParts.size} filmes lançados • ordem de lançamento",
                                                        color = Purple,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                        items(franchiseParts, key = { it.id }) { part ->
                                                            val saved = all.firstOrNull { it.tmdbId == part.id && it.mediaType == "movie" }
                                                            Column(Modifier.width(104.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                                Box(
                                                                    Modifier.fillMaxWidth().height(148.dp)
                                                                        .background(if (completed) Color(0xFF302044) else Night, RoundedCornerShape(8.dp))
                                                                        .clickable {
                                                                            detailItem = part
                                                                            detailReturnPage = "Minha lista"
                                                                            detailSeasons = saved?.seasonProgress?.ifBlank { "[]" } ?: "[]"
                                                                            navigateTo("Detalhes")
                                                                        },
                                                                    contentAlignment = Alignment.TopEnd
                                                                ) {
                                                                    AsyncImage(
                                                                        model = part.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" },
                                                                        contentDescription = part.title,
                                                                        modifier = Modifier.fillMaxSize().background(if (completed) Color(0xFF302044) else Night, RoundedCornerShape(8.dp)),
                                                                        contentScale = ContentScale.Crop
                                                                    )
                                                                    if (completed) {
                                                                        Text(
                                                                            "CONCLUÍDO",
                                                                            color = Color.White,
                                                                            fontSize = 7.sp,
                                                                            fontWeight = FontWeight.ExtraBold,
                                                                            modifier = Modifier.padding(4.dp)
                                                                                .background(Color(0xFF713BA5), RoundedCornerShape(5.dp))
                                                                                .padding(horizontal = 5.dp, vertical = 3.dp)
                                                                        )
                                                                    }
                                                                }
                                                                Text(part.title, color = TextMain, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 2, minLines = 2)
                                                                Text(part.year.ifBlank { "Ano indisponível" }, color = Muted, fontSize = 9.sp)
                                                                if (saved == null) {
                                                                    OutlinedButton(
                                                                        onClick = {
                                                                            lifecycleScope.launch {
                                                                                if (dao.findByTmdbId(part.id, "movie") == null) {
                                                                                    dao.insert(MediaItem(
                                                                                        tmdbId = part.id, title = part.title, mediaType = "movie",
                                                                                        year = part.year, posterPath = part.posterPath, overview = part.overview,
                                                                                        status = "WATCHLIST", releaseDate = part.releaseDate,
                                                                                        tmdbVoteAverage = part.tmdbVoteAverage, franchiseId = group.first,
                                                                                        franchiseName = franchiseData?.name ?: franchiseMovies.firstOrNull()?.franchiseName.orEmpty()
                                                                                    ))
                                                                                }
                                                                            }
                                                                        },
                                                                        modifier = Modifier.fillMaxWidth().height(34.dp),
                                                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                                                    ) { Text("+ Adicionar", fontSize = 9.sp, maxLines = 1) }
                                                                } else {
                                                                    Text(
                                                                        when (saved.status) {
                                                                            "WATCHED" -> "JÁ ASSISTI"
                                                                            "WATCHING" -> "TÔ ASSISTINDO"
                                                                            else -> "QUERO ASSISTIR"
                                                                        },
                                                                        color = if (completed) Color(0xFFD8B5FF) else Purple,
                                                                        fontSize = 8.sp,
                                                                        fontWeight = FontWeight.ExtraBold,
                                                                        maxLines = 1
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                    items(listOf("Quero assistir", "Tô assistindo", "Já assisti", "Franquias")) { statusOption ->
                                        FilterChip(
                                            selected = filter == statusOption,
                                            onClick = { if (statusOption != "Franquias") lastNormalListFilter = statusOption; filter = statusOption },
                                            label = { Text(statusOption, fontSize = 11.sp) }
                                        )
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                    Text("Categoria:", color = Muted, fontSize = 12.sp)
                                    listOf("FILMES", "SÉRIES").forEach { item -> FilterChip(selected = categoryFilter == item, onClick = { categoryFilter = item; yearFilter = "Todos" }, label = { Text(item, fontSize = 11.sp) }) }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    var yearMenu by remember { mutableStateOf(false) }
                                    var sortMenu by remember { mutableStateOf(false) }
                                    Box {
                                        OutlinedButton(onClick = { yearMenu = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("Ano: $yearFilter", fontSize = 11.sp) }
                                        DropdownMenu(expanded = yearMenu, onDismissRequest = { yearMenu = false }) {
                                            (listOf("Todos") + availableYears.map { it.toString() }).forEach { year ->
                                                DropdownMenuItem(text = { Text(year) }, onClick = { yearFilter = year; yearMenu = false })
                                            }
                                        }
                                    }
                                    Box(Modifier.weight(1f)) {
                                        OutlinedButton(onClick = { sortMenu = true }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) { Text("Ordenar: $sortMode", fontSize = 11.sp, maxLines = 1) }
                                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                            listOf("Manual", "A–Z", "Ano (mais recente)", "Inclusão (mais recente)", "Inclusão (mais antiga)").forEach { mode ->
                                                DropdownMenuItem(text = { Text(mode) }, onClick = { sortMode = mode; sortMenu = false })
                                            }
                                        }
                                    }
                                }
                                Text(
                                    "${visible.size} títulos • Pressione e segure um pôster para reorganizar.",
                                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp)
                                )
                                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
                                    state = myListGridState,
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    contentPadding = PaddingValues(bottom = 16.dp)
                                ) {
                                    items(visible, key = { it.id }) { media ->
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .onGloballyPositioned { coordinates -> posterBounds[media.id] = coordinates.boundsInRoot() }
                                                .graphicsLayer {
                                                    if (draggedPosterId == media.id) {
                                                        val center = posterBounds[media.id]?.center ?: Offset.Zero
                                                        translationX = dragPointer.x - center.x
                                                        translationY = dragPointer.y - center.y
                                                        shadowElevation = 18f
                                                    }
                                                }
                                                .pointerInput(media.id, sortMode) {
                                                    if (sortMode == "Manual") {
                                                        var lastTargetId: Long? = null
                                                        detectDragGesturesAfterLongPress(
                                                            onDragStart = {
                                                                draggedPosterId = media.id
                                                                dragOrder = visible.map { it.id }
                                                                dragPointer = posterBounds[media.id]?.center ?: it
                                                                lastTargetId = null
                                                            },
                                                            onDragEnd = {
                                                                persistManualOrder(latestDragOrder)
                                                                draggedPosterId = null
                                                                dragOrder = emptyList()
                                                                lastTargetId = null
                                                            },
                                                            onDragCancel = {
                                                                draggedPosterId = null
                                                                dragOrder = emptyList()
                                                                lastTargetId = null
                                                            },
                                                            onDrag = { change, amount ->
                                                                change.consume()
                                                                dragPointer += amount
                                                                val targetId = posterBounds.entries.firstOrNull { entry ->
                                                                    entry.key != media.id &&
                                                                        entry.key in latestVisibleIds &&
                                                                        entry.value.contains(dragPointer)
                                                                }?.key
                                                                if (targetId == null) {
                                                                    lastTargetId = null
                                                                } else if (targetId != lastTargetId) {
                                                                    lastTargetId = targetId
                                                                    val currentOrder = latestDragOrder
                                                                    val fromIndex = currentOrder.indexOf(media.id)
                                                                    val toIndex = currentOrder.indexOf(targetId)
                                                                    if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
                                                                        val updatedOrder = currentOrder.toMutableList()
                                                                        val movingId = updatedOrder.removeAt(fromIndex)
                                                                        updatedOrder.add(toIndex, movingId)
                                                                        dragOrder = updatedOrder
                                                                    }
                                                                }
                                                            }
                                                        )
                                                    }
                                                }
                                        ) {
                                            CompactMediaCard(media, Modifier.fillMaxWidth(), onRemove = { lifecycleScope.launch { dao.delete(media) } }) {
                                                detailItem = TmdbResult(media.tmdbId ?: 0, media.title, media.mediaType, media.year, media.posterPath, media.overview, media.releaseDate, media.tmdbVoteAverage)
                                                detailReturnPage = "Minha lista"
                                                detailSeasons = media.seasonProgress.ifBlank { "[]" }
                                                navigateTo("Detalhes")
                                                if (media.mediaType == "tv" && apiKey.isNotBlank()) lifecycleScope.launch {
                                                    detailLoading = true
                                                    val imported = runCatching { TmdbClient.seriesSeasons(media.tmdbId ?: 0, apiKey) }.getOrDefault("[]")
                                                    val saved = runCatching { JSONArray(media.seasonProgress) }.getOrNull()
                                                    val fresh = JSONArray(imported)
                                                    if (saved != null) {
                                                        for (i in 0 until fresh.length()) {
                                                            val season = fresh.optJSONObject(i) ?: continue
                                                            for (j in 0 until saved.length()) {
                                                                val oldSeason = saved.optJSONObject(j) ?: continue
                                                                if (oldSeason.optInt("number") == season.optInt("number")) {
                                                                    season.put("status", oldSeason.optString("status", "WATCHLIST"))
                                                                    val oldEpisodes = oldSeason.optJSONArray("episodeList")
                                                                    val newEpisodes = season.optJSONArray("episodeList")
                                                                    if (oldEpisodes != null && newEpisodes != null) {
                                                                        for (e in 0 until newEpisodes.length()) {
                                                                            val newEpisode = newEpisodes.optJSONObject(e) ?: continue
                                                                            for (oe in 0 until oldEpisodes.length()) {
                                                                                val oldEpisode = oldEpisodes.optJSONObject(oe) ?: continue
                                                                                if (oldEpisode.optInt("number") == newEpisode.optInt("number")) {
                                                                                    newEpisode.put("watched", oldEpisode.optBoolean("watched", false))
                                                                                    break
                                                                                }
                                                                            }
                                                                        }
                                                                    }
                                                                    break
                                                                }
                                                            }
                                                        }
                                                    }
                                                    detailSeasons = fresh.toString()
                                                    detailLoading = false
                                                }
                                            }
                                        }
                                    }
                                }
                                }
                            }
                            "Franquias" -> {
                                Column(Modifier.weight(1f)) {
                                    Text("UNIVERSOS DE FRANQUIAS", color = Purple, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Text("Filmes relacionados, organizados pela ordem de lançamento.", color = Muted, fontSize = 11.sp)
                                    if (franchiseCollections.isEmpty()) {
                                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                            Text(
                                                when {
                                                    apiKey.isBlank() -> "Configure o TMDB para identificar as franquias."
                                                    all.none { it.mediaType == "movie" && it.tmdbId != null } -> "Adicione filmes à sua coleção para descobrir franquias."
                                                    all.any { it.mediaType == "movie" && it.tmdbId != null && it.franchiseId == null } -> "Identificando os universos dos seus filmes…"
                                                    else -> "Nenhuma franquia foi identificada nos filmes da sua coleção."
                                                },
                                                color = Muted,
                                                fontSize = 12.sp
                                            )
                                        }
                                    } else {
                                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                                            items(franchiseCollections, key = { it.id }) { franchise ->
                                                Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Text(franchise.name, color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                                    Text("${franchise.parts.size} filmes • ordem de lançamento", color = Purple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                    if (franchise.overview.isNotBlank()) Text(franchise.overview, color = Muted, fontSize = 11.sp, maxLines = 3)
                                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                        items(franchise.parts, key = { it.id }) { part ->
                                                            val saved = all.firstOrNull { it.tmdbId == part.id && it.mediaType == "movie" }
                                                            Column(Modifier.width(104.dp).clickable {
                                                                detailItem = part
                                                                detailReturnPage = "Franquias"
                                                                navigateTo("Detalhes")
                                                            }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                                AsyncImage(model = part.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }, contentDescription = part.title, modifier = Modifier.fillMaxWidth().height(148.dp).background(Night, RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                                                                Text(part.title, color = TextMain, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 2, minLines = 2)
                                                                Text(part.year.ifBlank { "Ano indisponível" }, color = Muted, fontSize = 9.sp)
                                                                Text(if (saved == null) "NÃO ADICIONADO" else when (saved.status) {
                                                                    "WATCHED" -> "JÁ ASSISTI"
                                                                    "WATCHING" -> "TÔ ASSISTINDO"
                                                                    else -> "QUERO ASSISTIR"
                                                                }, color = if (saved == null) Muted else Purple, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            "Estatísticas" -> {
                                val watched = all.filter { it.status == "WATCHED" }
                                val movies = all.count { it.mediaType == "movie" }
                                val shows = all.count { it.mediaType == "tv" }
                                                                LazyColumn(state = statisticsState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                                    item { StatCard("Títulos na coleção", all.size.toString(), "Todos os filmes e séries salvos") }
                                    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Box(Modifier.weight(1f)) { StatCard("Filmes", movies.toString(), "Na coleção") }; Box(Modifier.weight(1f)) { StatCard("Séries", shows.toString(), "Na coleção") } } }
                                    item { StatCard("Concluídos", watched.size.toString(), "Marcados como assistidos") }
                                    item { StatCard("Em andamento", all.count { it.status == "WATCHING" }.toString(), "Títulos que você está acompanhando") }
                                    item { StatCard("Episódios registrados", all.sumOf { it.watchedEpisodes }.toString(), "Progresso salvo nas séries") }
                                }
                            }
                            "Calendário" -> {
                                val watched = all.filter { it.status == "WATCHED" && it.watchedAt != null }.sortedByDescending { it.watchedAt }
                                Text("Histórico de títulos marcados como assistidos", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp))
                                if (watched.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Seus títulos concluídos aparecerão aqui.", color = Muted) }
                                else LazyColumn(state = calendarState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(watched, key = { it.id }) { media ->
                                        val date = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(java.util.Date(media.watchedAt!!))
                                        Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) { Text(media.title, fontWeight = FontWeight.Bold); Text("Concluído em $date", color = Muted, fontSize = 12.sp) }
                                            Text(if (media.mediaType == "tv") "SÉRIE" else "FILME", color = Purple, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                            "Roleta" -> {
                                Spacer(Modifier.height(22.dp))
                                Box(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(22.dp)).padding(22.dp), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("O QUE VAMOS ASSISTIR?", color = Purple, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                        Spacer(Modifier.height(16.dp))
                                        if (roulettePick == null) Text("Deixe a sorte decidir.", color = TextMain, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                        else {
                                            if (!roulettePick!!.posterPath.isNullOrBlank()) AsyncImage(model = "https://image.tmdb.org/t/p/w342${roulettePick!!.posterPath}", contentDescription = roulettePick!!.title, modifier = Modifier.width(120.dp).height(175.dp))
                                            Text(roulettePick!!.title, color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                            Text(if (roulettePick!!.mediaType == "tv") "Série" else "Filme", color = Muted)
                                        }
                                        Spacer(Modifier.height(18.dp))
                                        Button(onClick = { roulettePick = all.filter { it.status != "WATCHED" }.randomOrNull() ?: all.randomOrNull(); if (roulettePick == null) message = "Adicione títulos à coleção primeiro." }, modifier = Modifier.fillMaxWidth()) { Text("Sortear título") }
                                        TextButton(onClick = { navigateTo("Buscar") }) { Text("Adicionar mais títulos") }
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                            }
                            "Config." -> {
                                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("DESTAQUE DA PÁGINA INICIAL", color = palette.third, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("Escolha um título da sua coleção para destacar.", color = Muted, fontSize = 12.sp)
                                var featuredMenu by remember { mutableStateOf(false) }
                                Box {
                                    OutlinedButton(onClick = { featuredMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                        Text(featuredTitle.ifBlank { "Escolher título em destaque" }, maxLines = 1)
                                    }
                                    DropdownMenu(expanded = featuredMenu, onDismissRequest = { featuredMenu = false }) {
                                        DropdownMenuItem(text = { Text("Sem destaque") }, onClick = { featuredTitle = ""; prefs.edit().remove("featured_title").apply(); featuredMenu = false })
                                        all.forEach { media ->
                                            DropdownMenuItem(text = { Text(media.title, maxLines = 1) }, onClick = { featuredTitle = media.title; prefs.edit().putString("featured_title", media.title).apply(); featuredMenu = false })
                                        }
                                    }
                                }
                                Text("ORGANIZAÇÃO DA PÁGINA INICIAL", color = palette.third, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                listOf("Padrão", "Minha coleção primeiro", "Descobertas primeiro").forEach { option ->
                                    Row(Modifier.fillMaxWidth().clickable { homeOrder = option; prefs.edit().putString("home_order", option).apply() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = homeOrder == option, onClick = { homeOrder = option; prefs.edit().putString("home_order", option).apply() })
                                        Text(option, color = TextMain, fontSize = 13.sp)
                                    }
                                }
                                Divider(color = Muted.copy(alpha = .25f))
                                Text("ATUALIZAÇÕES DO CINEVERSO", color = palette.third, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("Confira se existe uma nova versão do aplicativo. Suas listas e seus dados permanecem salvos durante a atualização.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                                Button(
                                    onClick = {
                                        if (!updateChecking) {
                                            updateChecking = true
                                            updateMessage = "Verificando a versão mais recente..."
                                            lifecycleScope.launch {
                                                val result = withContext(Dispatchers.IO) {
                                                    runCatching {
                                                        val connection = (URL("https://api.github.com/repos/guibsilla-svg/CineVerse/releases?per_page=100").openConnection() as HttpURLConnection).apply {
                                                            connectTimeout = 15000
                                                            readTimeout = 15000
                                                            setRequestProperty("Accept", "application/vnd.github+json")
                                                            setRequestProperty("User-Agent", "CineVerso-Android")
                                                        }
                                                        try {
                                                            val releases = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
                                                            val latest = (0 until releases.length())
                                                                .map { releases.getJSONObject(it) }
                                                                .filter {
                                                                    it.optString("tag_name").removePrefix("v").startsWith("1.0.") &&
                                                                        !it.optBoolean("draft") && !it.optBoolean("prerelease")
                                                                }
                                                                .maxByOrNull { it.optString("tag_name").removePrefix("v") }
                                                            val tag = latest?.optString("tag_name")?.removePrefix("v").orEmpty()
                                                            val assets = latest?.optJSONArray("assets")
                                                            val apk = assets?.let { array ->
                                                                (0 until array.length()).map { array.getJSONObject(it) }
                                                                    .firstOrNull { it.optString("name") == "cineverse.apk" }
                                                                    ?.optString("browser_download_url")
                                                            }
                                                            if (tag.isBlank() || apk?.startsWith("https://") != true) {
                                                                throw IllegalStateException("Não foi possível localizar o APK da versão estável.")
                                                            }
                                                            if (compareVersions(tag, BuildConfig.VERSION_NAME) > 0) tag to apk else null
                                                        } finally {
                                                            connection.disconnect()
                                                        }
                                                    }
                                                }
                                                result.fold(
                                                    onSuccess = { release ->
                                                        if (release == null) {
                                                            updateMessage = "Seu CineVerso já está atualizado (versão ${BuildConfig.VERSION_NAME})."
                                                        } else {
                                                            updateMessage = "Versão ${release.first} disponível. Iniciando o download..."
                                                            downloadAndInstallUpdate(release.second, release.first)
                                                        }
                                                    },
                                                    onFailure = {
                                                        updateMessage = "Não foi possível verificar agora. Confira sua conexão e tente novamente."
                                                    }
                                                )
                                                updateChecking = false
                                            }
                                        }
                                    },
                                    enabled = !updateChecking,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (updateChecking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    else Text("Verificar atualizações")
                                }
                                if (updateMessage.isNotBlank()) {
                                    Text(updateMessage, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                                }
                                Spacer(Modifier.height(8.dp))
                                Text("BACKUP AUTOMÁTICO", color = palette.third, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("O Android pode salvar automaticamente a coleção, o progresso e as avaliações na conta Google quando o backup do aparelho estiver ativado. Ao reinstalar o CineVerso, os dados podem ser restaurados usando a mesma conta. Não há cobrança nem servidor próprio; a execução depende das configurações e da disponibilidade do backup do Android.", color = Muted, fontSize = 12.sp)
                                Divider(color = Muted.copy(alpha = .25f))
                                Text("ACESSO OFFLINE", color = palette.third, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("Sua coleção, avaliações, favoritos e progresso ficam salvos localmente. Esses dados continuam disponíveis sem internet; novas buscas, pôsteres não armazenados e atualizações do catálogo exigem conexão.", color = Muted, fontSize = 12.sp)
                                Divider(color = Muted.copy(alpha = .25f))
                                Text("AVALIAÇÕES TMDB", color = palette.third, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("As notas exibidas são as avaliações da comunidade do The Movie Database (TMDB).", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                                Divider(color = Muted.copy(alpha = .25f))
                                Text("INTEGRAÇÃO TMDB", color = palette.third, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("A chave fica salva neste aparelho e é usada para buscar títulos, sinopses, pôsteres e avaliações.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                                Button(onClick = { showKey = true }, modifier = Modifier.fillMaxWidth()) { Text(if (apiKey.isBlank()) "Configurar chave TMDB" else "Alterar chave TMDB") }
                                Spacer(Modifier.height(8.dp))
                                Divider(color = Muted.copy(alpha = .25f))
                                Text("TRÊS CENAS PARA SUA INDICAÇÃO", color = palette.third, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("Ao gerar uma indicação, o CineVerso permite escolher primeiro um banner horizontal no Google Imagens e depois três capturas reais de cenas. As imagens selecionadas são usadas temporariamente na composição; não são adicionadas à galeria nem substituídas por pôsteres ou artes promocionais.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                                OutlinedButton(onClick = {
                                    val json = JSONArray()
                                    all.forEach { m -> json.put(JSONObject().put("title", m.title).put("tmdbId", m.tmdbId).put("mediaType", m.mediaType).put("year", m.year).put("posterPath", m.posterPath).put("overview", m.overview).put("status", m.status).put("rating", m.rating).put("watchedAt", m.watchedAt).put("runtimeMinutes", m.runtimeMinutes).put("genres", m.genres).put("totalSeasons", m.totalSeasons).put("watchedEpisodes", m.watchedEpisodes).put("totalEpisodes", m.totalEpisodes).put("releaseDate", m.releaseDate).put("seasonProgress", m.seasonProgress).put("sortOrder", m.sortOrder).put("tmdbVoteAverage", m.tmdbVoteAverage).put("personalRating", m.personalRating).put("franchiseId", m.franchiseId).put("franchiseName", m.franchiseName)) }
                                    val send = Intent(Intent.ACTION_SEND).apply { type = "application/json"; putExtra(Intent.EXTRA_SUBJECT, "Backup CineVerso"); putExtra(Intent.EXTRA_TEXT, json.toString(2)) }
                                    startActivity(Intent.createChooser(send, "Exportar backup CineVerso"))
                                }, modifier = Modifier.fillMaxWidth()) { Text("Exportar backup JSON") }
                                Text("O backup contém os dados da coleção. Guarde o arquivo em local seguro.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                                Text("ALERTAS DE ESTREIAS E EPISÓDIOS", color = palette.third, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("O CineVerso verifica a cada 12 horas os próximos episódios das séries marcadas como Tô assistindo e as estreias dos filmes em Quero assistir. Os avisos de episódios cobrem hoje e os próximos dois dias; os de filmes, os próximos sete dias. As datas dependem das informações disponíveis no TMDB.", color = Muted, fontSize = 12.sp)
                                OutlinedButton(onClick = { if (android.os.Build.VERSION.SDK_INT >= 33) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.fillMaxWidth()) { Text("Ativar permissão de notificações") }
                                OutlinedButton(onClick = {
                                    val request = OneTimeWorkRequestBuilder<EpisodeAlertWorker>()
                                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                                        .build()
                                    WorkManager.getInstance(this@MainActivity).enqueue(request)
                                }, modifier = Modifier.fillMaxWidth()) { Text("Verificar estreias e episódios agora") }
                                }
                            }
                            else -> {
                                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                val featured = all.firstOrNull { it.title == featuredTitle }
                                if (featured != null) {
                                    Text("ESCOLHIDO PARA VOCÊ", color = palette.third, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                                    Row(
                                        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp))
                                            .clickable {
                                                detailItem = TmdbResult(featured.tmdbId ?: 0, featured.title, featured.mediaType, featured.year, featured.posterPath, featured.overview, featured.releaseDate, featured.tmdbVoteAverage)
                                                detailReturnPage = "Início"
                                                detailSeasons = featured.seasonProgress.ifBlank { "[]" }
                                                navigateTo("Detalhes")
                                            }.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (!featured.posterPath.isNullOrBlank()) {
                                            AsyncImage(model = "https://image.tmdb.org/t/p/w342${featured.posterPath}", contentDescription = featured.title, modifier = Modifier.width(82.dp).height(120.dp), contentScale = ContentScale.Crop)
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(featured.title, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                            Text("${if (featured.mediaType == "tv") "Série" else "Filme"} • ${featured.year}", color = Muted, fontSize = 12.sp)
                                             Text(tmdbRatingLabel(featured.tmdbVoteAverage), color = Color(0xFFFFD166), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            Text("Título selecionado nas configurações", color = palette.third, fontSize = 11.sp)
                                        }
                                    }
                                    Spacer(Modifier.height(14.dp))
                                }
                                
                                Spacer(Modifier.height(14.dp))
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(
                                            androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF38205D), Color(0xFF171321))),
                                            RoundedCornerShape(20.dp)
                                        )
                                        .clickable { rouletteSuggestion = null; showRouletteDialog = true }
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier.size(76.dp).background(Color(0xFF241638), RoundedCornerShape(18.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("🎡", fontSize = 42.sp)
                                    }
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("ROLETA CINEVERSO", color = Color(0xFFD7B5FF), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                                        Text("Não sabe o que assistir?", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                                        Text("Gire e descubra seu próximo filme ou série.", color = Muted, fontSize = 11.sp)
                                        Spacer(Modifier.height(8.dp))
                                        Text("GIRAR AGORA  →", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                Spacer(Modifier.height(14.dp))
                                Text("EM ALTA • FILMES", color = Purple, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                if (recommendedMovies.isNotEmpty()) {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        items(recommendedMovies, key = { "movie-" + it.id }) { item ->
                                            RecommendationCard(item) {
                                                detailItem = item
                                                navigateTo("Detalhes")
                                                if (item.type == "tv" && apiKey.isNotBlank()) lifecycleScope.launch {
                                                    detailLoading = true
                                                    val imported = runCatching { TmdbClient.seriesSeasons(item.id, apiKey) }.getOrDefault("[]")
                                                    val savedProgress = dao.findByTmdbId(item.id, "tv")?.seasonProgress.orEmpty()
                                                    detailSeasons = mergeSavedSeasonProgress(imported, savedProgress)
                                                    detailLoading = false
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Text(if (apiKey.isBlank()) "Configure o TMDB em Mais > Config. para carregar recomendações." else if (recommendationsLoading) "Carregando..." else recommendationsError.ifBlank { "Sem recomendações disponíveis no momento." }, color = Muted, fontSize = 12.sp)
                                }
                                Spacer(Modifier.height(14.dp))
                                Text("EM ALTA • SÉRIES", color = Purple, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                if (recommendedShows.isNotEmpty()) {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        items(recommendedShows, key = { "tv-" + it.id }) { item ->
                                            RecommendationCard(item) {
                                                detailItem = item
                                                navigateTo("Detalhes")
                                                if (item.type == "tv" && apiKey.isNotBlank()) lifecycleScope.launch {
                                                    detailLoading = true
                                                    val imported = runCatching { TmdbClient.seriesSeasons(item.id, apiKey) }.getOrDefault("[]")
                                                    val savedProgress = dao.findByTmdbId(item.id, "tv")?.seasonProgress.orEmpty()
                                                    detailSeasons = mergeSavedSeasonProgress(imported, savedProgress)
                                                    detailLoading = false
                                                }
                                            }
                                        }
                                    }
                                } else if (apiKey.isNotBlank() && !recommendationsLoading) {
                                    Text(recommendationsError.ifBlank { "Recomendações de séries indisponíveis no momento." }, color = Muted, fontSize = 12.sp)
                                }
                                Spacer(Modifier.height(14.dp))

                                }
                            }
                        }
                        NavigationBar(
                            modifier = Modifier.fillMaxWidth().height(62.dp),
                            containerColor = Panel,
                            tonalElevation = 0.dp
                        ) {
                            var moreMenu by remember { mutableStateOf(false) }
                            listOf("Início", "Explorar", "Minha lista").forEach { destination ->
                                NavigationBarItem(
                                    selected = page == destination,
                                    onClick = { navigateTo(destination) },
                                    icon = {
                                        Text(
                                            when (destination) {
                                                "Início" -> "⌂"
                                                "Explorar" -> "✦"
                                                else -> "▣"
                                            },
                                            fontSize = 20.sp,
                                            color = if (page == destination) Purple else Muted
                                        )
                                    },
                                    label = { Text(destination, fontSize = 9.sp, maxLines = 1) },
                                    alwaysShowLabel = true
                                )
                            }
                            NavigationBarItem(
                                selected = page in listOf("Estatísticas", "Calendário", "Config."),
                                onClick = { moreMenu = true },
                                icon = { Text("•••", color = Muted, fontSize = 18.sp) },
                                label = { Text("Mais", fontSize = 9.sp) }
                            )
                            Box {
                                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                                    listOf("Estatísticas", "Calendário", "Config.").forEach { destination ->
                                        DropdownMenuItem(text = { Text(destination) }, onClick = { navigateTo(destination); moreMenu = false })
                                    }
                                }
                            }
                        }
                    }
                }
                if (showRouletteDialog) {
                    val collectionOptions = all.filter { it.mediaType == rouletteType && it.status != "WATCHED" && it.tmdbId != null && yearMatches(it.year, rouletteYear) }
                        .map { media -> TmdbResult(media.tmdbId!!, media.title, media.mediaType, media.year, media.posterPath, media.overview, media.releaseDate, media.tmdbVoteAverage) }
                    val options = if (rouletteMode == "LIST") collectionOptions else emptyList()
                    val genreOptions = if (rouletteType == "movie") listOf(
                        "Todos" to null, "Ação" to 28, "Aventura" to 12, "Animação" to 16, "Comédia" to 35,
                        "Crime" to 80, "Documentário" to 99, "Drama" to 18, "Família" to 10751, "Fantasia" to 14,
                        "Ficção científica" to 878, "Mistério" to 9648, "Romance" to 10749, "Terror" to 27,
                        "Suspense" to 53, "Musical" to 10402, "Guerra" to 10752, "Faroeste" to 37
                    ) else listOf(
                        "Todos" to null, "Ação e aventura" to 10759, "Animação" to 16, "Comédia" to 35,
                        "Crime" to 80, "Documentário" to 99, "Drama" to 18, "Família" to 10751,
                        "Infantil" to 10762, "Mistério" to 9648, "Notícias" to 10763, "Reality" to 10764,
                        "Ficção científica e fantasia" to 10765, "Novela" to 10766, "Talk show" to 10767,
                        "Guerra e política" to 10768, "Western" to 37
                    )
                    val selectedGenreId = genreOptions.firstOrNull { it.first == rouletteGenre }?.second
                    fun startRoulette() {
                        if (rouletteSpinning || (rouletteMode == "LIST" && options.isEmpty())) return
                        rouletteSpinning = true
                        rouletteError = ""
                        rouletteSuggestion = null
                        rouletteScope.launch {
                            try {
                                val pool: List<TmdbResult> = if (rouletteMode == "LIST") options else {
                                    TmdbClient.discoverRandom(
                                        rouletteType, selectedGenreId,
                                        rouletteYear.takeIf { it != "Todos" }, apiKey, originalLanguages = listOf("en", "es")
                                    ).filter { candidate ->
                                        all.none { it.mediaType == candidate.type && it.tmdbId == candidate.id }
                                    }
                                }
                                rouletteCarouselItems = pool.shuffled().take(8)
                                rouletteWheelRotation.animateTo(
                                    rouletteWheelRotation.value + 1440f + (360..720).random(),
                                    animationSpec = tween(durationMillis = 3800, easing = FastOutSlowInEasing)
                                )
                                if (pool.isEmpty()) {
                                    rouletteError = "Não encontrei títulos com esse filtro. Tente outro gênero."
                                } else {
                                    rouletteSuggestion = if (rouletteMode == "LIST") pool.random() else TmdbClient.pickRouletteTitle(pool)
                                }
                            } catch (error: Exception) {
                                rouletteError = error.message ?: "Não foi possível consultar o catálogo. Confira sua conexão e a chave TMDB."
                            } finally {
                                rouletteSpinning = false
                            }
                        }
                    }
                    AlertDialog(
                        onDismissRequest = { if (!rouletteSpinning) showRouletteDialog = false },
                        title = { Text(if (rouletteSuggestion != null && !rouletteSpinning) "A roleta escolheu!" else "Roleta CineVerso", color = TextMain, fontWeight = FontWeight.ExtraBold) },
                        text = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(11.dp)) {
                                if (rouletteSuggestion != null && !rouletteSpinning) {
                                    val picked = rouletteSuggestion!!
                                    if (!picked.posterPath.isNullOrBlank()) AsyncImage(model = "https://image.tmdb.org/t/p/w342${picked.posterPath}", contentDescription = picked.title, modifier = Modifier.width(132.dp).height(190.dp), contentScale = ContentScale.Crop)
                                    Text(picked.title, color = TextMain, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                                    Text("${if (picked.type == "tv") "Série" else "Filme"} • ${picked.year.ifBlank { "Ano indisponível" }}", color = Muted)
                                    Text(if (rouletteMode == "LIST") "Sorteado da sua coleção" else "Descoberta aleatória • ${rouletteGenre}", color = Purple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                } else {
                                    Text("Hoje quem escolhe é o acaso.", color = Muted, fontSize = 13.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        if (rouletteMode == "LIST") {
                                            Button(onClick = { rouletteMode = "LIST"; rouletteSuggestion = null }, modifier = Modifier.weight(1f)) { Text("Minha lista") }
                                            OutlinedButton(onClick = { rouletteMode = "DISCOVER"; rouletteSuggestion = null }, modifier = Modifier.weight(1f)) { Text("Descobrir") }
                                        } else {
                                            OutlinedButton(onClick = { rouletteMode = "LIST"; rouletteSuggestion = null }, modifier = Modifier.weight(1f)) { Text("Minha lista") }
                                            Button(onClick = { rouletteMode = "DISCOVER"; rouletteSuggestion = null }, modifier = Modifier.weight(1f)) { Text("Descobrir") }
                                        }
                                    }
                                    Text(if (rouletteMode == "LIST") "Títulos da sua coleção que ainda não foram assistidos." else "Catálogo completo do TMDB: sorteio em páginas aleatórias, sem ficar preso aos títulos em alta." , color = Muted, fontSize = 11.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        FilterChip(selected = rouletteType == "movie", onClick = { rouletteType = "movie"; rouletteGenre = "Todos"; rouletteSuggestion = null; rouletteCarouselItems = emptyList() }, enabled = !rouletteSpinning, label = { Text("Filme") })
                                        FilterChip(selected = rouletteType == "tv", onClick = { rouletteType = "tv"; rouletteGenre = "Todos"; rouletteSuggestion = null; rouletteCarouselItems = emptyList() }, enabled = !rouletteSpinning, label = { Text("Série") })
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        Box(Modifier.weight(1f)) {
                                            OutlinedButton(onClick = { rouletteYearMenu = true }, modifier = Modifier.fillMaxWidth(), enabled = !rouletteSpinning) { Text("Ano: $rouletteYear") }
                                            DropdownMenu(expanded = rouletteYearMenu, onDismissRequest = { rouletteYearMenu = false }) {
                                                listOf("Todos", "2020–atual", "2010–2019", "2000–2009", "1990–1999", "1980–1989", "Antes de 1980").forEach { period -> DropdownMenuItem(text = { Text(period) }, onClick = { rouletteYear = period; rouletteSuggestion = null; rouletteYearMenu = false }) }
                                            }
                                        }
                                    }
                                    if (rouletteMode == "DISCOVER") {
                                        Box {
                                            OutlinedButton(onClick = { rouletteGenreMenu = true }, modifier = Modifier.fillMaxWidth(), enabled = !rouletteSpinning) {
                                                Text("Gênero: $rouletteGenre  ▾")
                                            }
                                            DropdownMenu(expanded = rouletteGenreMenu, onDismissRequest = { rouletteGenreMenu = false }) {
                                                genreOptions.forEach { (name, _) ->
                                                    DropdownMenuItem(text = { Text(name) }, onClick = { rouletteGenre = name; rouletteSuggestion = null; rouletteGenreMenu = false })
                                                }
                                            }
                                        }
                                    } else {
                                        Text("Gênero: não aplicado à coleção pessoal.", color = Muted, fontSize = 10.sp)
                                    }
                                    val wheelItems = (if (rouletteMode == "LIST") options else rouletteCarouselItems).take(8)
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().height(350.dp)) {
                                        Box(Modifier.size(320.dp), contentAlignment = Alignment.Center) {
                                            Canvas(Modifier.fillMaxSize()) {
                                                val colors = listOf(
                                                    Color(0xFF302044), Color(0xFF21182F), Color(0xFF3A2452), Color(0xFF241B35),
                                                    Color(0xFF342044), Color(0xFF20182D), Color(0xFF39234D), Color(0xFF251A38)
                                                )
                                                val diameter = size.minDimension
                                                val inset = 5.dp.toPx()
                                                colors.forEachIndexed { index, color ->
                                                    drawArc(color, -112.5f + index * 45f, 43f, true,
                                                        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                                                        size = androidx.compose.ui.geometry.Size(diameter - inset * 2, diameter - inset * 2))
                                                }
                                                drawCircle(Color(0xFFB77AFF), radius = diameter / 2 - 2.dp.toPx(), style = Stroke(3.dp.toPx()))
                                                drawCircle(Color(0xFF6E4A91), radius = diameter * .31f, style = Stroke(2.dp.toPx()))
                                                drawCircle(Color(0xFF1A1324), radius = diameter * .245f)
                                            }
                                            Box(Modifier.size(320.dp).graphicsLayer { rotationZ = rouletteWheelRotation.value },
                                                contentAlignment = Alignment.Center) {
                                                wheelItems.forEachIndexed { index, poster ->
                                                    val angle = (index * (360f / maxOf(wheelItems.size, 1)) - 90f) * (Math.PI / 180.0)
                                                    val dx = (108.0 * kotlin.math.cos(angle)).toFloat()
                                                    val dy = (108.0 * kotlin.math.sin(angle)).toFloat()
                                                    Column(
                                                        modifier = Modifier.offset(x = dx.dp, y = dy.dp).width(76.dp)
                                                            .background(Color(0xFF17111F), RoundedCornerShape(9.dp))
                                                            .padding(3.dp),
                                                        horizontalAlignment = Alignment.CenterHorizontally
                                                    ) {
                                                        AsyncImage(
                                                            model = poster.posterPath?.let { "https://image.tmdb.org/t/p/w185$it" },
                                                            contentDescription = poster.title,
                                                            modifier = Modifier.fillMaxWidth().height(104.dp)
                                                                .background(Color(0xFF14263A), RoundedCornerShape(6.dp)),
                                                            contentScale = ContentScale.Crop
                                                        )
                                                        Text(poster.title, color = Color.White, fontSize = 7.sp,
                                                            maxLines = 1, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                            Box(
                                                Modifier.size(112.dp)
                                                    .background(Color(0xFF130E1B), RoundedCornerShape(56.dp))
                                                    .padding(5.dp)
                                                    .background(Color(0xFF302044), RoundedCornerShape(56.dp))
                                                    .clickable(enabled = !rouletteSpinning && (rouletteMode == "DISCOVER" || options.isNotEmpty())) {
                                                        startRoulette()
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                androidx.compose.foundation.Image(
                                                    painter = painterResource(id = R.drawable.cineverse_logo_horizontal),
                                                    contentDescription = "Girar roleta CineVerso",
                                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp).height(42.dp)
                                                )
                                            }
                                        }
                                        if (wheelItems.isEmpty()) Text(
                                            if (rouletteMode == "LIST") "Adicione títulos à sua lista" else "Gire para descobrir um título",
                                            color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp)
                                        )
                                    }
                                    Text(if (rouletteSpinning) "A sorte está sendo lançada..." else "Toque em GIRAR e deixe a roleta decidir!", color = Purple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    if (rouletteError.isNotBlank()) Text(rouletteError, color = Color(0xFFFF8A8A), fontSize = 11.sp)
                                }
                            }
                        },
                        confirmButton = {
                            if (rouletteSuggestion != null && !rouletteSpinning) {
                                TextButton(onClick = {
                                    val picked = rouletteSuggestion!!
                                    detailItem = picked
                                    detailReturnPage = "Início"
                                    showRouletteDialog = false
                                    navigateTo("Detalhes")
                                    if (picked.type == "tv" && apiKey.isNotBlank()) lifecycleScope.launch {
                                        detailLoading = true
                                        val imported = runCatching { TmdbClient.seriesSeasons(picked.id, apiKey) }.getOrDefault("[]")
                                        val savedProgress = dao.findByTmdbId(picked.id, "tv")?.seasonProgress.orEmpty()
                                        detailSeasons = mergeSavedSeasonProgress(imported, savedProgress)
                                        detailLoading = false
                                    }
                                }) { Text("Ver detalhes") }
                            } else {
                                TextButton(enabled = !rouletteSpinning && (rouletteMode == "DISCOVER" || options.isNotEmpty()), onClick = {
                                    startRoulette()
                                }) { Text(if (rouletteSpinning) "Girando..." else "GIRAR A ROLETA") }
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                if (!rouletteSpinning) {
                                    if (rouletteSuggestion != null) rouletteSuggestion = null else showRouletteDialog = false
                                }
                            }) { Text(if (rouletteSuggestion != null && !rouletteSpinning) "Sortear novamente" else "Fechar") }
                        },
                        containerColor = Panel
                    )
                }
                                if (editingSeries != null) {
                    val series = editingSeries!!
                    AlertDialog(onDismissRequest = { editingSeries = null },
                        title = { Text("Progresso da série") },
                        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(series.title, color = Purple, fontWeight = FontWeight.Bold)
                            OutlinedTextField(value = seasonInput, onValueChange = { seasonInput = it.filter(Char::isDigit).take(3) }, label = { Text("Total de temporadas") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                            OutlinedTextField(value = totalEpisodesInput, onValueChange = { totalEpisodesInput = it.filter(Char::isDigit).take(4) }, label = { Text("Total de episódios") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                            OutlinedTextField(value = watchedEpisodesInput, onValueChange = { watchedEpisodesInput = it.filter(Char::isDigit).take(4) }, label = { Text("Episódios assistidos") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                        } },
                        confirmButton = { TextButton(onClick = {
                            val total = totalEpisodesInput.toIntOrNull()?.coerceAtLeast(0) ?: 0
                            val watched = (watchedEpisodesInput.toIntOrNull() ?: 0).coerceIn(0, if (total > 0) total else 9999)
                            lifecycleScope.launch { dao.update(series.copy(totalSeasons = seasonInput.toIntOrNull()?.coerceAtLeast(0) ?: 0, totalEpisodes = total, watchedEpisodes = watched)) }
                            editingSeries = null
                        }) { Text("Salvar") } },
                        dismissButton = { TextButton(onClick = { editingSeries = null }) { Text("Cancelar") } })
                }
                if (showKey) {
                    AlertDialog(onDismissRequest = { showKey = false }, title = { Text("Credencial da API TMDB") },
                        text = { Column { Text("Cole a API Read Access Token (Bearer) ou a API Key v3. Ambas ficam em themoviedb.org/settings/api."); Spacer(Modifier.height(8.dp)); OutlinedTextField(value = apiKey, onValueChange = { apiKey = it.trim() }, label = { Text("Token Bearer ou API Key v3") }, singleLine = true) } },
                        confirmButton = { TextButton(onClick = { prefs.edit().putString("tmdb_key", apiKey.trim()).apply(); showKey = false; message = "Chave salva neste aparelho." }) { Text("Salvar") } },
                        dismissButton = { TextButton(onClick = { showKey = false }) { Text("Cancelar") } })
                }
            }
        }
    }
}


private fun yearMatches(year: String, period: String): Boolean {
    val value = year.toIntOrNull() ?: return period == "Todos"
    val current = java.time.Year.now().value
    return when (period) {
        "2020–atual" -> value in 2020..current
        "2010–2019" -> value in 2010..2019
        "2000–2009" -> value in 2000..2009
        "1990–1999" -> value in 1990..1999
        "1980–1989" -> value in 1980..1989
        "Antes de 1980" -> value < 1980
        else -> true
    }
}

private fun compareVersions(first: String, second: String): Int {
    val a = first.split(".").map { it.toIntOrNull() ?: 0 }
    val b = second.split(".").map { it.toIntOrNull() ?: 0 }
    for (index in 0 until maxOf(a.size, b.size)) {
        val comparison = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
        if (comparison != 0) return comparison
    }
    return 0
}

private fun exploreGenres(type: String): List<Pair<String, Int?>> = if (type == "tv") listOf(
    "Todos" to null, "Ação e aventura" to 10759, "Animação" to 16, "Comédia" to 35, "Crime" to 80,
    "Documentário" to 99, "Drama" to 18, "Família" to 10751, "Infantil" to 10762, "Mistério" to 9648,
    "Ficção científica e fantasia" to 10765, "Novela" to 10766, "Reality" to 10764, "Guerra e política" to 10768
) else listOf(
    "Todos" to null, "Ação" to 28, "Aventura" to 12, "Animação" to 16, "Comédia" to 35, "Crime" to 80,
    "Documentário" to 99, "Drama" to 18, "Família" to 10751, "Fantasia" to 14, "História" to 36,
    "Terror" to 27, "Mistério" to 9648, "Romance" to 10749, "Ficção científica" to 878, "Suspense" to 53
)


private fun tmdbRatingLabel(value: Double): String = if (value > 0.0) "★ %.0f%% • TMDB".format(java.util.Locale.US, (value * 10.0).coerceIn(0.0, 100.0)) else "★ Sem avaliação TMDB"

@Composable
private fun ExploreMediaCard(item: TmdbResult, savedMedia: MediaItem?, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    Column(modifier.clickable(onClick = onOpen), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (!item.posterPath.isNullOrBlank()) {
            AsyncImage(
                model = "https://image.tmdb.org/t/p/w342" + item.posterPath,
                contentDescription = item.title,
                modifier = Modifier.fillMaxWidth().height(150.dp),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(Modifier.fillMaxWidth().height(150.dp).background(Panel, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Text("CineVerso", color = Purple, fontSize = 11.sp)
            }
        }
        Text(item.title, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 2, minLines = 2)
        Text(item.year.ifBlank { "Ano indisponível" }, color = Muted, fontSize = 10.sp, maxLines = 1)
        Text(tmdbRatingLabel(item.tmdbVoteAverage), color = Color(0xFFFFD166), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        if (savedMedia != null) {
            val label = when (savedMedia.status) {
                "WATCHED" -> "JÁ ASSISTI"
                "WATCHING" -> "TÔ ASSISTINDO"
                else -> "QUERO ASSISTIR"
            }
            Text(label, color = Purple, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            if (item.type == "tv" && savedMedia.totalEpisodes > 0) {
                Text("${savedMedia.watchedEpisodes}/${savedMedia.totalEpisodes} episódios", color = Muted, fontSize = 9.sp, maxLines = 1)
            }
        }
    }
}
@Composable
private fun ListToggleButton(inList: Boolean, onClick: () -> Unit) {
    if (inList) {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(32.dp), contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC29AFF))) {
            Text("NA MINHA LISTA", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(32.dp), contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) {
            Text("+ MINHA LISTA", fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun CompactMediaCard(media: MediaItem, modifier: Modifier = Modifier, onRemove: () -> Unit, onOpen: () -> Unit) {
    Column(modifier.clickable(onClick = onOpen), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (!media.posterPath.isNullOrBlank()) {
            AsyncImage(
                model = "https://image.tmdb.org/t/p/w342${media.posterPath}",
                contentDescription = media.title,
                modifier = Modifier.fillMaxWidth().height(150.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        } else {
            Box(Modifier.fillMaxWidth().height(150.dp).background(Panel, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Text("CineVerso", color = Purple, fontSize = 11.sp)
            }
        }
        Text(media.title, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 2, minLines = 2)
        Text(media.year.ifBlank { "Ano indisponível" }, color = Muted, fontSize = 10.sp, maxLines = 1)
        Text(tmdbRatingLabel(media.tmdbVoteAverage), color = Color(0xFFFFD166), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        if (media.personalRating > 0) Text("SUA NOTA • ${media.personalRating}/10", color = Purple, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        TextButton(onClick = onRemove, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(30.dp)) { Text("Remover da lista", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun RecommendationCard(item: TmdbResult, onOpen: () -> Unit) {
    Column(Modifier.width(116.dp).clickable(onClick = onOpen), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (!item.posterPath.isNullOrBlank()) {
            AsyncImage(model = "https://image.tmdb.org/t/p/w342" + item.posterPath, contentDescription = item.title, modifier = Modifier.fillMaxWidth().height(166.dp))
        } else {
            Box(Modifier.fillMaxWidth().height(166.dp).background(Panel, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Text("CineVerso", color = Purple, fontSize = 12.sp)
            }
        }
        Text(item.title, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 2)
        Text(item.year.ifBlank { "Em alta" }, color = Muted, fontSize = 10.sp)
        Text(tmdbRatingLabel(item.tmdbVoteAverage), color = Color(0xFFFFD166), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SearchCard(item: TmdbResult, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).clickable(onClick = onOpen).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!item.posterPath.isNullOrBlank()) AsyncImage(model = "https://image.tmdb.org/t/p/w185${item.posterPath}", contentDescription = item.title, modifier = Modifier.width(58.dp).height(84.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, color = TextMain, fontWeight = FontWeight.Bold)
            Text("${if (item.type == "tv") "Série" else "Filme"} • ${item.year.ifBlank { "Ano indisponível" }}", color = Muted, fontSize = 12.sp)
            Text(tmdbRatingLabel(item.tmdbVoteAverage), color = Color(0xFFFFD166), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            if (item.overview.isNotBlank()) Text(item.overview, color = Muted, fontSize = 11.sp, maxLines = 3)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("Detalhes", color = Purple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MediaCard(media: MediaItem, favorite: Boolean, onFavorite: () -> Unit, onStatus: (String) -> Unit, onProgress: (Int) -> Unit, onEditProgress: () -> Unit, onDelete: () -> Unit, showStatusActions: Boolean = true) {
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            if (!media.posterPath.isNullOrBlank()) AsyncImage(model = "https://image.tmdb.org/t/p/w342${media.posterPath}", contentDescription = media.title, modifier = Modifier.width(76.dp).height(112.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(media.title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("${if (media.mediaType == "tv") "Série" else "Filme"} • ${media.year}", color = Muted, fontSize = 12.sp)
                Text(tmdbRatingLabel(media.tmdbVoteAverage), color = Color(0xFFFFD166), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (media.personalRating > 0) Text("SUA NOTA • ${media.personalRating}/10", color = Purple, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                if (media.overview.isNotBlank()) Text(media.overview, color = Muted, fontSize = 12.sp, maxLines = 3, modifier = Modifier.padding(top = 4.dp))
                TextButton(onClick = onFavorite, contentPadding = PaddingValues(0.dp)) { Text(if (favorite) "♥ Favorito" else "♡ Favoritar", color = Purple) }
            }
        }
        if (media.mediaType == "tv") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("T${media.totalSeasons} • ${media.watchedEpisodes}${if (media.totalEpisodes > 0) "/${media.totalEpisodes}" else ""} episódios", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onEditProgress) { Text("Editar") }
                TextButton(onClick = { onProgress(-1) }, enabled = media.watchedEpisodes > 0) { Text("−") }
                TextButton(onClick = { onProgress(1) }) { Text("+1") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val statusLabel = when {
                media.mediaType == "movie" && media.status == "WATCHING" -> "QUERO ASSISTIR"
                media.status == "WATCHLIST" -> "QUERO ASSISTIR"
                media.mediaType == "tv" && media.status == "WATCHING" -> "TÔ ASSISTINDO"
                media.status == "WATCHED" -> "JÁ ASSISTI"
                else -> "NA COLEÇÃO"
            }
            Text(statusLabel, color = Purple, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                modifier = Modifier.background(Night, RoundedCornerShape(20.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Excluir", color = Muted, fontSize = 11.sp) }
        }
        if (showStatusActions) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { onStatus("WATCHLIST") }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 7.dp)) { Text("Quero assistir", fontSize = 10.sp) }
                if (media.mediaType == "tv") {
                    Button(onClick = { onStatus("WATCHING") }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 7.dp)) { Text("Tô assistindo", fontSize = 10.sp) }
                }
                Button(onClick = { onStatus("WATCHED") }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 7.dp)) { Text("Já assisti", fontSize = 10.sp) }
            }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, detail: String) {
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(16.dp)) {
        Text(title.uppercase(), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(value, color = Purple, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Text(detail, color = Muted, fontSize = 11.sp)
    }
}
