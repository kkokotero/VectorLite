package io.github.kkokotero.vectorlite

import io.github.kkokotero.vectorlite.orm.Column
import io.github.kkokotero.vectorlite.orm.DataTable
import io.github.kkokotero.vectorlite.orm.ForeignKey
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import kotlin.reflect.KClass

@DataTable("graph_users")
data class GraphUser(
    @param:Column(primaryKey = true, autoIncrement = true, nullable = false)
    val id: Long = 0,

    @param:Column(nullable = false)
    val name: String = ""
)

@DataTable("graph_posts")
data class GraphPost(
    @param:Column(primaryKey = true, autoIncrement = true, nullable = false)
    val id: Long = 0,

    @param:Column(nullable = false)
    @param:ForeignKey(entity = GraphUser::class, field = "id")
    val userId: Long = 0,

    @param:Column(nullable = false)
    val title: String = "",

    @param:Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.MIN
)

class VectorLiteEntityGraphTest {

    @Test
    fun sortsEntitiesByForeignKeyDependencies() {
        val ordered = EntityGraph.sort(
            listOf(GraphPost::class as KClass<out Any>, GraphUser::class as KClass<out Any>)
        )

        assertEquals(listOf(GraphUser::class, GraphPost::class), ordered)
    }
}
