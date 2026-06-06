package io.github.kkokotero.vectorlite.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.kkokotero.vectorlite.sample.demo.DemoDataSource
import io.github.kkokotero.vectorlite.sample.demo.entities.DemoArticle
import io.github.kkokotero.vectorlite.sample.demo.models.DemoState
import io.github.kkokotero.vectorlite.sample.demo.models.SearchPreset
import io.github.kkokotero.vectorlite.sample.demo.models.uiFormat
import io.github.kkokotero.vectorlite.sample.theme.SampleappTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SampleappTheme {
                DemoScreen()
            }
        }
    }
}

@Composable
private fun DemoScreen() {
    val context = LocalContext.current.applicationContext
    val dataSource = remember(context) { DemoDataSource(context) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<DemoState?>(null) }
    var selectedPreset by remember { mutableStateOf(SearchPreset.Compose) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { dataSource.close() }
    }

    LaunchedEffect(selectedPreset) {
        loading = true
        error = null
        state = try {
            withContext(Dispatchers.IO) { dataSource.loadState(selectedPreset) }
        } catch (t: Throwable) {
            error = t.message ?: t::class.java.simpleName
            null
        }
        loading = false
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Surface(modifier = Modifier.padding(innerPadding)) {
            when {
                loading -> LoadingState()
                error != null -> ErrorState(message = error!!)
                state != null -> DemoContent(
                    state = state!!,
                    onPresetSelected = { selectedPreset = it },
                    onRefresh = {
                        scope.launch {
                            loading = true
                            error = null
                            state = try {
                                withContext(Dispatchers.IO) { dataSource.reset(selectedPreset) }
                            } catch (t: Throwable) {
                                error = t.message ?: t::class.java.simpleName
                                null
                            }
                            loading = false
                        }
                    },
                    selectedPreset = selectedPreset
                )
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("Initializing VectorLite demo...")
    }
}

@Composable
private fun ErrorState(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Demo failed", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(text = message)
    }
}

@Composable
private fun DemoContent(
    state: DemoState,
    selectedPreset: SearchPreset,
    onPresetSelected: (SearchPreset) -> Unit,
    onRefresh: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            HeaderCard(state, onRefresh)
        }

        item {
            SectionCard(
                title = "Schema overview",
                subtitle = "Tables, types, foreign keys, and vector columns"
            ) {
                state.schemaOverview.forEach { overview ->
                    Text(
                        text = overview.table,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    overview.columns.forEach { column ->
                        Text(text = "• $column")
                    }
                    if (overview != state.schemaOverview.last()) {
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "Authors and relationships",
                subtitle = "ONE_TO_MANY and MANY_TO_ONE loaded from the ORM"
            ) {
                state.authors.forEach { author ->
                    Text(
                        text = "${author.name} · ${author.expertise}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(text = "Joined ${author.joinedAt.uiFormat()}")
                    Text(text = "Articles: ${author.articles.size}")
                    author.articles.forEach { article ->
                        Text(text = "  • ${article.title}")
                    }
                    if (author != state.authors.last()) {
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "Featured articles",
                subtitle = "Filtered with where(...) and ordered by publishedAt"
            ) {
                state.featuredArticles.forEach { article ->
                    ArticleRow(article)
                    if (article != state.featuredArticles.last()) {
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "Title search",
                subtitle = "LIKE search using whereMatches(...)"
            ) {
                if (state.titleMatches.isEmpty()) {
                    Text("No title matches for ${state.activePreset.keyword}")
                } else {
                    state.titleMatches.forEach { article ->
                        ArticleRow(article)
                        if (article != state.titleMatches.last()) {
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "Vector search",
                subtitle = "nearestTo(...) + vectorSearch()"
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    state.vectorPresets.forEach { preset ->
                        AssistChip(
                            onClick = { onPresetSelected(preset) },
                            label = { Text(preset.label) },
                            leadingIcon = {
                                Text(if (preset == selectedPreset) "●" else "○")
                            }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                state.semanticResults.forEach { hit ->
                    Text(
                        text = "#${hit.rank} · ${hit.article.title}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(text = "Similarity: ${(hit.similarity * 100f).formatOneDecimal()}% · Distance: ${hit.distance.formatTwoDecimals()}")
                    Text(text = hit.article.summary)
                    Text(text = "Author: ${hit.article.author?.name ?: hit.article.authorId.toString()} · ${hit.article.publishedAt.uiFormat()}")
                    if (hit != state.semanticResults.last()) {
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderCard(state: DemoState, onRefresh: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("VectorLite Demo", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Database: ${state.dbName}")
            Text("Authors: ${state.authors.size} · Featured: ${state.featuredArticles.size} · Vector hits: ${state.semanticResults.size}")
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRefresh) {
                Text("Reset demo data")
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ArticleRow(article: DemoArticle) {
    Column {
        Text(
            text = article.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(text = article.summary)
        Text(text = "Published ${article.publishedAt.uiFormat()} · ${article.readingMinutes} min · rating ${article.rating.formatOneDecimal()} · featured=${article.featured}")
        Text(text = "Author: ${article.author?.name ?: article.authorId.toString()}")
    }
}

private fun Float.formatTwoDecimals(): String = String.format("%.2f", this)
private fun Float.formatOneDecimal(): String = String.format("%.1f", this)
private fun Double.formatOneDecimal(): String = String.format("%.1f", this)

@Preview(showBackground = true)
@Composable
private fun PreviewHeader() {
    SampleappTheme {
        HeaderCard(
            state = DemoState(
                dbName = "vectorlite_demo.db",
                authors = emptyList(),
                featuredArticles = emptyList(),
                semanticResults = emptyList(),
                titleMatches = emptyList(),
                schemaOverview = emptyList(),
                vectorPresets = SearchPreset.entries,
                activePreset = SearchPreset.Compose
            ),
            onRefresh = {}
        )
    }
}
