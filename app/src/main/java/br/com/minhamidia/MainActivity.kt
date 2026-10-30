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
            var filter by remember { mutableStateOf("Todos") }
            var results by remember { mutableStateOf(emptyList<TmdbResult>()) }
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
            val visible = all.filter { media ->
                when (filter) {
                    "Filmes" -> media.mediaType == "movie"
                    "Séries" -> media.mediaType == "tv"
                    "Favoritos" -> favorites.contains(media.id.toString())
                    "Quero assistir" -> media.status == "WATCHLIST"
                    "Assistindo" -> media.status == "WATCHING"
                    "Assistidos" -> media.status == "WATCHED"
                    else -> true
                }
            }
            MaterialTheme(colorScheme = darkColorScheme(primary = Purple, background = Night, surface = Panel, onBackground = TextMain, onSurface = TextMain)) {
                Surface(Modifier.fillMaxSize(), color = Night) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        Spacer(Modifier.height(18.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            androidx.compose.foundation.Image(
                                painter = painterResource(id = R.drawable.cineverse_logo),
                                contentDescription = "Logo oficial CineVerse",
                                modifier = Modifier.size(54.dp)
                            )
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Cine", fontSize = 25.sp, color = TextMain, fontWeight = FontWeight.ExtraBold)
                                    Text("Verse", fontSize = 25.sp, color = Purple, fontWeight = FontWeight.ExtraBold)
                                }
                                Text("Seu universo em cada tela.", fontSize = 11.sp, color = Muted)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(when(page) {
                            "Início" -> "Seu universo em cada tela."
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
                                        SearchCard(item, onAdd = {
                                            lifecycleScope.launch {
                                                val existing = dao.findByTmdbId(item.id, item.type)
                                                if (existing == null) { dao.insert(item.toMediaItem()); message = "Adicionado à sua coleção." }
                                                else message = "Esse título já está na sua coleção."
                                                results = results.filterNot { it.id == item.id && it.type == item.type }
                                            }
                                        })
                                    }
                                }
                            }
                            "Minha lista" -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                                    listOf("Todos", "Quero assistir", "Assistindo", "Assistidos", "Filmes", "Séries", "Favoritos").forEach { item ->
                                        FilterChip(selected = filter == item, onClick = { filter = item }, label = { Text(item, fontSize = 10.sp) })
                                    }
                                }
                                Text("${visible.size} títulos", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                                    items(visible, key = { it.id }) { media ->
                                        MediaCard(media, favorites.contains(media.id.toString()),
                                            onFavorite = {
                                                val next = favorites.toMutableSet()
                                                if (!next.add(media.id.toString())) next.remove(media.id.toString())
                                                favorites = next
                                                prefs.edit().putStringSet("favorites", next).apply()
                                            },
                                            onStatus = { status -> lifecycleScope.launch { dao.update(media.copy(status = status, watchedAt = if (status == "WATCHED") System.currentTimeMillis() else media.watchedAt)) } },
                                            onRating = { rating -> lifecycleScope.launch { dao.update(media.copy(rating = rating)) } },
                                            onProgress = { delta -> lifecycleScope.launch { dao.update(media.copy(watchedEpisodes = (media.watchedEpisodes + delta).coerceIn(0, if (media.totalEpisodes > 0) media.totalEpisodes else 9999))) } },
                                            onEditProgress = { editingSeries = media; seasonInput = media.totalSeasons.toString(); totalEpisodesInput = media.totalEpisodes.toString(); watchedEpisodesInput = media.watchedEpisodes.toString() },
                                            onDelete = { lifecycleScope.launch { dao.delete(media) } })
                                    }
                                }
                            }
                            "Estatísticas" -> {
                                val watched = all.filter { it.status == "WATCHED" }
                                val movies = all.count { it.mediaType == "movie" }
                                val shows = all.count { it.mediaType == "tv" }
                                val rated = all.filter { it.rating > 0 }
                                val avg = if (rated.isEmpty()) "—" else String.format(java.util.Locale.getDefault(), "%.1f/5", rated.map { it.rating }.average())
                                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                                    item { StatCard("Títulos na coleção", all.size.toString(), "Todos os filmes e séries salvos") }
                                    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Box(Modifier.weight(1f)) { StatCard("Filmes", movies.toString(), "Na coleção") }; Box(Modifier.weight(1f)) { StatCard("Séries", shows.toString(), "Na coleção") } } }
                                    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Box(Modifier.weight(1f)) { StatCard("Concluídos", watched.size.toString(), "Marcados como assistidos") }; Box(Modifier.weight(1f)) { StatCard("Nota média", avg, "Entre os avaliados") } } }
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
                                    val send = Intent(Intent.ACTION_SEND).apply { type = "application/json"; putExtra(Intent.EXTRA_SUBJECT, "Backup CineVerse"); putExtra(Intent.EXTRA_TEXT, json.toString(2)) }
                                    startActivity(Intent.createChooser(send, "Exportar backup CineVerse"))
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
                                                lifecycleScope.launch {
                                                    if (dao.findByTmdbId(item.id, item.type) == null) dao.insert(item.toMediaItem())
                                                    page = "Minha lista"
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
                                                lifecycleScope.launch {
                                                    if (dao.findByTmdbId(item.id, item.type) == null) dao.insert(item.toMediaItem())
                                                    page = "Minha lista"
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
                                            onRating = { rating -> lifecycleScope.launch { dao.update(media.copy(rating = rating)) } },
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
private fun RecommendationCard(item: TmdbResult, onAdd: () -> Unit) {
    Column(Modifier.width(116.dp).clickable(onClick = onAdd), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (!item.posterPath.isNullOrBlank()) {
            AsyncImage(model = "https://image.tmdb.org/t/p/w342" + item.posterPath, contentDescription = item.title, modifier = Modifier.fillMaxWidth().height(166.dp))
        } else {
            Box(Modifier.fillMaxWidth().height(166.dp).background(Panel, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Text("CineVerse", color = Purple, fontSize = 12.sp)
            }
        }
        Text(item.title, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 2)
        Text((item.year.ifBlank { "Em alta" }) + " • + Minha lista", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun SearchCard(item: TmdbResult, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!item.posterPath.isNullOrBlank()) AsyncImage(model = "https://image.tmdb.org/t/p/w185${item.posterPath}", contentDescription = item.title, modifier = Modifier.width(58.dp).height(84.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, color = TextMain, fontWeight = FontWeight.Bold)
            Text("${if (item.type == "tv") "Série" else "Filme"} • ${item.year.ifBlank { "Ano indisponível" }}", color = Muted, fontSize = 12.sp)
            if (item.overview.isNotBlank()) Text(item.overview, color = Muted, fontSize = 11.sp, maxLines = 3)
        }
        IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Adicionar à coleção", tint = Purple) }
    }
}

@Composable
private fun MediaCard(media: MediaItem, favorite: Boolean, onFavorite: () -> Unit, onStatus: (String) -> Unit, onRating: (Int) -> Unit, onProgress: (Int) -> Unit, onEditProgress: () -> Unit, onDelete: () -> Unit) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Nota", color = Muted, fontSize = 11.sp)
            (1..5).forEach { n ->
                Text(if (media.rating >= n) "★" else "☆", color = if (media.rating >= n) Purple else Muted, modifier = Modifier.clickable { onRating(n) }.padding(horizontal = 2.dp), fontSize = 19.sp)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Excluir", color = Muted, fontSize = 11.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { onStatus("WATCHLIST") }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 7.dp)) { Text("Quero ver", fontSize = 10.sp) }
            Button(onClick = { onStatus("WATCHING") }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 7.dp)) { Text("Assistindo", fontSize = 10.sp) }
            Button(onClick = { onStatus("WATCHED") }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 7.dp)) { Text("Assistido", fontSize = 10.sp) }
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
