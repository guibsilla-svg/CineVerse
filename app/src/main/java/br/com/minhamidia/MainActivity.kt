package br.com.minhamidia

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import br.com.minhamidia.data.TmdbResult
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val Night = Color(0xFF08070D)
private val Panel = Color(0xFF171321)
private val Purple = Color(0xFFAC65FF)
private val TextMain = Color(0xFFF7F3FF)
private val Muted = Color(0xFFB7AEC6)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = AppDatabase.get(this).mediaDao()
        val prefs = getSharedPreferences("cineverse_settings", MODE_PRIVATE)
        setContent {
            var apiKey by remember { mutableStateOf(prefs.getString("tmdb_key", "")?.takeIf { it.isNotBlank() } ?: BuildConfig.TMDB_API_KEY) }
            var query by remember { mutableStateOf("") }
            var page by remember { mutableStateOf("Início") }
            val pageHistory = remember { mutableStateListOf<String>() }
            fun navigateTo(destination: String) {
                if (destination != page) {
                    pageHistory.add(page)
                    page = destination
                }
            }
            var filter by remember { mutableStateOf("Quero assistir") }
            var categoryFilter by remember { mutableStateOf("FILMES") }
            var yearFilter by remember { mutableStateOf("Todos") }
            var sortMode by remember { mutableStateOf("Manual") }
            var results by remember { mutableStateOf(emptyList<TmdbResult>()) }
            var detailItem by remember { mutableStateOf<TmdbResult?>(null) }
            var detailReturnPage by remember { mutableStateOf("Buscar") }
            var detailSeasons by remember { mutableStateOf("[]") }
            var detailLoading by remember { mutableStateOf(false) }
            var detailTrailer by remember { mutableStateOf<br.com.minhamidia.data.TmdbTrailer?>(null) }
            var detailTrailerLoading by remember { mutableStateOf(false) }
            var recommendedMovies by remember { mutableStateOf(emptyList<TmdbResult>()) }
            var recommendedShows by remember { mutableStateOf(emptyList<TmdbResult>()) }
            var recommendationsLoading by remember { mutableStateOf(false) }
            var recommendationsError by remember { mutableStateOf("") }
            var message by remember { mutableStateOf("Sua próxima história começa aqui.") }
            var showKey by remember { mutableStateOf(false) }
            var busy by remember { mutableStateOf(false) }
            var roulettePick by remember { mutableStateOf<MediaItem?>(null) }
            var showRouletteDialog by remember { mutableStateOf(false) }
            var rouletteType by remember { mutableStateOf("movie") }
            var rouletteMode by remember { mutableStateOf("DISCOVER") }
            var rouletteSuggestion by remember { mutableStateOf<TmdbResult?>(null) }
            var rouletteSpinning by remember { mutableStateOf(false) }
            var rouletteGenre by remember { mutableStateOf("Todos") }
            var rouletteGenreMenu by remember { mutableStateOf(false) }
            var rouletteError by remember { mutableStateOf("") }
            var exploreType by remember { mutableStateOf("movie") }
            var exploreCategory by remember { mutableStateOf("trending") }
            var exploreGenre by remember { mutableStateOf("Todos") }
            var explorePage by remember { mutableStateOf(1) }
            var exploreResults by remember { mutableStateOf(emptyList<TmdbResult>()) }
            var exploreLoading by remember { mutableStateOf(false) }
            var exploreError by remember { mutableStateOf("") }
            var exploreCanLoadMore by remember { mutableStateOf(true) }
            val rouletteWheelRotation = remember { Animatable(0f) }
            var editingSeries by remember { mutableStateOf<MediaItem?>(null) }
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
            BackHandler(enabled = showKey || editingSeries != null || pageHistory.isNotEmpty()) {
                when {
                    showKey -> showKey = false
                    editingSeries != null -> editingSeries = null
                    pageHistory.isNotEmpty() -> page = pageHistory.removeAt(pageHistory.lastIndex)
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
            LaunchedEffect(apiKey) {
                if (apiKey.isNotBlank()) {
                    recommendationsLoading = true
                    recommendationsError = ""
                    recommendedMovies = emptyList()
                    recommendedShows = emptyList()

                    val movieResult = runCatching {
                        val trending = TmdbClient.trendingMovies(apiKey).filter { it.posterPath != null }
                        if (trending.isNotEmpty()) trending else TmdbClient.popularMovies(apiKey).filter { it.posterPath != null }
                    }
                    val showResult = runCatching {
                        val trending = TmdbClient.trendingShows(apiKey).filter { it.posterPath != null }
                        if (trending.isNotEmpty()) trending else TmdbClient.popularShows(apiKey).filter { it.posterPath != null }
                    }

                    recommendedMovies = movieResult.getOrDefault(emptyList()).take(12)
                    recommendedShows = showResult.getOrDefault(emptyList()).take(12)

                    val errors = listOfNotNull(
                        movieResult.exceptionOrNull()?.message?.let { "Filmes: $it" },
                        showResult.exceptionOrNull()?.message?.let { "Séries: $it" }
                    )
                    recommendationsError = when {
                        recommendedMovies.isEmpty() && recommendedShows.isEmpty() ->
                            errors.joinToString(" • ").ifBlank { "O TMDB não retornou títulos com pôsteres." }
                        else -> errors.joinToString(" • ")
                    }
                    recommendationsLoading = false
                } else {
                    recommendedMovies = emptyList()
                    recommendedShows = emptyList()
                    recommendationsError = ""
                    recommendationsLoading = false
                }
            }
            LaunchedEffect(page, exploreType, exploreCategory, exploreGenre, apiKey) {
                if (page == "Explorar") {
                    explorePage = 1
                    exploreResults = emptyList()
                    exploreError = ""
                    exploreCanLoadMore = true
                    if (apiKey.isBlank()) exploreError = "Configure sua credencial TMDB em Mais > Config."
                    else {
                        exploreLoading = true
                        val genreId = if (exploreGenre == "Todos") null else exploreGenres(exploreType).firstOrNull { it.first == exploreGenre }?.second
                        runCatching { TmdbClient.catalog(exploreType, exploreCategory, 1, genreId, apiKey).filter { it.posterPath != null } }
                            .onSuccess { items -> exploreResults = items; exploreCanLoadMore = items.size >= 20 }
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
            val visible = when (sortMode) {
                "A–Z" -> visibleUnsorted.sortedBy { it.title.lowercase(java.util.Locale.ROOT) }
                "Ano (mais recente)" -> visibleUnsorted.sortedByDescending { it.year.toIntOrNull() ?: 0 }
                "Inclusão (mais recente)" -> visibleUnsorted.sortedByDescending { it.id }
                "Inclusão (mais antiga)" -> visibleUnsorted.sortedBy { it.id }
                else -> visibleUnsorted.sortedWith(compareBy<MediaItem> { if (it.sortOrder > 0) it.sortOrder else it.id }.thenBy { it.id })
            }
            MaterialTheme(colorScheme = darkColorScheme(primary = Purple, background = Night, surface = Panel, onBackground = TextMain, onSurface = TextMain)) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = Night) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        Spacer(Modifier.height(18.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            androidx.compose.foundation.Image(
                                painter = painterResource(id = R.drawable.cineverse_logo),
                                contentDescription = "Logo oficial CineVerso",
                                modifier = Modifier.size(54.dp)
                            )
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Cine", fontSize = 25.sp, color = TextMain, fontWeight = FontWeight.ExtraBold)
                                    Text("Verse", fontSize = 25.sp, color = Purple, fontWeight = FontWeight.ExtraBold)
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(when(page) {
                            "Início" -> "Descubra filmes e séries"
                            "Buscar" -> "Encontre sua próxima história."
                            "Minha lista" -> "Sua coleção, do seu jeito."
                            "Estatísticas" -> "Seu perfil de espectador."
                            "Calendário" -> "Sua jornada de exibição."
                            "Roleta" -> "Deixe a escolha com o acaso."
                            else -> "Preferências do aplicativo."
                        }, fontSize = 23.sp, color = TextMain, fontWeight = FontWeight.ExtraBold)
                        Text("FILMES  •  SÉRIES  •  SUA COLEÇÃO", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            listOf("Início", "Explorar", "Buscar", "Minha lista").forEach { tab ->
                                FilterChip(selected = page == tab, onClick = { navigateTo(tab) }, label = { Text(tab, fontSize = 11.sp) })
                            }
                            Box {
                                var menu by remember { mutableStateOf(false) }
                                FilterChip(selected = page in listOf("Estatísticas", "Calendário", "Config."), onClick = { menu = true }, label = { Text("Mais ▾", fontSize = 11.sp) })
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    listOf("Estatísticas", "Calendário", "Roleta", "Config.").forEach { item ->
                                        DropdownMenuItem(text = { Text(item) }, onClick = { navigateTo(item); menu = false })
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        when (page) {
                            "Explorar" -> {
                                Column(Modifier.weight(1f)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        FilterChip(selected = exploreType == "movie", onClick = { exploreType = "movie" }, label = { Text("Filmes") })
                                        FilterChip(selected = exploreType == "tv", onClick = { exploreType = "tv" }, label = { Text("Séries") })
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text("DESCUBRA MAIS", color = Purple, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, letterSpacing = 1.sp)
                                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf("Em alta" to "trending", "Populares" to "popular", "Mais bem avaliados" to "top_rated", "Recentes" to "recent", "Em breve" to "upcoming").forEach { (label, value) ->
                                            FilterChip(selected = exploreCategory == value, onClick = { exploreCategory = value }, label = { Text(label, fontSize = 11.sp) })
                                        }
                                    }
                                    var exploreGenreMenu by remember { mutableStateOf(false) }
                                    Box {
                                        OutlinedButton(onClick = { exploreGenreMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("Gênero: $exploreGenre  ▾") }
                                        DropdownMenu(expanded = exploreGenreMenu, onDismissRequest = { exploreGenreMenu = false }) {
                                            exploreGenres(exploreType).forEach { (name, _) -> DropdownMenuItem(text = { Text(name) }, onClick = { exploreGenre = name; exploreGenreMenu = false }) }
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(when (exploreCategory) { "trending" -> "Em alta nesta semana"; "popular" -> "Os títulos mais populares"; "top_rated" -> "Os mais bem avaliados"; "recent" -> if (exploreType == "movie") "Em cartaz" else "Exibidos hoje"; else -> if (exploreType == "movie") "Próximos lançamentos" else "No ar" }, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    if (exploreLoading && exploreResults.isEmpty()) {
                                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) }
                                    } else if (exploreError.isNotBlank() && exploreResults.isEmpty()) {
                                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(exploreError, color = Muted) }
                                    } else {
                                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                                            items(exploreResults, key = { "explore-${it.type}-${it.id}" }) { item ->
                                                val alreadySaved = all.any { it.tmdbId == item.id && it.mediaType == item.type }
                                                Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    AsyncImage(model = "https://image.tmdb.org/t/p/w185${item.posterPath}", contentDescription = item.title, modifier = Modifier.width(66.dp).height(96.dp), contentScale = ContentScale.Crop)
                                                    Spacer(Modifier.width(10.dp))
                                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        Text(item.title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2)
                                                        Text(item.year.ifBlank { "Ano indisponível" }, color = Muted, fontSize = 11.sp)
                                                        if (item.overview.isNotBlank()) Text(item.overview, color = Muted, fontSize = 10.sp, maxLines = 3)
                                                        TextButton(onClick = { detailItem = item; detailReturnPage = "Explorar"; navigateTo("Detalhes"); if (item.type == "tv" && apiKey.isNotBlank()) lifecycleScope.launch { detailLoading = true; detailSeasons = runCatching { TmdbClient.seriesSeasons(item.id, apiKey) }.getOrDefault("[]"); detailLoading = false } }, contentPadding = PaddingValues(0.dp)) { Text("Ver detalhes", color = Purple, fontSize = 11.sp) }
                                                        Button(onClick = { lifecycleScope.launch { if (dao.findByTmdbId(item.id, item.type) == null) { var saved = item.toMediaItem(); if (item.type == "tv" && apiKey.isNotBlank()) { val imported = runCatching { TmdbClient.seriesSeasons(item.id, apiKey) }.getOrDefault("[]"); val seasons = JSONArray(imported); var episodes = 0; for (i in 0 until seasons.length()) episodes += seasons.optJSONObject(i)?.optInt("episodes", 0) ?: 0; saved = saved.copy(totalSeasons = (0 until seasons.length()).count { seasons.optJSONObject(it)?.optInt("number", 0) ?: 0 > 0 }, totalEpisodes = episodes, seasonProgress = imported) }; dao.insert(saved) } } }, enabled = !alreadySaved, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)) { Text(if (alreadySaved) "Na minha lista" else "+ Minha lista", fontSize = 10.sp) }
                                                    }
                                                }
                                            }
                                            if (exploreCanLoadMore) item {
                                                OutlinedButton(onClick = { if (!exploreLoading) lifecycleScope.launch { exploreLoading = true; val nextPage = explorePage + 1; val genreId = if (exploreGenre == "Todos") null else exploreGenres(exploreType).firstOrNull { it.first == exploreGenre }?.second; runCatching { TmdbClient.catalog(exploreType, exploreCategory, nextPage, genreId, apiKey).filter { it.posterPath != null } }.onSuccess { items -> val newItems = items.filter { candidate -> exploreResults.none { it.id == candidate.id && it.type == candidate.type } }; exploreResults = exploreResults + newItems; explorePage = nextPage; exploreCanLoadMore = items.size >= 20 }.onFailure { exploreError = it.message ?: "Falha ao carregar mais títulos." }; exploreLoading = false } }, modifier = Modifier.fillMaxWidth(), enabled = !exploreLoading) { if (exploreLoading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Carregar mais títulos") }
                                            }
                                            if (exploreError.isNotBlank()) item { Text(exploreError, color = Color(0xFFFF8A8A), fontSize = 11.sp) }
                                        }
                                    }
                                }
                            }
                            "Buscar" -> {
                                OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Nome do filme ou série") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(15.dp))
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
                                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                                    items(results, key = { "${it.type}-${it.id}" }) { item ->
                                        SearchCard(item, onOpen = {
                                            detailItem = item
                                            navigateTo("Detalhes")
                                            if (item.type == "tv" && apiKey.isNotBlank()) lifecycleScope.launch {
                                                detailLoading = true
                                                detailSeasons = runCatching { TmdbClient.seriesSeasons(item.id, apiKey) }.getOrDefault("[]")
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
                                                TextButton(onClick = { page = if (pageHistory.isNotEmpty()) pageHistory.removeAt(pageHistory.lastIndex) else detailReturnPage }) { Text("← Voltar") }
                                                Text("DETALHES", color = Purple, fontWeight = FontWeight.Bold)
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                                                if (!selectedTitle.posterPath.isNullOrBlank()) AsyncImage(model = "https://image.tmdb.org/t/p/w342${selectedTitle.posterPath}", contentDescription = selectedTitle.title, modifier = Modifier.width(112.dp).height(166.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(selectedTitle.title, color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                                    Text("${if (selectedTitle.type == "tv") "Série" else "Filme"} • ${selectedTitle.year}", color = Muted)
                                                    Spacer(Modifier.height(8.dp))
                                                    Text(selectedTitle.overview.ifBlank { "Sinopse indisponível." }, color = Muted, fontSize = 13.sp)
                                                }
                                            }
                                            Spacer(Modifier.height(12.dp))
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
                                            Text("ADICIONAR À MINHA LISTA", color = Purple, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            (if (selectedTitle.type == "tv") listOf("WATCHLIST" to "Quero assistir", "WATCHING" to "Tô assistindo", "WATCHED" to "Já assisti") else listOf("WATCHLIST" to "Quero assistir", "WATCHED" to "Já assisti")).forEach { (status, label) ->
                                                Button(onClick = {
                                                    lifecycleScope.launch {
                                                        val existing = dao.findByTmdbId(selectedTitle.id, selectedTitle.type)
                                                        var media = existing ?: selectedTitle.toMediaItem()
                                                        if (selectedTitle.type == "tv" && media.seasonProgress.isBlank() && apiKey.isNotBlank()) {
                                                            val imported = runCatching { TmdbClient.seriesSeasons(selectedTitle.id, apiKey) }.getOrDefault("[]")
                                                            val seasons = JSONArray(imported)
                                                            var episodes = 0
                                                            for (i in 0 until seasons.length()) episodes += seasons.optJSONObject(i)?.optInt("episodes", 0) ?: 0
                                                            media = media.copy(totalSeasons = (0 until seasons.length()).count { seasons.optJSONObject(it)?.optInt("number", 0) ?: 0 > 0 }, totalEpisodes = episodes, seasonProgress = imported)
                                                        }
                                                        dao.insert(media.copy(status = status, watchedAt = if (status == "WATCHED") System.currentTimeMillis() else media.watchedAt))
                                                        page = if (pageHistory.isNotEmpty()) pageHistory.removeAt(pageHistory.lastIndex) else detailReturnPage
                                                    }
                                                }, modifier = Modifier.fillMaxWidth()) { Text(label) }
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
                                                        var seasonMenu by remember(season.optInt("number")) { mutableStateOf(false) }
                                                        val seasonStatus = season.optString("status", "WATCHLIST")
                                                        val seasonLabel = when (seasonStatus) { "WATCHED" -> "JÁ ASSISTIDA"; "WATCHING" -> "EM ANDAMENTO"; else -> "QUERO ASSISTIR" }
                                                        val seasonAccent = when (seasonStatus) {
                                                            "WATCHED" -> Color(0xFF65D6A2)
                                                            "WATCHING" -> Color(0xFFC29AFF)
                                                            else -> Muted
                                                        }
                                                        val seasonBackground = when (seasonStatus) {
                                                            "WATCHED" -> Color(0xFF15352D)
                                                            "WATCHING" -> Color(0xFF2A2040)
                                                            else -> Panel
                                                        }
                                                        Box(Modifier.fillMaxWidth()) {
                                                            OutlinedButton(
                                                                onClick = { seasonMenu = true },
                                                                modifier = Modifier.fillMaxWidth(),
                                                                colors = ButtonDefaults.outlinedButtonColors(containerColor = seasonBackground)
                                                            ) {
                                                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                                                    Text(season.optString("name"), color = TextMain, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                                                    Text("${season.optInt("episodes")} episódios • ${season.optString("airDate")}", color = Muted, fontSize = 11.sp)
                                                                    Text(seasonLabel, color = seasonAccent, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.7.sp)
                                                                }
                                                            }
                                                            DropdownMenu(expanded = seasonMenu, onDismissRequest = { seasonMenu = false }) {
                                                                listOf("WATCHLIST" to "Quero assistir", "WATCHING" to "Tô assistindo", "WATCHED" to "Já assisti").forEach { (newStatus, label) ->
                                                                    DropdownMenuItem(text = { Text(label) }, onClick = {
                                                                        season.put("status", newStatus)
                                                                        detailSeasons = seasons.toString()
                                                                        seasonMenu = false
                                                                        lifecycleScope.launch {
                                                                            val existing = dao.findByTmdbId(selectedTitle.id, selectedTitle.type)
                                                                            val current = existing ?: selectedTitle.toMediaItem()
                                                                            val imported = JSONArray(detailSeasons)
                                                                            val count = (0 until imported.length()).count { (imported.optJSONObject(it)?.optInt("number") ?: 0) > 0 }
                                                                            val episodes = (0 until imported.length()).sumOf { imported.optJSONObject(it)?.optInt("episodes", 0) ?: 0 }
                                                                            dao.insert(current.copy(totalSeasons = count, totalEpisodes = episodes, seasonProgress = detailSeasons))
                                                                        }
                                                                    })
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
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                    items(listOf("Quero assistir", "Tô assistindo", "Já assisti")) { statusOption ->
                                        FilterChip(
                                            selected = filter == statusOption,
                                            onClick = { filter = statusOption },
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
                                Text("${visible.size} títulos • Toque em um banner para ver os detalhes.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                                    items(visible.chunked(3), key = { row -> row.firstOrNull()?.id ?: 0L }) { rowItems ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                            rowItems.forEach { media ->
                                                CompactMediaCard(media, Modifier.weight(1f)) {
                                                    detailItem = TmdbResult(media.tmdbId ?: 0, media.title, media.mediaType, media.year, media.posterPath, media.overview, media.releaseDate)
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
                                            repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                                        }
                                    }
                                }
                            }
                            "Estatísticas" -> {
                                val watched = all.filter { it.status == "WATCHED" }
                                val movies = all.count { it.mediaType == "movie" }
                                val shows = all.count { it.mediaType == "tv" }
                                                                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
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
                                else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                Text("INTEGRAÇÃO TMDB", color = Purple, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("A chave fica salva neste aparelho e é usada para buscar títulos, sinopses e pôsteres.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                                Button(onClick = { showKey = true }, modifier = Modifier.fillMaxWidth()) { Text(if (apiKey.isBlank()) "Configurar chave TMDB" else "Alterar chave TMDB") }
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(onClick = {
                                    val json = JSONArray()
                                    all.forEach { m -> json.put(JSONObject().put("title", m.title).put("tmdbId", m.tmdbId).put("mediaType", m.mediaType).put("year", m.year).put("posterPath", m.posterPath).put("overview", m.overview).put("status", m.status).put("rating", m.rating).put("watchedAt", m.watchedAt).put("runtimeMinutes", m.runtimeMinutes).put("genres", m.genres).put("totalSeasons", m.totalSeasons).put("watchedEpisodes", m.watchedEpisodes).put("totalEpisodes", m.totalEpisodes).put("releaseDate", m.releaseDate)) }
                                    val send = Intent(Intent.ACTION_SEND).apply { type = "application/json"; putExtra(Intent.EXTRA_SUBJECT, "Backup CineVerso"); putExtra(Intent.EXTRA_TEXT, json.toString(2)) }
                                    startActivity(Intent.createChooser(send, "Exportar backup CineVerso"))
                                }, modifier = Modifier.fillMaxWidth()) { Text("Exportar backup JSON") }
                                Text("O backup contém os dados da coleção. Guarde o arquivo em local seguro.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                                Spacer(Modifier.weight(1f))
                            }
                            else -> {
                                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                Text("Continue de onde parou ou descubra algo novo.", color = Muted, fontSize = 13.sp)
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
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(Modifier.weight(1f)) { StatCard("Na coleção", all.size.toString(), "Títulos salvos") }
                                    Box(Modifier.weight(1f)) { StatCard("Assistindo", all.count { it.mediaType == "tv" && it.status == "WATCHING" }.toString(), "Séries em andamento") }
                                }
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
                                                    detailSeasons = runCatching { TmdbClient.seriesSeasons(item.id, apiKey) }.getOrDefault("[]")
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
                                                    detailSeasons = runCatching { TmdbClient.seriesSeasons(item.id, apiKey) }.getOrDefault("[]")
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
                    }
                }
                if (showRouletteDialog) {
                    val collectionOptions = all.filter { it.mediaType == rouletteType && it.status != "WATCHED" && it.tmdbId != null }
                        .map { media -> TmdbResult(media.tmdbId!!, media.title, media.mediaType, media.year, media.posterPath, media.overview, media.releaseDate) }
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
                                        Button(onClick = { rouletteType = "movie"; rouletteGenre = "Todos"; rouletteSuggestion = null }, modifier = Modifier.weight(1f), enabled = !rouletteSpinning) { Text("🎬 Filme") }
                                        OutlinedButton(onClick = { rouletteType = "tv"; rouletteGenre = "Todos"; rouletteSuggestion = null }, modifier = Modifier.weight(1f), enabled = !rouletteSpinning) { Text("📺 Série") }
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
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(190.dp)) {
                                        Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = rouletteWheelRotation.value }) {
                                            val colors = listOf(Purple, Color(0xFF5D35A5), Color(0xFFDBA7FF), Color(0xFF39205F), Color(0xFF8B4CE0), Color(0xFF241638), Color(0xFFC58BFF), Color(0xFF7040B8))
                                            val slice = 360f / colors.size
                                            colors.forEachIndexed { index, color ->
                                                drawArc(color = color, startAngle = index * slice, sweepAngle = slice - 2f, useCenter = true)
                                            }
                                            drawCircle(color = Night, radius = size.minDimension * .16f)
                                            drawCircle(color = Color.White.copy(alpha = .8f), radius = size.minDimension * .16f, style = Stroke(width = 3.dp.toPx()))
                                        }
                                        Text("▶", color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.Black)
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
                                        detailSeasons = runCatching { TmdbClient.seriesSeasons(picked.id, apiKey) }.getOrDefault("[]")
                                        detailLoading = false
                                    }
                                }) { Text("Ver detalhes") }
                            } else {
                                TextButton(enabled = !rouletteSpinning && (rouletteMode == "DISCOVER" || options.isNotEmpty()), onClick = {
                                    rouletteSpinning = true
                                    rouletteError = ""
                                    rouletteSuggestion = null
                                    lifecycleScope.launch {
                                        try {
                                            rouletteWheelRotation.animateTo(
                                                rouletteWheelRotation.value + 1800f + (360..900).random(),
                                                animationSpec = tween(durationMillis = 3600, easing = FastOutSlowInEasing)
                                            )
                                            val pool = if (rouletteMode == "LIST") options else {
                                                var found: List<TmdbResult> = emptyList()
                                                repeat(5) {
                                                    if (found.isEmpty()) {
                                                        found = TmdbClient.discoverRandom(rouletteType, selectedGenreId, apiKey)
                                                            .filter { candidate -> all.none { it.mediaType == candidate.type && it.tmdbId == candidate.id } }
                                                    }
                                                }
                                                found
                                            }
                                            if (pool.isEmpty()) {
                                                rouletteError = "Não encontrei títulos com esse filtro. Tente outro gênero."
                                            } else {
                                                rouletteSuggestion = pool.random()
                                            }
                                        } catch (error: Exception) {
                                            rouletteError = error.message ?: "Não foi possível consultar o catálogo. Confira sua conexão e a chave TMDB."
                                        } finally {
                                            rouletteSpinning = false
                                        }
                                    }
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


private fun exploreGenres(type: String): List<Pair<String, Int?>> = if (type == "tv") listOf(
    "Todos" to null, "Ação e aventura" to 10759, "Animação" to 16, "Comédia" to 35, "Crime" to 80,
    "Documentário" to 99, "Drama" to 18, "Família" to 10751, "Infantil" to 10762, "Mistério" to 9648,
    "Ficção científica e fantasia" to 10765, "Novela" to 10766, "Reality" to 10764, "Guerra e política" to 10768
) else listOf(
    "Todos" to null, "Ação" to 28, "Aventura" to 12, "Animação" to 16, "Comédia" to 35, "Crime" to 80,
    "Documentário" to 99, "Drama" to 18, "Família" to 10751, "Fantasia" to 14, "História" to 36,
    "Terror" to 27, "Mistério" to 9648, "Romance" to 10749, "Ficção científica" to 878, "Suspense" to 53
)

@Composable
private fun CompactMediaCard(media: MediaItem, modifier: Modifier = Modifier, onOpen: () -> Unit) {
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
        Text((item.year.ifBlank { "Em alta" }) + " • + Minha lista", color = Muted, fontSize = 10.sp)
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
            if (item.overview.isNotBlank()) Text(item.overview, color = Muted, fontSize = 11.sp, maxLines = 3)
        }
        Text("Detalhes", color = Purple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
