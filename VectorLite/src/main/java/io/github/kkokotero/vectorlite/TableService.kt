package io.github.kkokotero.vectorlite

import io.github.kkokotero.vectorlite.orm.Query
import io.github.kkokotero.vectorlite.orm.SqlOperator
import io.github.kkokotero.vectorlite.orm.Table

/**
 * Public CRUD-oriented service abstraction for a single table.
 */
interface TableService<T : Any> {
    fun create(entity: T): T
    fun update(entity: T): T
    fun delete(entity: T): Boolean
    fun findById(id: Any): T?
    fun findAll(): List<T>
    fun count(): Long
    fun query(): Query<T>
    fun where(block: Query<T>.() -> Unit): List<T>
    fun first(block: Query<T>.() -> Unit): T?
}

/**
 * Default reusable table service that can be inherited by user services.
 */
open class DefaultTableService<T : Any>(
    protected open val table: Table<T>,
    protected open val session: VectorLiteSession
) : TableService<T> {

    override fun create(entity: T): T {
        table.insert(entity)
        return entity
    }

    override fun update(entity: T): T {
        table.update(entity)
        return entity
    }

    override fun delete(entity: T): Boolean = table.delete(entity)

    override fun findById(id: Any): T? = table.findById(id)

    override fun findAll(): List<T> = table.findAll()

    override fun count(): Long = table.count()

    override fun query(): Query<T> = table.query()

    override fun where(block: Query<T>.() -> Unit): List<T> = query().apply(block).findAll()

    override fun first(block: Query<T>.() -> Unit): T? = query().apply(block).findFirst()
}
