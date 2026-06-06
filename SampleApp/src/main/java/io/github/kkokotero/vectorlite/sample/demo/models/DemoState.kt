package io.github.kkokotero.vectorlite.sample.demo.models

import io.github.kkokotero.vectorlite.sample.demo.entities.DemoArticle
import io.github.kkokotero.vectorlite.sample.demo.entities.DemoAuthor
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class SearchPreset(
    val label: String,
    val keyword: String,
    val vector: FloatArray
) {
    Compose("Compose UI", "Compose", floatArrayOf(0.92f, 0.10f, 0.05f, 0.28f)),
    Security("Security", "secure", floatArrayOf(0.84f, 0.06f, 0.18f, 0.40f)),
    Library("Public API", "library", floatArrayOf(0.34f, 0.18f, 0.74f, 0.24f))
}

data class DemoState(
    val dbName: String,
    val authors: List<DemoAuthor>,
    val featuredArticles: List<DemoArticle>,
    val semanticResults: List<DemoSemanticHit>,
    val titleMatches: List<DemoArticle>,
    val schemaOverview: List<SchemaOverview>,
    val vectorPresets: List<SearchPreset>,
    val activePreset: SearchPreset
)

data class DemoSemanticHit(
    val article: DemoArticle,
    val similarity: Float,
    val distance: Float,
    val rank: Int
)

data class SchemaOverview(
    val table: String,
    val columns: List<String>
)

fun LocalDateTime.uiFormat(): String = format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US))
