package br.com.minhamidia

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import br.com.minhamidia.data.AppDatabase
import br.com.minhamidia.data.MediaDao
import br.com.minhamidia.data.MediaItem
import br.com.minhamidia.data.TmdbClient
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

private val Ink = Color(0xFF09070D)
private val Panel = Color(0xFF17121F)
private val Panel2 = Color(0xFF21182D)
private val Purple = Color(0xFFAD70FF)
private val Lavender = Color(0xFFD8BFFF)
private val Muted = Color(0xFFAAA0B7)
private val White = Color(0xFFF7F2FC)

class MainActivity : ComponentActivity() {
    private lateinit var dao: MediaDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dao = AppDatabase.get(this).mediaDao()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Ink,
                    surface = Panel,
                    primary = Purple,
                    secondary = Lavender,
                    onBackground = White,
                    onSurface = White
                )
            ) {
                CineVerseApp(dao = dao, context = this)
            }
        }
    }
}

private enum class Screen(val label: String) {
    HOME("Início"), WATCHLIST("Minha lista"), SERIES("Séries"), STATS("Estatísticas"), CALENDAR("Lançamentos"), SETTINGS("Ajustes")
}

@Composable
private fun CineVerseApp(dao: MediaDao, context: Context) {
    val all by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    var screen by remember { mutableStateOf(Screen.HOME) }
    var searchText by remember { mutableStateOf("") }
    var tmdbResults by remember { mutableStateOf(emptyList<br.com.minhamidia.data.TmdbResult>()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var roulettePick by remember { mutableStateOf<MediaItem?>(null) }
    var apiKey by remember { mutableStateOf(context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("tmdb_key", "") ?: "") }
    var keyInput by remember { mutableStateOf(apiKey) }
    val scope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val json = JSONObject()
                    val array = JSONArray()
                    all.forEach { m ->
                        array.put(JSONObject().apply {
                            put("tmdbId", m.tmdbId ?: JSONObject.NULL)
                            put("title", m.title); put("mediaType", m.mediaType); put("year", m.year)
                            put("posterPath", m.posterPath ?: JSONObject.NULL); put("overview", m.overview)
                            put("status", m.status); put("rating", m.rating); put("watchedAt", m.watchedAt ?: JSONObject.NULL)
                            put("runtimeMinutes", m.runtimeMinutes); put("genres", m.genres)
                            put("totalSeasons", m.totalSeasons); put("watchedEpisodes", m.watchedEpisodes)
                            put("totalEpisodes", m.totalEpisodes); put("releaseDate", m.releaseDate)
                        })
                    }
                    json.put("format", "CineVerseBackup"); json.put("version", 1); json.put("items", array)
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json.toString(2)) }
                    message = "Backup exportado com sucesso."
                } catch (e: Exception) { message = "Não foi possível exportar: ${e.message}" }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Arquivo vazio")
                val json = JSONObject(text)
                require(json.optString("format") in setOf("CineVerseBackup", "MinhaMidiaBackup")) { "Este arquivo não é um backup compatível com CineVerse." }
                val array = json.getJSONArray("items")
                val imported = buildList {
                    for (i in 0 until array.length()) {
                        val o = array.getJSONObject(i)
                        add(MediaItem(
                            tmdbId = if (o.isNull("tmdbId")) null else o.optInt("tmdbId"),
                            title = o.optString("title"), mediaType = o.optString("mediaType", "movie"),
                            year = o.optString("year"), posterPath = if (o.isNull("posterPath")) null else o.optString("posterPath"),
                            overview = o.optString("overview"), status = o.optString("status", "WATCHLIST"),
                            rating = o.optInt("rating"), watchedAt = if (o.isNull("watchedAt")) null else o.optLong("watchedAt"),
                            runtimeMinutes = o.optInt("runtimeMinutes"), genres = o.optString("genres"),
                            totalSeasons = o.optInt("totalSeasons"), watchedEpisodes = o.optInt("watchedEpisodes"),
                            totalEpisodes = o.optInt("totalEpisodes"), releaseDate = o.optString("releaseDate")
                        ))
                    }
                }
                dao.insertAll(imported)
                message = "Backup restaurado. Itens importados: ${imported.size}."
            } catch (e: Exception) { message = "Falha ao restaurar: ${e.message}" }
        }
    }

    Scaffold(
        containerColor = Ink,
        bottomBar = {
            NavigationBar(containerColor = Panel, contentColor = White) {
                val nav = listOf(Screen.HOME, Screen.WATCHLIST, Screen.SERIES, Screen.STATS, Screen.CALENDAR)
                nav.forEach { item ->
                    NavigationBarItem(
                        selected = screen == item,
                        onClick = { screen = item },
                        icon = {
                            Icon(
                                when (item) {
                                    Screen.HOME -> Icons.Default.Home
                                    Screen.WATCHLIST -> Icons.Default.Bookmarks
                                    Screen.SERIES -> Icons.Default.LiveTv
                                    Screen.STATS -> Icons.Default.QueryStats
                                    else -> Icons.Default.CalendarMonth
                                }, contentDescription = item.label
                            )
                        },
                        label = { Text(item.label, fontSize = 10.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Lavender,
                            selectedTextColor = Lavender,
                            indicatorColor = Panel2,
                            unselectedIconColor = Muted,
                            unselectedTextColor = Muted
                        )
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(Ink)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Cine", color = White, fontSize = 23.sp, fontWeight = FontWeight.Black, letterSpacing = 0.2.sp)
                    Text("Verse", color = Purple, fontSize = 23.sp, fontWeight = FontWeight.Black, letterSpacing = 0.2.sp)
                }
                IconButton(onClick = { screen = Screen.SETTINGS }) {
                    Icon(Icons.Default.Settings, contentDescription = "Ajustes", tint = Lavender)
                }
            }
            when (screen) {
                Screen.HOME -> HomeScreen(
                    items = all,
                    onSearch = { screen = Screen.WATCHLIST },
                    onSelect = { item -> scope.launch { dao.update(item.copy(status = if (item.status == "WATCHED") "WATCHLIST" else "WATCHED", watchedAt = if (item.status == "WATCHED") null else System.currentTimeMillis())) } },
                    onRoulette = {
                        val pool = all.filter { it.status == "WATCHLIST" && it.tmdbId != null }
                        roulettePick = pool.randomOrNull()
                        message = roulettePick?.let { "A roleta escolheu: ${it.title}" } ?: "Adicione filmes pela busca do TMDB para usar a roleta."
                    },
                    roulettePick = roulettePick
                )
                Screen.WATCHLIST -> WatchlistScreen(
                    items = all, query = searchText, onQuery = { searchText = it },
                    results = tmdbResults, loading = loading, apiKeySet = apiKey.isNotBlank(),
                    message = message,
                    onSearch = {
                        if (apiKey.isBlank()) { message = "Configure sua chave TMDB em Ajustes."; return@WatchlistScreen }
                        if (searchText.isBlank()) { message = "Digite o nome de um filme ou série."; return@WatchlistScreen }
                        scope.launch {
                            loading = true; message = null
                            try { tmdbResults = TmdbClient.search(searchText, apiKey) }
                            catch (e: Exception) { message = e.message ?: "Erro ao pesquisar." }
                            finally { loading = false }
                        }
                    },
                    onAdd = { result ->
                        scope.launch {
                            val existing = dao.findByTmdbId(result.id, result.type)
                            if (existing == null) dao.insert(result.toMediaItem())
                            else message = "Esse título já está na sua coleção."
                            tmdbResults = emptyList()
                            message = if (existing == null) "Adicionado à sua coleção." else message
                        }
                    },
                    onToggle = { item -> scope.launch { dao.update(item.copy(status = if (item.status == "WATCHED") "WATCHLIST" else "WATCHED", watchedAt = if (item.status == "WATCHED") null else System.currentTimeMillis())) } },
                    onDelete = { item -> scope.launch { dao.delete(item) } }
                )
                Screen.SERIES -> SeriesScreen(
                    items = all.filter { it.mediaType == "tv" },
                    onEpisode = { item ->
                        scope.launch {
                            val next = (item.watchedEpisodes + 1).coerceAtMost(item.totalEpisodes.takeIf { it > 0 } ?: Int.MAX_VALUE)
                            dao.update(item.copy(watchedEpisodes = next, status = "WATCHING"))
                        }
                    }
                )
                Screen.STATS -> StatsScreen(all)
                Screen.CALENDAR -> CalendarScreen(all)
                Screen.SETTINGS -> SettingsScreen(
                    apiKey = keyInput, onKeyChange = { keyInput = it },
                    onSaveKey = {
                        apiKey = keyInput.trim()
                        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("tmdb_key", apiKey).apply()
                        message = "Configuração salva neste aparelho."
                    },
                    onExport = { exportLauncher.launch("cineverse-backup.json") },
                    onImport = { importLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) }
                )
            }
        }
    }
    message?.let { msg ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("CineVerse") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            containerColor = Panel,
            titleContentColor = White,
            textContentColor = Muted
        )
    }
}

@Composable
private fun HomeScreen(
    items: List<MediaItem>, onSearch: () -> Unit, onSelect: (MediaItem) -> Unit,
    onRoulette: () -> Unit, roulettePick: MediaItem?
) {
    val watched = items.count { it.status == "WATCHED" }
    val pending = items.count { it.status == "WATCHLIST" }
    val series = items.count { it.mediaType == "tv" }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(190.dp).clip(RoundedCornerShape(24.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF3B2059), Color(0xFF17101F), Color(0xFF0A080E))))
                    .clickable { onSearch() }.padding(20.dp),
                contentAlignment = Alignment.BottomStart
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("SUA PRÓXIMA\nSESSÃO COMEÇA AQUI", color = White, fontSize = 23.sp, lineHeight = 27.sp, fontWeight = FontWeight.Black)
                    Text("Organize sua coleção. Escolha sua próxima história.", color = Lavender, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onSearch, colors = ButtonDefaults.buttonColors(containerColor = Purple, contentColor = Ink), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                            Icon(Icons.Default.Search, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("Encontrar títulos")
                        }
                        OutlinedButton(onClick = onRoulette, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                            Icon(Icons.Default.Casino, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("Roleta")
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("$pending", "Na lista", Modifier.weight(1f))
                StatTile("$watched", "Assistidos", Modifier.weight(1f))
                StatTile("$series", "Séries", Modifier.weight(1f))
            }
        }
        if (roulettePick != null) {
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = Panel2)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Poster(roulettePick, Modifier.width(54.dp).height(78.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("ESCOLHA DA ROLETA", color = Lavender, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(roulettePick.title, color = White, fontWeight = FontWeight.Bold)
                            Text("Uma opção para sua próxima sessão", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        item { SectionTitle("Continue organizando", "Seus títulos recentes") }
        if (items.isEmpty()) {
            item { EmptyState("Sua coleção está vazia", "Use a busca para adicionar seu primeiro filme ou série.") }
        } else {
            items(items.take(12), key = { it.id }) { item ->
                MediaRow(item = item, onClick = { onSelect(item) }, trailing = {
                    IconButton(onClick = { onSelect(item) }) {
                        Icon(if (item.status == "WATCHED") Icons.Default.CheckCircle else Icons.Default.CheckCircleOutline, null, tint = if (item.status == "WATCHED") Purple else Muted)
                    }
                })
            }
        }
    }
}

@Composable
private fun WatchlistScreen(
    items: List<MediaItem>, query: String, onQuery: (String) -> Unit,
    results: List<br.com.minhamidia.data.TmdbResult>, loading: Boolean, apiKeySet: Boolean,
    message: String?, onSearch: () -> Unit, onAdd: (br.com.minhamidia.data.TmdbResult) -> Unit,
    onToggle: (MediaItem) -> Unit, onDelete: (MediaItem) -> Unit
) {
    var filter by remember { mutableStateOf("TODOS") }
    val filtered = items.filter {
        (filter == "TODOS" || it.status == filter) &&
            (query.isBlank() || it.title.contains(query, ignoreCase = true))
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = onQuery, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Buscar na coleção ou no TMDB…", color = Muted) },
            leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Purple, unfocusedBorderColor = Panel2,
                focusedTextColor = White, unfocusedTextColor = White,
                focusedContainerColor = Panel, unfocusedContainerColor = Panel
            )
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("TODOS", "WATCHLIST", "WATCHED", "WATCHING").forEach { status ->
                FilterChip(
                    selected = filter == status, onClick = { filter = status },
                    label = { Text(when(status) { "TODOS" -> "Todos"; "WATCHLIST" -> "Quero ver"; "WATCHED" -> "Assistidos"; else -> "Em andamento" }) }
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Minha coleção", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = onSearch, enabled = apiKeySet, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Icon(Icons.Default.TravelExplore, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Buscar online")
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = Purple)
        if (results.isNotEmpty()) {
            Text("Resultados TMDB — toque para adicionar", color = Lavender, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(results, key = { "${it.type}-${it.id}" }) { result ->
                    Row(Modifier.fillMaxWidth().clickable { onAdd(result) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = result.posterPath?.let { "https://image.tmdb.org/t/p/w185$it" },
                            contentDescription = result.title,
                            modifier = Modifier.width(56.dp).height(80.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(result.title, color = White, fontWeight = FontWeight.SemiBold)
                            Text("${if (result.type == "movie") "Filme" else "Série"} • ${result.year}", color = Muted, fontSize = 12.sp)
                        }
                        Icon(Icons.Default.AddCircle, null, tint = Purple)
                    }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                if (filtered.isEmpty()) item { EmptyState("Nada por aqui ainda", "Adicione títulos pelo TMDB ou ajuste o filtro.") }
                items(filtered, key = { it.id }) { item ->
                    MediaRow(item, onClick = { onToggle(item) }, trailing = {
                        Row {
                            IconButton(onClick = { onToggle(item) }) { Icon(if (item.status == "WATCHED") Icons.Default.CheckCircle else Icons.Default.CheckCircleOutline, null, tint = Purple) }
                            IconButton(onClick = { onDelete(item) }) { Icon(Icons.Default.DeleteOutline, null, tint = Muted) }
                        }
                    })
                }
            }
        }
    }
    if (!apiKeySet) {
        Text("Para pesquisar títulos online, configure a chave TMDB em Ajustes.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }
}

@Composable
private fun SeriesScreen(items: List<MediaItem>, onEpisode: (MediaItem) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("Acompanhe de onde parou", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        Text("Toque em + para marcar mais um episódio assistido.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp))
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            if (items.isEmpty()) item { EmptyState("Nenhuma série adicionada", "Pesquise séries na aba Minha lista.") }
            items(items, key = { it.id }) { item ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(16.dp)).background(Panel).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Poster(item, Modifier.width(64.dp).height(92.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, fontWeight = FontWeight.Bold, color = White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${item.watchedEpisodes} episódio(s) marcados", color = Muted, fontSize = 12.sp)
                            if (item.totalEpisodes > 0) {
                                LinearProgressIndicator(progress = { item.watchedEpisodes.toFloat() / item.totalEpisodes.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = Purple, trackColor = Panel2)
                            }
                        }
                        IconButton(onClick = { onEpisode(item) }) { Icon(Icons.Default.AddCircle, null, tint = Purple) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsScreen(items: List<MediaItem>) {
    val watched = items.filter { it.status == "WATCHED" }
    val thisYear = SimpleDateFormat("yyyy", Locale.getDefault()).format(Date())
    val watchedThisYear = watched.count {
        it.watchedAt?.let { time -> SimpleDateFormat("yyyy", Locale.getDefault()).format(Date(time)) == thisYear } == true
    }
    val minutes = watched.sumOf { it.runtimeMinutes }
    val genres = watched.flatMap { it.genres.split(",").map(String::trim) }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "Adicione títulos com gêneros"
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Sua retrospectiva", fontSize = 25.sp, fontWeight = FontWeight.Black) }
        item { Text("Um retrato da sua jornada cinematográfica.", color = Muted) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("${watched.size}", "Assistidos", Modifier.weight(1f))
            StatTile("$watchedThisYear", "Em $thisYear", Modifier.weight(1f))
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(if (minutes > 0) "${minutes / 60}h" else "—", "Tempo registrado", Modifier.weight(1f))
            StatTile("${items.count { it.mediaType == "tv" }}", "Séries", Modifier.weight(1f))
        } }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("Gênero em destaque", color = Lavender, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(genres, color = White, fontSize = 18.sp)
                    Text("As estatísticas ficam melhores à medida que você registra avaliações, gêneros e duração.", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun CalendarScreen(items: List<MediaItem>) {
    val upcoming = items.filter { it.releaseDate.length >= 10 }.sortedBy { it.releaseDate }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Calendário de lançamentos", fontSize = 23.sp, fontWeight = FontWeight.Black) }
        item { Text("Datas vindas dos metadados do TMDB. Confirme a disponibilidade na sua região.", color = Muted, fontSize = 12.sp) }
        if (upcoming.isEmpty()) {
            item { EmptyState("Seu calendário está vazio", "Adicione filmes e séries pelo TMDB para registrar datas de lançamento.") }
        } else {
            items(upcoming, key = { it.id }) { item ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(58.dp).height(58.dp).clip(RoundedCornerShape(10.dp)).background(Panel2), contentAlignment = Alignment.Center) {
                        Text(item.releaseDate.take(10).let { if (it.length >= 10) it.substring(5) else it }, color = Lavender, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = White, fontWeight = FontWeight.SemiBold)
                        Text("${if (item.mediaType == "movie") "Filme" else "Série"} • ${item.releaseDate}", color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    apiKey: String, onKeyChange: (String) -> Unit, onSaveKey: () -> Unit,
    onExport: () -> Unit, onImport: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Ajustes e backup", fontSize = 24.sp, fontWeight = FontWeight.Black) }
        item { Text("Sua coleção fica salva localmente neste aparelho.", color = Muted) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Conectar ao TMDB", fontWeight = FontWeight.Bold, color = Lavender)
                    Text("Crie uma chave de API para uso pessoal nas configurações da sua conta TMDB. Não publique essa chave em repositórios públicos.", color = Muted, fontSize = 12.sp)
                    OutlinedTextField(
                        value = apiKey, onValueChange = onKeyChange, modifier = Modifier.fillMaxWidth(),
                        label = { Text("TMDB API key") }, singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple, unfocusedBorderColor = Panel2)
                    )
                    Button(onClick = onSaveKey, modifier = Modifier.fillMaxWidth()) { Text("Salvar chave neste aparelho") }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Backup da coleção", fontWeight = FontWeight.Bold, color = Lavender)
                    Text("Exporte um arquivo JSON para guardar em local seguro. Para restaurar, selecione um backup criado pelo CineVerse.", color = Muted, fontSize = 12.sp)
                    Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.FileUpload, null); Spacer(Modifier.width(8.dp)); Text("Exportar backup") }
                    OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("Restaurar backup") }
                }
            }
        }
        item {
            Text("Dados de filmes e séries fornecidos por TMDB. Este produto usa a API do TMDB, mas não é endossado nem certificado pelo TMDB.", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MediaRow(item: MediaItem, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Poster(item, Modifier.width(66.dp).height(94.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, color = White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${if (item.mediaType == "tv") "Série" else "Filme"}${if (item.year.isNotBlank()) " • ${item.year}" else ""}", color = Muted, fontSize = 12.sp)
            Text(when(item.status) { "WATCHED" -> "✓ Assistido"; "WATCHING" -> "Em andamento"; else -> "Quero assistir" }, color = Lavender, fontSize = 11.sp)
            if (item.rating > 0) Text("★ ${item.rating}/10", color = Purple, fontSize = 12.sp)
        }
        trailing()
    }
}

@Composable
private fun Poster(item: MediaItem, modifier: Modifier = Modifier) {
    AsyncImage(
        model = item.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" },
        contentDescription = item.title,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(Panel2),
        contentScale = ContentScale.Crop
    )
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Panel).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, color = Lavender, fontSize = 24.sp, fontWeight = FontWeight.Black)
        Text(label, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.LocalMovies, null, tint = Purple, modifier = Modifier.size(42.dp))
        Text(title, color = White, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}
