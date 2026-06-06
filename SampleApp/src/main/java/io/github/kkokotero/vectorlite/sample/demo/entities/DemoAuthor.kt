package io.github.kkokotero.vectorlite.sample.demo.entities

import io.github.kkokotero.vectorlite.orm.Column
import io.github.kkokotero.vectorlite.orm.DataTable
import io.github.kkokotero.vectorlite.orm.Relationship
import io.github.kkokotero.vectorlite.orm.RelationshipType
import io.github.kkokotero.vectorlite.sample.demo.entities.DemoArticle
import java.time.LocalDateTime

@DataTable("authors")
data class DemoAuthor(
    @param:Column(primaryKey = true, autoIncrement = true, nullable = false)
    val id: Long = 0,

    @param:Column(nullable = false, unique = true)
    val name: String,

    @param:Column(nullable = false)
    val expertise: String,

    @param:Column(nullable = false)
    val joinedAt: LocalDateTime,

    @Relationship(
        targetEntity = DemoArticle::class,
        type = RelationshipType.ONE_TO_MANY,
        mappedBy = "authorId"
    )
    var articles: List<DemoArticle> = emptyList()
)
