package io.github.kkokotero.vectorlite.sample.demo.entities

import io.github.kkokotero.vectorlite.orm.Column
import io.github.kkokotero.vectorlite.orm.DataTable
import io.github.kkokotero.vectorlite.orm.DistanceMetric
import io.github.kkokotero.vectorlite.orm.ForeignKey
import io.github.kkokotero.vectorlite.orm.Relationship
import io.github.kkokotero.vectorlite.orm.RelationshipType
import io.github.kkokotero.vectorlite.orm.SQLiteColumnType
import io.github.kkokotero.vectorlite.orm.VectorColumn
import java.time.LocalDateTime

@DataTable("articles")
data class DemoArticle(
    @param:Column(primaryKey = true, autoIncrement = true, nullable = false)
    val id: Long = 0,

    @param:Column(nullable = false)
    @param:ForeignKey(entity = DemoAuthor::class, field = "id")
    val authorId: Long,

    @param:Column(nullable = false)
    val title: String,

    @param:Column(nullable = false, sqliteType = SQLiteColumnType.TEXT)
    val summary: String,

    @param:Column(nullable = false)
    val body: String,

    @param:Column(nullable = false)
    val publishedAt: LocalDateTime,

    @param:Column(nullable = false)
    val readingMinutes: Int,

    @param:Column(nullable = false)
    val featured: Boolean,

    @param:Column(nullable = false)
    val rating: Double,

    @param:VectorColumn(dimensions = 4, elementSize = 4, distanceMetric = DistanceMetric.COSINE)
    val embedding: FloatArray,

    @Relationship(
        targetEntity = DemoAuthor::class,
        type = RelationshipType.MANY_TO_ONE
    )
    var author: DemoAuthor? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DemoArticle

        if (id != other.id) return false
        if (authorId != other.authorId) return false
        if (readingMinutes != other.readingMinutes) return false
        if (featured != other.featured) return false
        if (rating != other.rating) return false
        if (title != other.title) return false
        if (summary != other.summary) return false
        if (body != other.body) return false
        if (publishedAt != other.publishedAt) return false
        if (!embedding.contentEquals(other.embedding)) return false
        if (author != other.author) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + authorId.hashCode()
        result = 31 * result + readingMinutes
        result = 31 * result + featured.hashCode()
        result = 31 * result + rating.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + summary.hashCode()
        result = 31 * result + body.hashCode()
        result = 31 * result + publishedAt.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + (author?.hashCode() ?: 0)
        return result
    }
}
