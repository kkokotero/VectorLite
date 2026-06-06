package io.github.kkokotero.vectorlite.sample.demo.services

import io.github.kkokotero.vectorlite.Repository
import io.github.kkokotero.vectorlite.Service
import io.github.kkokotero.vectorlite.Transactional
import io.github.kkokotero.vectorlite.VectorLiteSession
import io.github.kkokotero.vectorlite.orm.VectorSearchOptions
import io.github.kkokotero.vectorlite.orm.descending
import io.github.kkokotero.vectorlite.orm.equal
import io.github.kkokotero.vectorlite.orm.matches
import io.github.kkokotero.vectorlite.sample.demo.entities.DemoArticle
import io.github.kkokotero.vectorlite.sample.demo.entities.DemoAuthor
import io.github.kkokotero.vectorlite.sample.demo.models.DemoSemanticHit
import io.github.kkokotero.vectorlite.sample.demo.models.DemoState
import io.github.kkokotero.vectorlite.sample.demo.models.SchemaOverview
import io.github.kkokotero.vectorlite.sample.demo.models.SearchPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

@Service
class DemoContentService(
    private val authors: Repository<DemoAuthor>,
    private val articles: Repository<DemoArticle>,
    private val database: VectorLiteSession
) {

    suspend fun loadState(searchPreset: SearchPreset = SearchPreset.Compose): DemoState = withContext(Dispatchers.IO) {
        ensureSeeded()
        DemoState(
            dbName = database.databaseName,
            authors = authors.query().withRelationships().orderBy(DemoAuthor::name).findAll(),
            featuredArticles = articles.query()
                .withRelationships(DemoArticle::author)
                .where(DemoArticle::featured equal true)
                .orderBy(DemoArticle::publishedAt, ascending = false)
                .findAll(),
            semanticResults = runSemanticSearch(searchPreset),
            titleMatches = articles.query()
                .withRelationships(DemoArticle::author)
                .whereMatches(DemoArticle::title, "%${searchPreset.keyword}%")
                .orderBy(DemoArticle::rating, ascending = false)
                .findAll(),
            schemaOverview = buildSchemaOverview(),
            vectorPresets = SearchPreset.entries,
            activePreset = searchPreset
        )
    }

    @Transactional
    suspend fun reset(searchPreset: SearchPreset = SearchPreset.Compose): DemoState = withContext(Dispatchers.IO) {
        database.transaction {
            articles.deleteAll()
            authors.deleteAll()
        }
        loadState(searchPreset)
    }

    private fun ensureSeeded() {
        if (authors.count() > 0L) return

        database.transaction {
            val seedAuthors = listOf(
                DemoAuthor(
                    name = "Ana Torres",
                    expertise = "Android architecture and secure storage",
                    joinedAt = LocalDateTime.of(2024, 1, 8, 9, 0)
                ),
                DemoAuthor(
                    name = "Diego Ramírez",
                    expertise = "Vector search and embeddings",
                    joinedAt = LocalDateTime.of(2024, 2, 14, 10, 30)
                ),
                DemoAuthor(
                    name = "Lucía Pérez",
                    expertise = "Public library design and API ergonomics",
                    joinedAt = LocalDateTime.of(2024, 3, 21, 15, 45)
                )
            )

            authors.insertAll(seedAuthors)

            val savedAuthors = authors.query().orderBy(DemoAuthor::name).findAll()
            val authorIds = savedAuthors.associateBy { it.name }

            val seedArticles = listOf(
                DemoArticle(
                    authorId = authorIds.getValue("Ana Torres").id,
                    title = "AndroidX SQLite as a safer foundation",
                    summary = "Why the new bundled driver is a better base for a public library.",
                    body = "This article shows how AndroidX SQLite simplifies connection handling, extension loading, and public API design.",
                    publishedAt = LocalDateTime.of(2024, 5, 10, 12, 0),
                    readingMinutes = 6,
                    featured = true,
                    rating = 4.8,
                    embedding = floatArrayOf(0.92f, 0.10f, 0.05f, 0.28f)
                ),
                DemoArticle(
                    authorId = authorIds.getValue("Diego Ramírez").id,
                    title = "Semantic search with SQLite vector functions",
                    summary = "A practical look at nearest-neighbor queries and similarity scoring.",
                    body = "We store embeddings directly in SQLite and rank results with cosine similarity for semantic retrieval.",
                    publishedAt = LocalDateTime.of(2024, 5, 16, 9, 15),
                    readingMinutes = 8,
                    featured = true,
                    rating = 4.9,
                    embedding = floatArrayOf(0.08f, 0.92f, 0.10f, 0.18f)
                ),
                DemoArticle(
                    authorId = authorIds.getValue("Lucía Pérez").id,
                    title = "How to package a Kotlin library for public use",
                    summary = "Naming, packaging, and organization rules for a clean library surface.",
                    body = "A public library should expose a stable entry point, clean packages, and sample code that proves the API.",
                    publishedAt = LocalDateTime.of(2024, 6, 3, 18, 30),
                    readingMinutes = 5,
                    featured = false,
                    rating = 4.6,
                    embedding = floatArrayOf(0.48f, 0.14f, 0.72f, 0.26f)
                ),
                DemoArticle(
                    authorId = authorIds.getValue("Ana Torres").id,
                    title = "Secure local databases on Android",
                    summary = "Private storage, WAL, and proper transaction boundaries.",
                    body = "Using the app-private database path keeps the demo aligned with security best practices.",
                    publishedAt = LocalDateTime.of(2024, 6, 20, 13, 10),
                    readingMinutes = 4,
                    featured = false,
                    rating = 4.4,
                    embedding = floatArrayOf(0.80f, 0.08f, 0.12f, 0.44f)
                ),
                DemoArticle(
                    authorId = authorIds.getValue("Diego Ramírez").id,
                    title = "Loading relationships without an ORM monster",
                    summary = "Typed and annotated relations that stay readable in a public API.",
                    body = "The sample demonstrates belongsTo and hasMany style loading through the library queries.",
                    publishedAt = LocalDateTime.of(2024, 7, 2, 11, 5),
                    readingMinutes = 7,
                    featured = true,
                    rating = 4.7,
                    embedding = floatArrayOf(0.22f, 0.78f, 0.18f, 0.56f)
                )
            )

            articles.insertAll(seedArticles)
        }
    }

    private fun runSemanticSearch(preset: SearchPreset): List<DemoSemanticHit> {
        val result = articles
            .query()
            .withRelationships(DemoArticle::author)
            .nearestTo(DemoArticle::embedding, preset.vector, VectorSearchOptions(topK = 3, approximate = false))
            .vectorSearch()

        return result.results.map { hit ->
            DemoSemanticHit(
                article = hit.entity,
                similarity = hit.similarity,
                distance = hit.distance,
                rank = hit.rank
            )
        }
    }

    private fun buildSchemaOverview(): List<SchemaOverview> = listOf(
        SchemaOverview(
            table = "authors",
            columns = listOf(
                "id: Long (PK)",
                "name: String",
                "expertise: String",
                "joinedAt: LocalDateTime",
                "articles: relation"
            )
        ),
        SchemaOverview(
            table = "articles",
            columns = listOf(
                "id: Long (PK)",
                "authorId: Long (FK)",
                "title: String",
                "summary: String",
                "body: String",
                "publishedAt: LocalDateTime",
                "readingMinutes: Int",
                "featured: Boolean",
                "rating: Double",
                "embedding: FloatArray"
            )
        )
    )
}
