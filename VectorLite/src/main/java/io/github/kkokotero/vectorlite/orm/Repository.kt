package io.github.kkokotero.vectorlite.orm

import android.util.Log
import androidx.sqlite.SQLiteStatement
import io.github.kkokotero.vectorlite.VectorLiteDatabase
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlin.reflect.KClass
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Repositorio principal del ORM.
 *
 * This class centralizes database access and provides:
 * - table registration and resolution
 * - raw SQL execution
 * - query utilities
 * - transaction handling
 * - vector search support
 * - table change event emission
 *
 * @property database Underlying VectorLite database instance.
 */
class Repository(val database: VectorLiteDatabase) {

    /**
     * Map of registered tables indexed by entity class.
     *
     * Each entry maps a model class to its
     * `Table` instance inside the ORM.
     */
    val tables = mutableMapOf<KClass<*>, Table<*>>()

    /**
     * Internal mutable flow used to emit table change events.
     *
     * Configuration:
     * - no replay
     * - extra buffer capacity of 128 events
     * - drops the oldest events when the buffer is full
     */
    private val _tableChanges = MutableSharedFlow<TableChangeEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Nesting depth for transactional calls on the current thread.
     *
     * SQLite does not support `BEGIN` inside an active transaction, so
     * nested calls use SAVEPOINT/RELEASE instead.
     */
    private val transactionDepth = ThreadLocal.withInitial { 0 }
    private val savepointCounter = AtomicInteger(0)
    private val transactionLock = ReentrantLock(true)

    /**
     * Public read-only flow for observing table changes.
     */
    val tableChanges: SharedFlow<TableChangeEvent> = _tableChanges.asSharedFlow()

    /**
     * Repository initialization.
     *
     * It tries to enable:
     * - vector support
     * - foreign keys
     *
     * If initialization fails, the error is logged
     * to Android logs without interrupting repository creation.
     */
    init {
        try {
            database.exec("PRAGMA vector_load = ON;")
            database.exec("PRAGMA foreign_keys = ON;")
        } catch (e: Exception) {
            android.util.Log.e("Repository", "Initialization failed: ${e.message}")
        }
    }

    /* =========================
     * Table registration
     * ========================= */

    /**
     * Registers an entity in the ORM from a reified type.
     *
     * This method:
     * - builds the schema from the class
     * - creates the `Table` instance
     * - stores it in the registered tables map
     *
     * @param T Tipo de entidad a registrar.
     * @return Registered table for the entity.
     */
    inline fun <reified T : Any> register(): Table<T> {
        val schema = TableSchema.fromClass<T>()
        val table = Table(this, schema)
        tables[T::class] = table
        return table
    }

    /**
     * Registers an entity using its Kotlin class at runtime.
     *
     * This overload enables public modules to be built with a builder
     * que recibe listas de `KClass` en lugar de depender exclusivamente de
     * instead of relying only on compile-time reified types.
     */
    fun <T : Any> register(entityClass: KClass<T>): Table<T> {
        val schema = TableSchema.fromClass(entityClass)
        val table = Table(this, schema)
        tables[entityClass] = table
        return table
    }

    /**
     * Returns the table associated with an entity class.
     *
     * If the table was not registered, throws
     * `VectorLiteException.TableNotRegistered`.
     *
     * @param entityClass Clase de la entidad.
     * @return Corresponding table.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> table(entityClass: KClass<T>): Table<T> =
        tables[entityClass] as? Table<T>
            ?: throw VectorLiteException.TableNotRegistered(
                "Table for ${entityClass.simpleName} not registered"
            )

    /**
     * Returns the table associated with a reified type.
     *
     * @param T Tipo de entidad.
     * @return Corresponding table.
     */
    inline fun <reified T : Any> table(): Table<T> = table(T::class)

    /* =========================
     * SQL base
     * ========================= */

    /**
     * Executes an SQL statement with optional arguments.
     *
     * If the statement should be executed
     * as a query (`SELECT`, `PRAGMA`, `WITH`, `EXPLAIN`), it is processed
     * using `query`.
     *
     * Otherwise:
     * - prepares the statement
     * - binds the arguments
     * - executes the statement
     *
     * @param sql Sentencia SQL a ejecutar.
     * @param args Positional arguments.
     */
    fun exec(sql: String, args: Array<Any?> = emptyArray()) {
        if (shouldRunWithQuery(sql)) {
            queryOne(sql, args) { Unit }
            return
        }
        database.prepare(sql).use { stmt ->
            bind(stmt, args)
            stmt.step()
        }
    }

    /**
     * Executes an SQL query and returns the resulting cursor.
     *
     * @param sql Sentencia SQL.
     * @param args Positional arguments.
     * @return Cursor con los resultados.
     */
    fun query(
        sql: String,
        args: Array<Any?> = emptyArray()
    ): SQLiteStatement {
        return database.query(sql, args)
    }

    /**
     * Executes a query and maps each cursor row through a mapper.
     *
     * @param sql Sentencia SQL.
     * @param args Positional arguments.
     * @param mapper Function that maps a cursor row to a T instance.
     * @return Lista de resultados transformados.
     */
    fun <T> queryList(
        sql: String,
        args: Array<Any?> = emptyArray(),
        mapper: (SQLiteStatement) -> T
    ): List<T> {
        val statement = query(sql, args)
        val results = mutableListOf<T>()

        statement.use {
            while (it.step()) {
                results += mapper(it)
            }
        }
        return results
    }

    /**
     * Executes a query and returns only the first mapped result,
     * or null if there are no rows.
     *
     * @param sql Sentencia SQL.
     * @param args Positional arguments.
     * @param mapper Function that maps a cursor row to a T instance.
     * @return Primer resultado o null.
     */
    fun <T> queryOne(
        sql: String,
        args: Array<Any?> = emptyArray(),
        mapper: (SQLiteStatement) -> T
    ): T? = queryList(sql, args, mapper).firstOrNull()

    /**
     * Returns whether a query yields at least one row.
     *
     * @param sql Sentencia SQL.
     * @param args Positional arguments.
     * @return `true` si existe al menos una fila.
     */
    fun exists(sql: String, args: Array<Any?> = emptyArray()): Boolean =
        queryOne(sql, args) { true } ?: false

    /**
     * Executes a statement that affects rows and returns how many rows
     * were modified by the last operation.
     *
     * Internally uses `SELECT changes()` to retrieve the total.
     *
     * @param sql Sentencia SQL.
     * @param args Positional arguments.
     * @return Cantidad de filas afectadas.
     */
    internal fun executeAffecting(sql: String, args: Array<Any?> = emptyArray()): Int {
        exec(sql, args)
        return queryOne("SELECT changes()") { it.getInt(0) } ?: 0
    }

    /* =========================
     * Transactions
     * ========================= */

    /**
     * Executes a block inside a non-exclusive transaction.
     *
     * Flujo:
     * - begins a transaction
     * - runs the block
     * - marks the transaction successful if no error occurs
     * - finishes the transaction in `finally`
     *
     * @param block Block to execute inside the transaction.
     * @return Resultado retornado por el bloque.
     */
    fun <T> transaction(block: Repository.() -> T): T {
        transactionLock.withLock {
            val depth = transactionDepth.get()
            val savepointName = if (depth > 0) "vectorlite_sp_${savepointCounter.incrementAndGet()}" else null
            if (depth == 0) {
                exec("BEGIN IMMEDIATE;")
            } else {
                exec("SAVEPOINT $savepointName;")
            }
            transactionDepth.set(depth + 1)
            try {
                val result = block()
                if (depth == 0) {
                    exec("COMMIT;")
                } else {
                    exec("RELEASE SAVEPOINT $savepointName;")
                }
                return result
            } catch (error: Throwable) {
                if (depth == 0) {
                    runCatching { exec("ROLLBACK;") }
                } else {
                    runCatching {
                        exec("ROLLBACK TO SAVEPOINT $savepointName;")
                        exec("RELEASE SAVEPOINT $savepointName;")
                    }
                }
                throw error
            } finally {
                val nextDepth = (transactionDepth.get() - 1).coerceAtLeast(0)
                if (nextDepth == 0) transactionDepth.remove() else transactionDepth.set(nextDepth)
            }
        }
    }

    /* =========================
     * Methods used by Table
     * ========================= */

    /**
     * Executes an ORM query and returns all resulting entities.
     *
     * This method:
     * - construye el SQL desde `Query`
     * - resolves the corresponding table
     * - convierte cada fila del cursor en una entidad
     *
     * @param query ORM query.
     * @return Lista de entidades.
     */
    internal fun <T : Any> findAll(query: Query<T>): List<T> {
        val (sql, params) = query.build()
        val table = table(query.table.schema.entityClass)

        return queryList(sql, params) { cursor ->
            table.cursorToEntity(cursor, query.includeRelationships, query.requestedRelationshipNames)
        }
    }

    /**
     * Executes an ORM query and returns the first entity found.
     *
     * @param query ORM query.
     * @return Primera entidad o null.
     */
    internal fun <T : Any> findFirst(query: Query<T>): T? {
        val (sql, params) = query.build()
        val table = table(query.table.schema.entityClass)

        return queryOne(sql, params) { cursor ->
            table.cursorToEntity(cursor, query.includeRelationships, query.requestedRelationshipNames)
        }
    }

    /**
     * Executes a vector similarity query and returns results
     * enriched with distance, similarity, and ranking.
     *
     * Comportamiento:
     * - validates that the query is configured for similarity
     * - locates the corresponding vector column
     * - runs the query and computes similarity for each row
     * - if the approximate search fails with a recoverable error,
     *   automatically retries using a full scan
     *
     * @param query Query configured for vector similarity.
     * @return Vector search result.
     */
    internal fun <T : Any> findAllWithSimilarity(
        query: Query<T>
    ): VectorSearchResult<T> {

        if (!query.includeSimilarity) {
            throw VectorLiteException.InvalidSimilarityScore(
                "Query not configured for similarity search"
            )
        }

        val start = System.currentTimeMillis()
        val table = table(query.table.schema.entityClass)

        val columnInfo = table.schema.columns.find {
            it.name == query.similarityColumn && it.isVector
        } ?: throw VectorLiteException.ColumnNotFound(
            "Vector column '${query.similarityColumn}' not found"
        )

        val results = mutableListOf<SimilarityResult<T>>()
        var rank = 1

        /**
         * Runs the similarity query and adds each result
         * a la lista `results`.
         *
         * Para cada fila:
         * - reconstruye la entidad
         * - lee la distancia desde la columna `distance`
         * - computes similarity using the corresponding metric
         * - asigna un ranking incremental
         */
        fun runSimilarityQuery() {
            val (sql, params) = query.build()

            queryList(sql, params) { cursor ->
                val entity = table.cursorToEntity(cursor, query.includeRelationships, query.requestedRelationshipNames)
                val distanceIdx = cursor.getColumnNames().indexOf("distance")

                if (distanceIdx != -1) {
                    val distance = cursor.getFloat(distanceIdx)
                    val metric = columnInfo.distanceMetric ?: DistanceMetric.COSINE

                    val maxDist = columnInfo.vectorDimensions?.let {
                        VectorConverter.calculateMaxDistanceForVectors(it, metric)
                    }

                    val similarity = VectorConverter.calculateSimilarityScore(
                        distance,
                        metric,
                        maxDist
                    )

                    results += SimilarityResult(
                        entity,
                        distance,
                        similarity,
                        rank++
                    )
                }
            }
        }

        try {
            runSimilarityQuery()
        } catch (e: Exception) {
            val shouldFallbackToFullScan =
                query.useApproximateSearch &&
                        isRecoverableApproximateSearchFailure(e)

            if (!shouldFallbackToFullScan) throw e

            query.useApproximateSearch = false
            results.clear()
            rank = 1
            runSimilarityQuery()
        }

        return VectorSearchResult(
            results,
            System.currentTimeMillis() - start,
            results.size
        )
    }

    /**
     * Counts the number of rows in a table that satisfy a set
     * of conditions and arguments.
     *
     * @param tableName Nombre de la tabla.
     * @param conditions List of SQL condition fragments.
     * @param parameters Arguments associated with the conditions.
     * @return Total de filas coincidentes.
     */
    internal fun count(
        tableName: String,
        conditions: List<String>,
        parameters: List<Any?>
    ): Long {

        val where =
            if (conditions.isNotEmpty())
                " WHERE ${conditions.joinToString(" ")}"
            else ""

        val sql = "SELECT COUNT(*) FROM ${tableName}${where}"

        return queryOne(sql, parameters.toTypedArray()) {
            it.getLong(0)
        } ?: 0L
    }

    /**
     * Propagates vector artifact invalidation to dependent tables
     * que puedan haber sido afectadas por acciones referenciales (`CASCADE`,
     * `SET NULL`, etc.) disparadas desde SQLite.
     *
     * @param entityClass Entidad origen desde la cual se debe recorrer el grafo.
     * @param visited Set used to avoid cycles during recursion.
     */
    internal fun refreshCascadeVectorArtifacts(
        entityClass: KClass<*>,
        visited: MutableSet<KClass<*>> = linkedSetOf()
    ) {
        if (!visited.add(entityClass)) return

        tables.values.forEach { table ->
            val dependsOnEntity = table.schema.columns.any { column ->
                column.foreignKey?.entity == entityClass
            }

            if (!dependsOnEntity) return@forEach

            table.refreshVectorArtifactsAfterExternalDelete()
            refreshCascadeVectorArtifacts(table.schema.entityClass, visited)
        }
    }

    /* =========================
     * Binding REAL (la clave)
     * ========================= */

    /**
     * Binds arguments to a `SQLiteStatement`.
     *
     * This method translates common Kotlin types into types SQLite can
     * manejar correctamente:
     * - null
     * - ByteArray
     * - FloatArray
     * - enteros y flotantes
     * - booleanos
     * - cadenas
     *
     * Para `FloatArray`, si no existe contexto de columna, se convierte
     * por defecto a blob float32.
     *
     * @param stmt Statement compilado.
     * @param params Arguments to bind.
     */
    private fun bind(stmt: SQLiteStatement, params: Array<Any?>) {
        params.forEachIndexed { i, value ->
            val index = i + 1
            when (value) {
                null -> stmt.bindNull(index)
                is ByteArray -> stmt.bindBlob(index, value)
                is FloatArray -> {
                    // Nota: Si no hay contexto de columna, asumimos Float32 por defecto.
                    // In similarity searches, Query.build() already converts FloatArray to ByteArray (Blob)
                    // with the correct size according to the column definition.
                    stmt.bindBlob(index, VectorConverter.floatArrayToBlob(value, 4))
                }
                is Int -> stmt.bindLong(index, value.toLong())
                is Long -> stmt.bindLong(index, value)
                is Float -> stmt.bindDouble(index, value.toDouble())
                is Double -> stmt.bindDouble(index, value)
                is Boolean -> stmt.bindLong(index, if (value) 1 else 0)
                is Instant -> stmt.bindLong(index, value.toEpochMilli())
                is LocalDate -> stmt.bindText(index, value.toString())
                is LocalTime -> stmt.bindText(index, value.toString())
                is LocalDateTime -> stmt.bindText(index, value.toString())
                is String -> stmt.bindText(index, value)
                else -> stmt.bindText(index, value.toString())
            }
        }
    }

    /* =========================
     * Utilities
     * ========================= */

    /**
     * Returns the names of all user tables present
     * in the database, excluding SQLite internal tables.
     *
     * @return Lista de nombres de tablas.
     */
    fun getTableNames(): List<String> =
        queryList(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
        ) { it.getText(0) }

    /**
     * Runs a VACUUM on the database.
     *
     * Esto puede ayudar a compactar el archivo y recuperar espacio.
     */
    fun vacuum() {
        compact()
    }

    fun compact() {
        cleanupVectorArtifactsForCompaction()
        val originalJournalMode = currentJournalMode()
        val usingWal = originalJournalMode.equals("WAL", ignoreCase = true)

        try {
            if (usingWal) {
                exec("PRAGMA wal_checkpoint(TRUNCATE);")
                val switchedMode = switchJournalMode("DELETE")
                if (!switchedMode.equals("DELETE", ignoreCase = true)) {
                    Log.w(
                        "Repository",
                        "No fue posible cambiar journal_mode a DELETE antes del VACUUM. modeActual=$switchedMode"
                    )
                }
            }

            exec("VACUUM;")
        } finally {
            if (usingWal) {
                val restoredMode = switchJournalMode("WAL")
                if (!restoredMode.equals("WAL", ignoreCase = true)) {
                    Log.w(
                        "Repository",
                        "Could not restore journal_mode to WAL after VACUUM. currentMode=$restoredMode"
                    )
                } else {
                    exec("PRAGMA wal_checkpoint(TRUNCATE);")
                }
            }
        }
    }

    /**
     * Creates a copy of the database at the specified path
     * usando `VACUUM INTO`.
     *
     * @param path Ruta del archivo de destino.
     */
    fun backupTo(path: String) {
        exec("VACUUM INTO '$path'")
    }

    /**
     * Returns a filtered flow of change events for a specific table.
     *
     * @param tableName Nombre de la tabla a observar.
     * @return Flujo con cambios solo de esa tabla.
     */
    fun tableChanges(tableName: String): Flow<TableChangeEvent> {
        return tableChanges.filter { it.tableName == tableName }
    }

    /**
     * Emits a table change event internally.
     *
     * Used internally by the ORM when an operation changes data.
     *
     * @param event Evento a emitir.
     */
    internal fun emitTableChange(event: TableChangeEvent) {
        _tableChanges.tryEmit(event)
    }

    /**
     * Determina si una sentencia SQL debe ejecutarse usando `query`
     * en lugar de `execute`.
     *
     * It is considered a query if it starts with:
     * - SELECT
     * - PRAGMA
     * - WITH
     * - EXPLAIN
     *
     * @param sql Sentencia SQL a evaluar.
     * @return `true` si debe ejecutarse como consulta.
     */
    private fun shouldRunWithQuery(sql: String): Boolean {
        val normalized = sql.trimStart()
        return normalized.startsWith("SELECT", ignoreCase = true) ||
                normalized.startsWith("PRAGMA", ignoreCase = true) ||
                normalized.startsWith("WITH", ignoreCase = true) ||
                normalized.startsWith("EXPLAIN", ignoreCase = true)
    }

    private fun currentJournalMode(): String {
        return queryOne("PRAGMA journal_mode;") { it.getText(0) }.orEmpty()
    }

    private fun switchJournalMode(targetMode: String): String {
        return queryOne("PRAGMA journal_mode = $targetMode;") { it.getText(0) }.orEmpty()
    }

    /**
     * Libera artefactos cuantizados persistidos antes de compactar.
     *
     * `sqlite-vector` keeps quantization in the database to speed up
     * ANN; si no se elimina primero, `VACUUM` no puede recuperar ese espacio
     * aunque las filas originales ya se hayan borrado.
     */
    private fun cleanupVectorArtifactsForCompaction() {
        tables.values.forEach { table ->
            runCatching {
                table.cleanupVectorSearchArtifacts(strict = false)
            }.onFailure { error ->
                Log.w(
                    "Repository",
                    "Could not clear quantization for ${table.schema.tableName} before VACUUM: ${error.message}"
                )
            }
        }
    }

    /**
     * Determines whether an error produced during an approximate search
     * es recuperable y permite reintentar usando un escaneo completo.
     *
     * Casos contemplados:
     * - missing quantization table
     * - vector functions unavailable
     * - problemas al recuperar contexto
     * - algunos errores ambiguos reportados por el motor
     *
     * @param error Error capturado.
     * @return `true` si conviene hacer fallback a full scan.
     */
    private fun isRecoverableApproximateSearchFailure(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return message.contains("Quantization table not found", ignoreCase = true) ||
                message.contains("vector_quantize(", ignoreCase = true) ||
                message.contains("vector_quantize_scan_stream", ignoreCase = true) ||
                message.contains("unable to retrieve context", ignoreCase = true) ||
                message.contains("no such function: vector_quantize_scan_stream", ignoreCase = true) ||
                message.contains("no such function: vector_quantize_preload", ignoreCase = true) ||
                message.contains("not an error", ignoreCase = true)
    }
}
