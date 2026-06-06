package io.github.kkokotero.vectorlite

import android.content.Context
import androidx.sqlite.SQLiteStatement
import io.github.kkokotero.vectorlite.orm.Repository as CoreRepository
import io.github.kkokotero.vectorlite.orm.Table
import io.github.kkokotero.vectorlite.orm.TableChangeEvent
import io.github.kkokotero.vectorlite.orm.VectorLiteException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.reflect.KClass

/**
 * Public runtime session for VectorLite.
 *
 * It owns the SQLite connection, the low-level ORM store and the public
 * repositories/services registered by the builder.
 */
class VectorLiteSession internal constructor(
    internal val applicationContext: Context,
    databaseName: String,
    entityTypes: List<KClass<out Any>>,
    private val serviceTypes: List<KClass<out Any>>
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val database = VectorLiteDatabase(applicationContext, databaseName)
    private val store = CoreRepository(database)
    private val repositoryCache = linkedMapOf<KClass<*>, Repository<*>>()
    private val serviceCache = linkedMapOf<KClass<*>, Any>()

    val triggers: VectorLiteTriggers = VectorLiteTriggers(this, scope)

    init {
        registerEntities(entityTypes)
    }

    internal val changeEvents: kotlinx.coroutines.flow.SharedFlow<TableChangeEvent>
        get() = store.tableChanges

    internal val coreStore: CoreRepository
        get() = store

    internal val backingDatabase: VectorLiteDatabase
        get() = database

    val databaseName: String
        get() = database.databaseFile.name

    /**
     * Opens the database transactionally and returns the result of the block.
     */
    fun <T> transaction(block: () -> T): T {
        return store.transaction {
            block()
        }
    }

    /**
     * Returns a typed repository for the requested entity.
     */
    inline fun <reified T : Any> repository(): Repository<T> = repository(T::class)

    /**
     * Returns a typed repository for the requested entity.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> repository(entityClass: KClass<T>): Repository<T> {
        val table = store.table(entityClass)
        return repositoryCache.getOrPut(entityClass) {
            Repository(this, table)
        } as Repository<T>
    }

    /**
     * Returns a registered table for advanced queries.
     */
    inline fun <reified T : Any> table(): Table<T> = table(T::class)

    /**
     * Returns a registered table for advanced queries.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> table(entityClass: KClass<T>): Table<T> = store.table(entityClass)

    /**
     * Returns the default CRUD service for a registered entity table.
     */
    inline fun <reified T : Any> crud(): TableService<T> = crud(T::class)

    /**
     * Returns the default CRUD service for a registered entity table.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> crud(entityClass: KClass<T>): TableService<T> {
        val table = store.table(entityClass)
        return DefaultTableService(table, this) as TableService<T>
    }

    /**
     * Returns the default CRUD service for a registered entity table.
     */
    inline fun <reified T : Any> tableService(): TableService<T> = crud()

    /**
     * Returns the default CRUD service for a registered entity table.
     */
    fun <T : Any> tableService(entityClass: KClass<T>): TableService<T> = crud(entityClass)

    /**
     * Returns a registered service instance, creating it on first access.
     */
    inline fun <reified T : Any> service(): T = service(T::class)

    /**
     * Returns a registered service instance, creating it on first access.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> service(serviceClass: KClass<T>): T {
        val isDefaultTableService = DefaultTableService::class.java.isAssignableFrom(serviceClass.java)
        val isCustomService = serviceTypes.isEmpty() || serviceClass in serviceTypes
        if (!isDefaultTableService && !isCustomService) {
            throw VectorLiteException.TableNotRegistered(
                "Service ${serviceClass.simpleName} was not registered in the database builder"
            )
        }
        return serviceCache.getOrPut(serviceClass) {
            ServiceFactory(this).create(serviceClass)
        } as T
    }

    /**
     * Exports the database into the requested file.
     */
    fun exportTo(destination: java.io.File) {
        destination.parentFile?.mkdirs()
        store.backupTo(destination.absolutePath)
    }

    /**
     * Creates a copy of the current database file.
     */
    fun backupTo(destination: String) {
        store.backupTo(destination)
    }

    /**
     * Replaces the backing database with a file from disk.
     */
    fun importFrom(source: java.io.File) {
        require(source.exists()) { "Import source does not exist: ${source.absolutePath}" }
        database.close()
        source.copyTo(database.databaseFile, overwrite = true)
        database.reopen()
    }

    /**
     * Exposes the flow of changes for a specific table.
     */
    fun tableChanges(tableName: String): kotlinx.coroutines.flow.Flow<TableChangeEvent> {
        return store.tableChanges(tableName)
    }

    internal fun emitChange(event: TableChangeEvent) {
        store.emitTableChange(event)
    }

    internal fun tableByName(tableName: String): Table<*>? {
        return store.tables.values.firstOrNull { it.schema.tableName == tableName }
    }

    private fun registerEntities(entityTypes: List<KClass<out Any>>) {
        entityTypes.forEach { entityClass ->
            val table = store.register(entityClass)
            repositoryCache[entityClass] = Repository(this, table)
        }
    }

    override fun close() {
        serviceCache.clear()
        repositoryCache.clear()
        triggers.cancel()
        scope.cancel()
        database.close()
    }
}

/**
 * Public transaction-aware repository facade.
 */
class Repository<T : Any> internal constructor(
    private val session: VectorLiteSession,
    internal val table: Table<T>
) {

    fun insert(entity: T): Long = table.insert(entity)

    fun insertAll(entities: Iterable<T>): List<Long> {
        return session.transaction {
            entities.map { entity -> table.insert(entity) }
        }
    }

    fun insertAll(vararg entities: T): List<Long> = insertAll(entities.asList())

    fun update(entity: T): Int = table.update(entity)

    fun delete(entity: T): Boolean = table.delete(entity)

    fun delete(query: io.github.kkokotero.vectorlite.orm.Query<T>): Int = table.delete(query)

    fun deleteWhere(builder: io.github.kkokotero.vectorlite.orm.Query<T>.() -> Unit): Int =
        table.deleteWhere(builder)

    fun deleteAll(): Int = table.deleteAll()

    fun query(): io.github.kkokotero.vectorlite.orm.Query<T> = table.query()

    fun findAll(): List<T> = table.query().findAll()

    fun findFirst(): T? = table.query().findFirst()

    fun count(): Long = table.query().count()

    fun exists(): Boolean = table.query().exists()

    fun upsert(entity: T): T {
        val primaryKey = table.schema.primaryKeyColumn
        if (primaryKey == null) {
            insert(entity)
            return entity
        }

        val keyValue = primaryKey.readValue(entity)
        val shouldUpdate = keyValue != null && table.query()
            .withoutRelationships()
            .whereColumn(primaryKey.name, io.github.kkokotero.vectorlite.orm.SqlOperator.EQUAL, keyValue)
            .exists()

        if (shouldUpdate) {
            update(entity)
        } else {
            insert(entity)
        }
        return entity
    }

    fun upsertAll(entities: Iterable<T>): List<T> {
        return session.transaction {
            entities.map { entity -> upsert(entity) }
        }
    }

    fun upsertAll(vararg entities: T): List<T> = upsertAll(entities.asList())

    fun nearestNeighbors(
        vectorColumn: kotlin.reflect.KProperty1<T, FloatArray>,
        queryVector: FloatArray,
        options: io.github.kkokotero.vectorlite.orm.VectorSearchOptions = io.github.kkokotero.vectorlite.orm.VectorSearchOptions()
    ) = table.nearestNeighbors(vectorColumn, queryVector, options)

    fun vectorSearch(
        column: kotlin.reflect.KProperty1<T, FloatArray>,
        vector: FloatArray,
        topK: Int = 10,
        approximate: Boolean = true
    ) = table.query()
        .nearestTo(
            column = column,
            vector = vector,
            options = io.github.kkokotero.vectorlite.orm.VectorSearchOptions(
                topK = topK.toLong(),
                approximate = approximate
            )
        )
        .vectorSearch()

    fun <R> transaction(block: Repository<T>.() -> R): R {
        return session.transaction {
            this@Repository.block()
        }
    }
}

/**
 * Marker annotation for public services.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Service

/**
 * Marker annotation for transactional service methods.
 *
 * Note: the runtime exposes explicit `transaction { }` helpers for concrete
 * classes. The annotation is retained so higher-level tooling can interpret it.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Transactional
