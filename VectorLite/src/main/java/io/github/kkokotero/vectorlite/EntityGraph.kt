package io.github.kkokotero.vectorlite

import io.github.kkokotero.vectorlite.orm.TableSchema
import kotlin.reflect.KClass

/**
 * Sorts entities so tables are created in dependency order.
 */
internal object EntityGraph {

    fun sort(entityTypes: Collection<KClass<out Any>>): List<KClass<out Any>> {
        if (entityTypes.size <= 1) return entityTypes.toList()

        val ordered = mutableListOf<KClass<out Any>>()
        val visiting = linkedSetOf<KClass<out Any>>()
        val visited = linkedSetOf<KClass<out Any>>()
        val known = entityTypes.toSet()

        fun visit(entityType: KClass<out Any>) {
            if (entityType in visited) return
            if (!visiting.add(entityType)) {
                throw IllegalStateException(
                    "Cyclic entity dependency detected at ${entityType.simpleName}"
                )
            }

            val schema = TableSchema.fromClass(entityType)
            val dependencies = schema.columns
                .mapNotNull { it.foreignKey?.entity }
                .filter { it in known }
                .distinct()

            dependencies.forEach { dependency ->
                visit(dependency as KClass<out Any>)
            }

            visiting.remove(entityType)
            visited += entityType
            ordered += entityType
        }

        entityTypes.forEach { visit(it) }
        return ordered
    }
}
