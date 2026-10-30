package br.com.minhamidia

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
            var apiKey by remember { mutableStateOf(prefs.getString("tmdb_key", "") ?: "") }
            var query by remember { mutableStateOf("") }
            var page by remember { mutableStateOf("Início") }
            var filter by remember { mutableStateOf("Quero assistir") }
            var categoryFilter by remember { mutableStateOf("FILMES") }
            var yearFilter by remember { mutableStateOf("Todos") }
            var sortMode by remember { mutableStateOf("Manual") }
            var results by remember { mutableStateOf(emptyList<TmdbResult>()) }
            var detailItem by remember { mutableStateOf<TmdbResult?>(null) }
            var detailSeasons by remember { mutableStateOf("[]") }
            var detailLoading by remember { mutableStateOf(false) }
            var recommendedMovies by remember { mutableStateOf(emptyList<TmdbResult>()) }
            var recommendedShows by remember { mutableStateOf(emptyList<TmdbResult>()) }
            var recommendationsLoading by remember { mutableStateOf(false) }
            var recommendationsError by remember { mutableStateOf("") }
            var message by remember { mutableStateOf("Sua próxima história começa aqui.") }
            var showKey by remember { mutableStateOf(false) }
            var busy by remember { mutableStateOf(false) }
            var roulettePick by remember { mutableStateOf<MediaItem?>(null) }
            var editingSeries by remember { mutableStateOf<MediaItem?>(null) }
            var seasonInput by remember { mutableStateOf("") }
            var totalEpisodesInput by remember { mutableStateOf("") }
            var watchedEpisodesInput by remember { mutableStateOf("") }
            var seasonProgressInput by remember { mutableStateOf("") }
            var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()) }
            val all by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
            LaunchedEffect(apiKey) {
                if (apiKey.isNotBlank()) {
                    recommendationsLoading = true
                    recommendationsError = ""
                    try {
                        val movies = runCatching { TmdbClient.trendingMovies(apiKey) }
                            .getOrElse { TmdbClient.popularMovies(apiKey) }
                        recommendedMovies = movies.filter { it.posterPath != null }.take(12)
                        val shows = runCatching { TmdbClient.trendingShows(apiKey) }
                            .getOrElse { TmdbClient.popularShows(apiKey) }
                        recommendedShows = shows.filter { it.posterPath != null }.take(12)
                        if (recommendedMovies.isEmpty() && recommendedShows.isEmpty()) {
                            recommendationsError = "O TMDB não retornou títulos para as recomendações."
                        }
                    } catch (e: Exception) {
                        recommendationsError = e.message ?: "Não foi possível carregar as recomendações."
                    } finally {
                        recommendationsLoading = false
                    }
                } else {
                    recommendedMovies = emptyList()
                    recommendedShows = emptyList()
                    recommendationsError = ""
                }
            }
            val availableYears = all.mapNotNull { it.year.toIntOrNull() }.distinct().sortedDescending()
            val visibleUnsorted = all.filter { media ->
                val statusMatches = when (filter) {
                    "Quero assistir" -> media.status == "WATCHLIST"
                    "Tô assistindo" -> media.status == "WATCHING"
                    "Já assisti" -> media.status == "WATCHED"
                    else -> false
                }
                val categoryMatches = when (categoryFilter) {
                    "FILMES" -> media.mediaType == "movie"
                    "SÉRIES" -> media.mediaType == "tv"
                    else -> true
                }
                statusMatches && (yearFilter == "Todos" || media.year == yearFilter) && categoryMatches
            }
            val visible = when (sortMode) {
                "A–Z" -> visibleUnsorted.sortedBy { it.title.lowercase(java.util.Locale.ROOT) }
                "Ano (mais recente)" -> visibleUnsorted.sortedByDescending { it.year.toIntOrNull() ?: 0 }
                "Inclusão (mais recente)" -> visibleUnsorted.sortedByDescending { it.id }
                "Inclusão (mais antiga)" -> visibleUnsorted.sortedBy { it.id }
                else -> visibleUnsorted.sortedWith(compareBy<MediaItem> { if (it.sortOrder > 0) it.sortOrder else it.id }.thenBy { it.id })
            }
            MaterialTheme(colorScheme = darkColorScheme(primary = Purple, background = Night, surface = Panel, onBackground = TextMain, onSurface = TextMain)) {
                Surface(Modifier.fillMaxSize(), color = Night) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 16.dp)) {
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
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            listOf("Início", "Buscar", "Minha lista").forEach { tab ->
                                FilterChip(selected = page == tab, onClick = { page = tab }, label = { Text(tab, fontSize = 11.sp) })
                            }
                            Box {
                                var menu by remember { mutableStateOf(false) }
                                FilterChip(selected = page in listOf("Estatísticas", "Calendário", "Roleta", "Config."), onClick = { menu = true }, label = { Text("Mais ▾", fontSize = 11.sp) })
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    listOf("Estatísticas", "Calendário", "Roleta", "Config.").forEach { item ->
                                        DropdownMenuItem(text = { Text(item) }, onClick = { page = item; menu = false })
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        when (page) {
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
                                            page = "Detalhes"
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
                                val item = detailItem
                                if (item != null) {
                                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                                        item {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                TextButton(onClick = { page = "Buscar" }) { Text("← Voltar") }
                                                Text("DETALHES", color = Purple, fontWeight = FontWeight.Bold)
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                                                if (!item.posterPath.isNullOrBlank()) AsyncImage(model = "https://image.tmdb.org/t/p/w342${item.posterPath}", contentDescription = item.title, modifier = Modifier.width(112.dp).height(166.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(item.title, color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                                    Text("${if (item.type == "tv") "Série" else "Filme"} • ${item.year}", color = Muted)
                                                    Spacer(Modifier.height(8.dp))
                                                    Text(item.overview.ifBlank { "Sinopse indisponível." }, color = Muted, fontSize = 13.sp)
                                                }
                                            }
                                            Spacer(Modifier.height(12.dp))
                                            Text("ADICIONAR À MINHA LISTA", color = Purple, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            listOf("WATCHLIST" to "Quero assistir", "WATCHING" to "Tô assistindo", "WATCHED" to "Já assisti").forEach { (status, label) ->
                                                Button(onClick = {
                                                    lifecycleScope.launch {
                                                        val existing = dao.findByTmdbId(item.id, item.type)
                                                        var media = existing ?: item.toMediaItem()
                                                        if (item.type == "tv" && media.seasonProgress.isBlank() && apiKey.isNotBlank()) {
                                                            val imported = runCatching { TmdbClient.seriesSeasons(item.id, apiKey) }.getOrDefault("[]")
                                                            val seasons = JSONArray(imported)
                                                            var episodes = 0
                                                            for (i in 0 until seasons.length()) episodes += seasons.optJSONObject(i)?.optInt("episodes", 0) ?: 0
                                                            media = media.copy(totalSeasons = (0 until seasons.length()).count { seasons.optJSONObject(it)?.optInt("number", 0) ?: 0 > 0 }, totalEpisodes = episodes, seasonProgress = imported)
                                                        }
                                                        dao.insert(media.copy(status = status, watchedAt = if (status == "WATCHED") System.currentTimeMillis() else media.watchedAt))
                                                        page = "Minha lista"
                                                    }
                                                }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                                            }
                                            if (item.type == "tv") {
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
                                                        val seasonLabel = when (seasonStatus) { "WATCHED" -> "Já assisti"; "WATCHING" -> "Tô assistindo"; else -> "Quero assistir" }
                                                        Box(Modifier.fillMaxWidth()) {
                                                            OutlinedButton(onClick = { seasonMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                                                Column(Modifier.fillMaxWidth()) {
                                                                    Text(season.optString("name"), color = TextMain, fontWeight = FontWeight.SemiBold)
                                                                    Text("${season.optInt("episodes")} episódios • ${season.optString("airDate")} • $seasonLabel", color = Muted, fontSize = 11.sp)
                                                                }
                                                            }
                                                            DropdownMenu(expanded = seasonMenu, onDismissRequest = { seasonMenu = false }) {
                                                                listOf("WATCHLIST" to "Quero assistir", "WATCHING" to "Tô assistindo", "WATCHED" to "Já assisti").forEach { (newStatus, label) ->
                                                                    DropdownMenuItem(text = { Text(label) }, onClick = {
                                                                        season.put("status", newStatus)
                                                                        detailSeasons = seasons.toString()
                                                                        seasonMenu = false
                                                                        lifecycleScope.launch {
                                                                            val existing = dao.findByTmdbId(item.id, item.type)
                                                                            val current = existing ?: item.toMediaItem()
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
                                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                                    listOf("Quero assistir", "Tô assistindo", "Já assisti").forEach { item ->
                                        FilterChip(selected = filter == item, onClick = { filter = item }, label = { Text(item, fontSize = 10.sp) })
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
                                Text("${visible.size} títulos • No modo Manual, use ↑ e ↓ para agrupar franquias.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                                    items(visible, key = { it.id }) { media ->
                                        MediaCard(media, favorites.contains(media.id.toString()),
                                            showStatusActions = false,
                                            onFavorite = {
                                                val next = favorites.toMutableSet()
                                                if (!next.add(media.id.toString())) next.remove(media.id.toString())
                                                favorites = next
                                                prefs.edit().putStringSet("favorites", next).apply()
                                            },
                                            onStatus = { status -> lifecycleScope.launch { dao.update(media.copy(status = status, watchedAt = if (status == "WATCHED") System.currentTimeMillis() else media.watchedAt)) } },
                                            
                                            onProgress = { delta -> lifecycleScope.launch { dao.update(media.copy(watchedEpisodes = (media.watchedEpisodes + delta).coerceIn(0, if (media.totalEpisodes > 0) media.totalEpisodes else 9999))) } },
                                            onEditProgress = { editingSeries = media; seasonInput = media.totalSeasons.toString(); totalEpisodesInput = media.totalEpisodes.toString(); watchedEpisodesInput = media.watchedEpisodes.toString() },
                                            onDelete = { lifecycleScope.launch { dao.delete(media) } })
                                        if (sortMode == "Manual") {
                                            val position = visible.indexOfFirst { it.id == media.id }
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                                TextButton(enabled = position > 0, onClick = {
                                                    lifecycleScope.launch {
                                                        val ordered = all.sortedWith(compareBy<MediaItem> { if (it.sortOrder > 0) it.sortOrder else it.id }.thenBy { it.id }).toMutableList()
                                                        val a = ordered.indexOfFirst { it.id == media.id }
                                                        val b = ordered.indexOfFirst { it.id == visible[position - 1].id }
                                                        if (a >= 0 && b >= 0) {
                                                            val temp = ordered[a]; ordered[a] = ordered[b]; ordered[b] = temp
                                                            ordered.forEachIndexed { i, item -> dao.update(item.copy(sortOrder = i.toLong() + 1L)) }
                                                        }
                                                    }
                                                }) { Text("↑ Subir") }
                                                TextButton(enabled = position < visible.lastIndex, onClick = {
                                                    lifecycleScope.launch {
                                                        val ordered = all.sortedWith(compareBy<MediaItem> { if (it.sortOrder > 0) it.sortOrder else it.id }.thenBy { it.id }).toMutableList()
                                                        val a = ordered.indexOfFirst { it.id == media.id }
                                                        val b = ordered.indexOfFirst { it.id == visible[position + 1].id }
                                                        if (a >= 0 && b >= 0) {
                                                            val temp = ordered[a]; ordered[a] = ordered[b]; ordered[b] = temp
                                                            ordered.forEachIndexed { i, item -> dao.update(item.copy(sortOrder = i.toLong() + 1L)) }
                                                        }
                                                    }
                                                }) { Text("↓ Descer") }
                                            }
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
                                        TextButton(onClick = { page = "Buscar" }) { Text("Adicionar mais títulos") }
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
                                Text("Continue de onde parou ou descubra algo novo.", color = Muted, fontSize = 13.sp)
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(Modifier.weight(1f)) { StatCard("Na coleção", all.size.toString(), "Títulos salvos") }
                                    Box(Modifier.weight(1f)) { StatCard("Assistindo", all.count { it.status == "WATCHING" }.toString(), "Em andamento") }
                                }
                                Spacer(Modifier.height(14.dp))
                                Text("EM ALTA • FILMES", color = Purple, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                if (recommendedMovies.isNotEmpty()) {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        items(recommendedMovies, key = { "movie-" + it.id }) { item ->
                                            RecommendationCard(item) {
                                                detailItem = item
                                                page = "Detalhes"
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
                                                page = "Detalhes"
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
                                Text("ACESSO RÁPIDO", color = Purple, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { page = "Buscar" }, modifier = Modifier.weight(1f)) { Text("Buscar títulos") }
                                    OutlinedButton(onClick = { page = "Roleta" }, modifier = Modifier.weight(1f)) { Text("Roleta") }
                                }
                                Text("ADICIONADOS RECENTEMENTE", color = Purple, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                                if (all.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Sua coleção está vazia. Pesquise um filme ou série para começar.", color = Muted) }
                                else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                                    items(all.takeLast(12).reversed(), key = { it.id }) { media ->
                                        MediaCard(media, favorites.contains(media.id.toString()),
                                            onFavorite = { val next = favorites.toMutableSet(); if (!next.add(media.id.toString())) next.remove(media.id.toString()); favorites = next; prefs.edit().putStringSet("favorites", next).apply() },
                                            onStatus = { status -> lifecycleScope.launch { dao.update(media.copy(status = status, watchedAt = if (status == "WATCHED") System.currentTimeMillis() else media.watchedAt)) } },
                                            
                                            onProgress = { delta -> lifecycleScope.launch { dao.update(media.copy(watchedEpisodes = (media.watchedEpisodes + delta).coerceIn(0, if (media.totalEpisodes > 0) media.totalEpisodes else 9999))) } },
                                            onEditProgress = { editingSeries = media; seasonInput = media.totalSeasons.toString(); totalEpisodesInput = media.totalEpisodes.toString(); watchedEpisodesInput = media.watchedEpisodes.toString() },
                                            onDelete = { lifecycleScope.launch { dao.delete(media) } })
                                    }
                                }
                            }
                        }
                    }
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
            val statusLabel = when (media.status) {
                "WATCHLIST" -> "QUERO ASSISTIR"
                "WATCHING" -> "TÔ ASSISTINDO"
                "WATCHED" -> "JÁ ASSISTI"
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
                Button(onClick = { onStatus("WATCHING") }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 7.dp)) { Text("Tô assistindo", fontSize = 10.sp) }
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
