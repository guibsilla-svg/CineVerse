package br.com.minhamidia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import br.com.minhamidia.data.AppDatabase
import br.com.minhamidia.data.MediaItem
import br.com.minhamidia.data.TmdbClient
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

private val Night = Color(0xFF08070D)
private val Panel = Color(0xFF171321)
private val Purple = Color(0xFFAC65FF)
private val TextMain = Color(0xFFF7F3FF)
private val Muted = Color(0xFFB7AEC6)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = AppDatabase.get(this).mediaDao()
        setContent {
            var apiKey by remember { mutableStateOf("") }
            var query by remember { mutableStateOf("") }
            var selectedTab by remember { mutableStateOf("Minha lista") }
            var results by remember { mutableStateOf(emptyList<br.com.minhamidia.data.TmdbResult>()) }
            var message by remember { mutableStateOf("Adicione títulos à sua coleção.") }
            var showKey by remember { mutableStateOf(false) }
            val all by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
            val visible = when (selectedTab) {
                "Assistidos" -> all.filter { it.status == "WATCHED" }
                "Assistindo" -> all.filter { it.status == "WATCHING" }
                else -> all.filter { it.status == "WATCHLIST" }
            }
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Purple, background = Night, surface = Panel,
                onBackground = TextMain, onSurface = TextMain
            )) {
                Surface(Modifier.fillMaxSize(), color = Night) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                        Spacer(Modifier.height(20.dp))
                        Text("CINEVERSE", fontSize = 13.sp, color = Purple, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("Seu universo em cada tela.", fontSize = 26.sp, color = TextMain, fontWeight = FontWeight.ExtraBold)
                        Text("Organize o que você ama assistir.", color = Muted, fontSize = 14.sp)
                        Spacer(Modifier.height(18.dp))
                        OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Buscar filme ou série") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                            singleLine = true, shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple, unfocusedBorderColor = Panel))
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = {
                                if (apiKey.isBlank()) { showKey = true }
                                else lifecycleScope.launch {
                                    try { results = TmdbClient.search(query, apiKey); message = if (results.isEmpty()) "Nenhum resultado encontrado." else "Resultados encontrados." }
                                    catch (e: Exception) { message = e.message ?: "Não foi possível pesquisar." }
                                }
                            }, enabled = query.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = Purple)) {
                                Icon(Icons.Default.Search, null); Spacer(Modifier.width(6.dp)); Text("Pesquisar")
                            }
                            TextButton(onClick = { showKey = true }) { Text("Chave TMDB", color = Muted) }
                        }
                        if (results.isNotEmpty()) {
                            Text("RESULTADOS", color = Purple, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                            LazyColumn(Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(results) { item ->
                                    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(item.title, fontWeight = FontWeight.SemiBold)
                                            Text(item.year, color = Muted, fontSize = 12.sp)
                                        }
                                        IconButton(onClick = { lifecycleScope.launch { dao.insert(item.toMediaItem()); results = results.filterNot { it.id == item.id && it.type == item.type }; message = "Adicionado à sua lista." } }) {
                                            Icon(Icons.Default.Add, "Adicionar", tint = Purple)
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(message, color = Muted, fontSize = 12.sp)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Minha lista", "Assistindo", "Assistidos").forEach { tab ->
                                FilterChip(selected = selectedTab == tab, onClick = { selectedTab = tab }, label = { Text(tab, fontSize = 11.sp) })
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("${visible.size} títulos", color = Muted, fontSize = 12.sp)
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
                            items(visible, key = { it.id }) { media ->
                                MediaCard(media = media, onStatus = { status -> lifecycleScope.launch { dao.update(media.copy(status = status, watchedAt = if (status == "WATCHED") System.currentTimeMillis() else media.watchedAt)) } }, onDelete = { lifecycleScope.launch { dao.delete(media) } })
                            }
                        }
                    }
                }
                if (showKey) {
                    AlertDialog(onDismissRequest = { showKey = false }, title = { Text("Chave da API TMDB") },
                        text = { Column { Text("Insira sua chave pessoal para pesquisar filmes e séries."); Spacer(Modifier.height(8.dp)); OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API key") }, singleLine = true) } },
                        confirmButton = { TextButton(onClick = { showKey = false }) { Text("Salvar") } },
                        dismissButton = { TextButton(onClick = { showKey = false }) { Text("Cancelar") } })
                }
            }
        }
    }
}

@Composable
private fun MediaCard(media: MediaItem, onStatus: (String) -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!media.posterPath.isNullOrBlank()) {
            AsyncImage(model = "https://image.tmdb.org/t/p/w185${media.posterPath}", contentDescription = media.title, modifier = Modifier.width(56.dp).height(82.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(media.title, color = TextMain, fontWeight = FontWeight.Bold)
            Text(media.year, color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onStatus("WATCHING") }, contentPadding = PaddingValues(horizontal = 5.dp)) { Text("Assistindo", fontSize = 10.sp) }
                TextButton(onClick = { onStatus("WATCHED") }, contentPadding = PaddingValues(horizontal = 5.dp)) { Text("Assistido", fontSize = 10.sp) }
            }
        }
        TextButton(onClick = onDelete) { Text("Excluir", color = Muted, fontSize = 11.sp) }
    }
}