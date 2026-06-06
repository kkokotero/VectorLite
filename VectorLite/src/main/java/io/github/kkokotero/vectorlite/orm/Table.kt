package io.github.kkokotero.vectorlite.orm

import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.flow.Flow
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Represents an ORM table associated with a specific entity.
 *
 * This class encapsulates:
 * - schema synchronization logic
 * - CRUD operations
 * - entity relationship support
 * - vector searches
 * - mantenimiento de artefactos necesarios para consultas aproximadas
 *
 * @param T Tipo de entidad asociado a la tabla.
 * @property repository Repositorio que ejecuta las operaciones contra la base de datos.
 * @property schema Esquema de la tabla derivado de la entidad.
 */
class Table<T : Any>(
    val repository: Repository,
    val schema: TableSchema<T>
) {

    /**
     * Typed relationships registered manually through the fluent API
     * `belongsTo`, `hasOne` y `hasMany`.
     */
    private val typedRelationships = mutableListOf<TypedRelationship<T>>()

    /**
     * Conjunto con los nombres de las columnas vectoriales definidas en el esquema.
     */
    private val vectorColumnNames = schema.getVectorColumns().mapTo(linkedSetOf()) { it.name }

    /**
     * Conjunto de columnas vectoriales cuyos artefactos auxiliares
     * must be regenerated or reloaded before approximate searches.
     */
    private val dirtyVectorContexts = vectorColumnNames.toMutableSet()

    /**
     * Table initialization.
     *
     * Al construirse:
     * - synchronizes the physical schema with the expected schema
     * - inicializa el soporte vectorial para las columnas correspondientes
     */
    init {
        syncSchema()
        initializeVectorSupport()
    }

    /**
     * Crea la tabla si no existe, o sincroniza el esquema si ya existe.
     *
     * This method delegates to the schema synchronization logic.
     */
    fun createTableIfNotExists() {
        syncSchema()
    }

    /**
     * Inserta una nueva entidad en la tabla.
     *
     * Comportamiento:
     * - omite la clave primaria autoincremental del `INSERT`
     * - converts vectors to blobs according to the configured element size
     * - valida dimensiones de columnas vectoriales
     * - marca artefactos vectoriales como desactualizados
     * - emite un evento de cambio de tabla
     *
     * @param entity Entidad a insertar.
     * @return `rowId` generado por SQLite, o 0 si no pudo recuperarse.
     */
    fun insert(entity: T): Long {
        val columns = schema.columns.filterNot { it.isPrimaryKey && it.isAutoIncrement }
        val columnNames = columns.joinToString(", ") { it.name }
        val placeholders = columns.joinToString(", ") { column ->
            if (column.isVector) {
                when (column.vectorElementSize) {
                    4 -> "vector_as_f32(?)"
                    2 -> "vector_as_f16(?)"
                    1 -> "vector_as_i8(?)"
                    else -> "vector_as_f32(?)"
                }
            } else "?"
        }

        val sql = """
            INSERT INTO ${schema.tableName} ($columnNames)
            VALUES ($placeholders)
        """.trimIndent()

        val params = columns.map { column ->
            val value = column.readValue(entity)
            if (column.isVector && value is FloatArray) {
                validateVectorDimensions(column, value)
                VectorConverter.floatArrayToBlob(value, column.vectorElementSize ?: 4)
            } else {
                value
            }
        }.toTypedArray()

        repository.exec(sql, params)
        markVectorSearchArtifactsDirty()
        val rowId = repository.queryOne("SELECT last_insert_rowid()") { it.getLong(0) } ?: 0L
        repository.emitTableChange(
            TableChangeEvent(
                tableName = schema.tableName,
                operation = TableOperation.INSERT,
                affectedRows = if (rowId > 0L) 1 else 0,
                rowId = if (rowId > 0L) rowId else null
            )
        )
        return rowId
    }

    /**
     * Actualiza una entidad existente a partir de su clave primaria.
     *
     * Requisitos:
     * - la tabla debe tener una clave primaria definida
     *
     * Comportamiento:
     * - actualiza todas las columnas excepto la clave primaria
     * - converts vector columns to blobs according to their type
     * - valida dimensiones vectoriales
     * - marca artefactos vectoriales como desactualizados si hubo cambios
     * - emits an update event
     *
     * @param entity Entidad a actualizar.
     * @return Cantidad de filas afectadas.
     */
    fun update(entity: T): Int {
        val primaryKey = schema.primaryKeyColumn
            ?: throw VectorLiteException("Table '${schema.tableName}' has no primary key")
        val columns = schema.columns.filterNot { it.isPrimaryKey }

        val setClause = columns.joinToString(", ") { column ->
            if (column.isVector) {
                when (column.vectorElementSize) {
                    4 -> "${column.name} = vector_as_f32(?)"
                    2 -> "${column.name} = vector_as_f16(?)"
                    1 -> "${column.name} = vector_as_i8(?)"
                    else -> "${column.name} = vector_as_f32(?)"
                }
            } else {
                "${column.name} = ?"
            }
        }

        val sql = """
            UPDATE ${schema.tableName}
            SET $setClause
            WHERE ${primaryKey.name} = ?
        """.trimIndent()

        val params = columns.map { column ->
            val value = column.readValue(entity)
            if (column.isVector && value is FloatArray) {
                validateVectorDimensions(column, value)
                VectorConverter.floatArrayToBlob(value, column.vectorElementSize ?: 4)
            } else {
                value
            }
        }.toMutableList()
        params += primaryKey.readValue(entity)

        repository.exec(sql, params.toTypedArray())
        val affected = repository.queryOne("SELECT changes()") { it.getInt(0) } ?: 0
        if (affected > 0) {
            markVectorSearchArtifactsDirty()
            repository.emitTableChange(
                TableChangeEvent(
                    tableName = schema.tableName,
                    operation = TableOperation.UPDATE,
                    affectedRows = affected
                )
            )
        }
        return affected
    }

    /**
     * Elimina una entidad usando el valor de su clave primaria.
     *
     * Requisitos:
     * - la tabla debe tener una clave primaria definida
     *
     * @param entity Entidad a eliminar.
     * @return `true` si al menos una fila fue eliminada.
     */
    fun delete(entity: T): Boolean {
        val primaryKey = schema.primaryKeyColumn
            ?: throw VectorLiteException("Table '${schema.tableName}' has no primary key")
        val affected = repository.executeAffecting(
            "DELETE FROM ${schema.tableName} WHERE ${primaryKey.name} = ?",
            arrayOf(primaryKey.readValue(entity))
        )
        if (affected > 0) {
            onRowsDeleted(affected)
        }
        return affected > 0
    }

    /**
     * Elimina filas usando una consulta previamente construida.
     *
     * Validates that the query belongs to this same table.
     *
     * @param query Delete query.
     * @return Cantidad de filas eliminadas.
     */
    fun delete(query: Query<T>): Int {
        require(query.table === this) {
            "Delete query belongs to a different table: expected ${schema.tableName}, got ${query.table.schema.tableName}"
        }
        return query.delete()
    }

    /**
     * Elimina filas que cumplan un predicado.
     *
     * @param predicate Predicate to apply in the `WHERE` clause.
     * @return Cantidad de filas eliminadas.
     */
    fun deleteWhere(predicate: QueryPredicate<T>): Int {
        return query().where(predicate).delete()
    }

    /**
     * Elimina filas usando un bloque constructor de consulta.
     *
     * This approach lets you build the query with a `Query`-based DSL.
     *
     * @param builder Bloque que configura la consulta de borrado.
     * @return Cantidad de filas eliminadas.
     */
    fun deleteWhere(builder: Query<T>.() -> Unit): Int {
        val deleteQuery = query()
        deleteQuery.builder()
        return deleteQuery.delete()
    }

    /**
     * Elimina todos los registros de la tabla.
     *
     * @return Cantidad de filas eliminadas.
     */
    fun deleteAll(): Int {
        val affected = repository.executeAffecting("DELETE FROM ${schema.tableName}")
        if (affected > 0) {
            onRowsDeleted(affected)
        }
        return affected
    }

    /**
     * Crea una nueva consulta sobre esta tabla.
     *
     * @return Empty `Query` instance.
     */
    fun query(): Query<T> = Query(this)

    /**
     * Finds a single entity by its primary key.
     */
    fun findById(id: Any): T? {
        val primaryKey = schema.primaryKeyColumn ?: return null
        return query()
            .withoutRelationships()
            .whereColumn(primaryKey.name, SqlOperator.EQUAL, id)
            .findFirst()
    }

    /**
     * Returns all rows from the table.
     */
    fun findAll(): List<T> = query().findAll()

    /**
     * Counts all rows in the table.
     */
    fun count(): Long = query().count()

    /**
     * Returns a flow with change events for this table.
     *
     * @return Flujo de eventos asociados al nombre de la tabla actual.
     */
    fun changes(): Flow<TableChangeEvent> = repository.tableChanges(schema.tableName)

    /**
     * Registers a typed many-to-one relationship.
     *
     * Ejemplo conceptual:
     * una entidad actual pertenece a una entidad destino.
     *
     * @param relation Propiedad relacional mutable en la entidad actual.
     * @param foreignKey Local property holding the foreign key.
     * @param targetTable Target table.
     * @param targetKey Clave objetivo en la entidad destino.
     * @return La misma tabla para encadenamiento.
     */
    fun <R : Any, FK> belongsTo(
        relation: KMutableProperty1<T, R?>,
        foreignKey: KProperty1<T, FK?>,
        targetTable: Table<R>,
        targetKey: KProperty1<R, FK>
    ): Table<T> {
        schema.requireColumn(foreignKey)
        targetTable.schema.requireColumn(targetKey)

        typedRelationships += BelongsToRelationship(
            relation = relation,
            foreignKey = foreignKey,
            targetTable = targetTable,
            targetKey = targetKey
        )
        return this
    }

    /**
     * Registers a typed one-to-one relationship.
     *
     * @param relation Propiedad relacional mutable en la entidad actual.
     * @param localKey Clave local de la entidad actual.
     * @param targetTable Target table.
     * @param foreignKey Foreign key in the target entity.
     * @return La misma tabla para encadenamiento.
     */
    fun <R : Any, FK> hasOne(
        relation: KMutableProperty1<T, R?>,
        localKey: KProperty1<T, FK?>,
        targetTable: Table<R>,
        foreignKey: KProperty1<R, FK?>
    ): Table<T> {
        schema.requireColumn(localKey)
        targetTable.schema.requireColumn(foreignKey)

        typedRelationships += HasOneRelationship(
            relation = relation,
            localKey = localKey,
            targetTable = targetTable,
            foreignKey = foreignKey
        )
        return this
    }

    /**
     * Registers a typed one-to-many relationship.
     *
     * @param relation Mutable relationship property that will hold the related list.
     * @param localKey Clave local de la entidad actual.
     * @param targetTable Target table.
     * @param foreignKey Foreign key in the target entity.
     * @return La misma tabla para encadenamiento.
     */
    fun <R : Any, FK> hasMany(
        relation: KMutableProperty1<T, List<R>>,
        localKey: KProperty1<T, FK?>,
        targetTable: Table<R>,
        foreignKey: KProperty1<R, FK?>
    ): Table<T> {
        schema.requireColumn(localKey)
        targetTable.schema.requireColumn(foreignKey)

        typedRelationships += HasManyRelationship(
            relation = relation,
            localKey = localKey,
            targetTable = targetTable,
            foreignKey = foreignKey
        )
        return this
    }

    /**
     * Executes a nearest-neighbor search on a vector column.
     *
     * @param vectorColumn Vector property used for the search.
     * @param queryVector Vector de consulta.
     * @param options Vector search options.
     * @return Full search result.
     */
    fun nearestNeighbors(
        vectorColumn: KProperty1<T, FloatArray>,
        queryVector: FloatArray,
        options: VectorSearchOptions = VectorSearchOptions()
    ): VectorSearchResult<T> {
        return query()
            .nearestTo(vectorColumn, queryVector, options)
            .vectorSearch()
    }

    /**
     * Returns only the nearest neighbor for a vector search.
     *
     * @param vectorColumn Propiedad vectorial usada para comparar.
     * @param queryVector Vector de consulta.
     * @param approximate Indicates whether approximate search is preferred.
     * @return Mejor coincidencia o null si no hubo resultados.
     */
    fun nearestNeighbor(
        vectorColumn: KProperty1<T, FloatArray>,
        queryVector: FloatArray,
        approximate: Boolean = false
    ): SimilarityResult<T>? {
        return nearestNeighbors(
            vectorColumn = vectorColumn,
            queryVector = queryVector,
            options = VectorSearchOptions(topK = 1, approximate = approximate)
        ).bestMatch
    }

    /**
     * Converts the current cursor row into an entity instance.
     *
     * Proceso:
     * - obtiene el constructor primario de la entidad
     * - extrae las columnas presentes desde el cursor
     * - converts primitive values and vectors
     * - coerces values to the constructor parameter type
     * - instancia la entidad con `callBy`
     * - opcionalmente carga relaciones
     *
     * @param statement Statement posicionado en una fila.
     * @param includeRelationships Indica si deben cargarse relaciones.
     * @param requestedRelationshipNames Optional set of specific relationships to load.
     * @return Entidad reconstruida desde la fila actual.
     */
    internal fun cursorToEntity(
        statement: SQLiteStatement,
        includeRelationships: Boolean = false,
        requestedRelationshipNames: Set<String> = emptySet()
    ): T {
        val constructor = schema.entityClass.primaryConstructor
            ?: error("Entity ${schema.entityClass.simpleName} must have a primary constructor")
        constructor.isAccessible = true

        val args = mutableMapOf<String, Any?>()
        val columnNames = statement.getColumnNames()
        schema.columns.forEach { column ->
            val index = columnNames.indexOf(column.name)
            if (index == -1 || statement.isNull(index)) return@forEach

            args[column.name] = when {
                column.isVector -> decodeVectorColumn(statement.getBlob(index), column)
                column.type == "INTEGER" -> statement.getLong(index)
                column.type == "REAL" -> statement.getDouble(index)
                column.type == "TEXT" -> statement.getText(index)
                column.type == "BLOB" -> statement.getBlob(index)
                else -> statement.getText(index)
            }
        }

        val constructorArgs = buildMap {
            constructor.parameters.forEach { parameter ->
                val name = parameter.name ?: return@forEach
                if (args.containsKey(name)) {
                    put(parameter, coerceToParameterType(args[name], parameter))
                }
            }
        }
        val entity = constructor.callBy(constructorArgs)
        if (includeRelationships) {
            val handledByTyped = loadTypedRelationships(entity, requestedRelationshipNames)
            loadAnnotatedRelationships(entity, args, handledByTyped, requestedRelationshipNames)
        }
        return entity
    }

    /**
     * Decodifica una columna vectorial almacenada como blob hacia `FloatArray`.
     *
     * Estrategia:
     * - tries to decode using the expected dimension
     * - if it fails, tries to infer the real dimension from the blob size
     * - if the inferred dimension differs, normalizes the result by truncating or padding
     *
     * @param blob Valor crudo almacenado en la base de datos.
     * @param column Metadatos de la columna vectorial.
     * @return Vector decodificado.
     */
    private fun decodeVectorColumn(
        blob: ByteArray,
        column: ColumnInfo
    ): FloatArray {
        val expectedDimensions = column.vectorDimensions ?: 0
        val elementSize = column.vectorElementSize ?: 4

        return try {
            VectorConverter.blobToFloatArray(blob, expectedDimensions, elementSize)
        } catch (_: IllegalArgumentException) {
            val inferredDimensions = when {
                elementSize <= 0 -> 0
                blob.size % elementSize == 0 -> blob.size / elementSize
                else -> 0
            }

            if (inferredDimensions <= 0) {
                return FloatArray(expectedDimensions)
            }

            val decoded = runCatching {
                VectorConverter.blobToFloatArray(blob, inferredDimensions, elementSize)
            }.getOrElse {
                return FloatArray(expectedDimensions)
            }

            if (expectedDimensions <= 0 || decoded.size == expectedDimensions) {
                return decoded
            }

            if (decoded.size > expectedDimensions) {
                decoded.copyOf(expectedDimensions)
            } else {
                FloatArray(expectedDimensions).also { normalized ->
                    decoded.copyInto(normalized, endIndex = decoded.size)
                }
            }
        }
    }

    /**
     * Converts a value read from SQLite to the type expected by a constructor parameter
     * del constructor de la entidad.
     *
     * This coercion mainly resolves differences between:
     * - `Long` e `Int`
     * - `Double` y `Float`
     * - `INTEGER` y `Boolean`
     * - convertible numeric or text values
     *
     * @param value Valor recuperado desde el cursor.
     * @param parameter Constructor parameter to which the value should be adapted.
     * @return Converted value, or the original value if no conversion is needed.
     */
    private fun coerceToParameterType(value: Any?, parameter: KParameter): Any? {
        if (value == null) return null
        val classifier = parameter.type.classifier

        return when (classifier) {
            Long::class -> when (value) {
                is Number -> value.toLong()
                is Boolean -> if (value) 1L else 0L
                is String -> value.toLongOrNull() ?: value
                else -> value
            }

            Int::class -> when (value) {
                is Number -> value.toInt()
                is Boolean -> if (value) 1 else 0
                is String -> value.toIntOrNull() ?: value
                else -> value
            }

            Double::class -> when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull() ?: value
                else -> value
            }

            Float::class -> when (value) {
                is Number -> value.toFloat()
                is String -> value.toFloatOrNull() ?: value
                else -> value
            }

            Boolean::class -> when (value) {
                is Boolean -> value
                is Number -> value.toInt() != 0
                is String -> value.equals("true", ignoreCase = true) || value == "1"
                else -> value
            }

            LocalDate::class -> when (value) {
                is LocalDate -> value
                is String -> runCatching { LocalDate.parse(value) }.getOrNull() ?: value
                else -> value
            }

            LocalTime::class -> when (value) {
                is LocalTime -> value
                is String -> runCatching { LocalTime.parse(value) }.getOrNull() ?: value
                else -> value
            }

            LocalDateTime::class -> when (value) {
                is LocalDateTime -> value
                is String -> runCatching { LocalDateTime.parse(value) }.getOrNull() ?: value
                else -> value
            }

            Instant::class -> when (value) {
                is Instant -> value
                is Number -> Instant.ofEpochMilli(value.toLong())
                is String -> {
                    value.toLongOrNull()?.let { Instant.ofEpochMilli(it) }
                        ?: runCatching { Instant.parse(value) }.getOrNull()
                        ?: value
                }
                else -> value
            }

            String::class -> value.toString()
            else -> value
        }
    }

    /**
     * Synchronizes the physical table schema with the entity-expected schema.
     *
     * Flujo:
     * - asegura la existencia de la tabla de registro de esquemas
     * - verifica si la tabla existe
     * - si no existe, la crea y guarda su hash
     * - if it exists and the hash differs, runs a migration
     */
    private fun syncSchema() {
        ensureSchemaRegistryTable()

        val tableExists = repository.exists(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(schema.tableName)
        )

        val currentHash = schema.schemaHash
        if (!tableExists) {
            repository.exec(buildCreateTableSql(schema.tableName))
            upsertSchemaHash(currentHash)
            return
        }

        val registeredHash = loadRegisteredSchemaHash()
        if (registeredHash != currentHash) {
            migrateToCurrentSchema(currentHash)
        }
    }

    /**
     * Crea, si no existe, la tabla interna que almacena el hash
     * del esquema registrado por cada tabla ORM.
     */
    private fun ensureSchemaRegistryTable() {
        repository.exec(
            """
            CREATE TABLE IF NOT EXISTS $SCHEMA_REGISTRY_TABLE (
                table_name TEXT PRIMARY KEY,
                schema_hash TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    /**
     * Recupera el hash del esquema actualmente registrado para esta tabla.
     *
     * @return Stored hash, or null if no record exists yet.
     */
    private fun loadRegisteredSchemaHash(): String? {
        return repository.queryOne(
            "SELECT schema_hash FROM $SCHEMA_REGISTRY_TABLE WHERE table_name = ?",
            arrayOf(schema.tableName)
        ) { it.getText(0) }
    }

    /**
     * Inserta o actualiza el hash del esquema de la tabla actual
     * en la tabla de registro de esquemas.
     *
     * @param schemaHash Hash calculado del esquema actual.
     */
    private fun upsertSchemaHash(schemaHash: String) {
        repository.exec(
            """
            INSERT INTO $SCHEMA_REGISTRY_TABLE(table_name, schema_hash, updated_at)
            VALUES(?, ?, ?)
            ON CONFLICT(table_name) DO UPDATE SET
                schema_hash = excluded.schema_hash,
                updated_at = excluded.updated_at
            """.trimIndent(),
            arrayOf(schema.tableName, schemaHash, System.currentTimeMillis())
        )
    }

    /**
     * Migrates the current table to the latest schema.
     *
     * Estrategia:
     * - disables foreign keys temporarily
     * - crea una tabla temporal con el esquema nuevo
     * - detecta columnas comunes entre el esquema antiguo y el nuevo
     * - copia los datos compatibles
     * - elimina la tabla antigua
     * - renombra la tabla temporal
     * - actualiza el hash registrado
     *
     * @param currentHash Hash del esquema nuevo.
     */
    private fun migrateToCurrentSchema(currentHash: String) {
        repository.transaction {
            exec("PRAGMA foreign_keys = OFF")
            try {
                val tempTable = "${schema.tableName}__migrated_${currentHash.take(8)}"
                cleanupVectorSearchArtifactsFor(tempTable, strict = false)
                exec("DROP TABLE IF EXISTS ${quoted(tempTable)}")
                exec(buildCreateTableSql(tempTable))

                val oldColumns = repository.queryList(
                    "PRAGMA table_info(${quoted(schema.tableName)})"
                ) { statement ->
                    val index = statement.getColumnNames().indexOf("name")
                    if (index >= 0) statement.getText(index) else ""
                }
                val newColumns = schema.columns.map { it.name }
                val commonColumns = newColumns.filter { it in oldColumns }

                if (commonColumns.isNotEmpty()) {
                    val columnsSql = commonColumns.joinToString(", ") { quoted(it) }
                    exec(
                        "INSERT INTO ${quoted(tempTable)} ($columnsSql) " +
                                "SELECT $columnsSql FROM ${quoted(schema.tableName)}"
                    )
                }

                cleanupVectorSearchArtifacts(strict = false)
                exec("DROP TABLE ${quoted(schema.tableName)}")
                exec("ALTER TABLE ${quoted(tempTable)} RENAME TO ${quoted(schema.tableName)}")
                markVectorSearchArtifactsDirty()
                upsertSchemaHash(currentHash)
            } finally {
                exec("PRAGMA foreign_keys = ON")
            }
        }
    }

    /**
     * Construye el SQL `CREATE TABLE` para el esquema actual.
     *
     * Incluye:
     * - column definitions
     * - clave primaria
     * - autoincremento
     * - nulabilidad
     * - unicidad
     * - foreign keys y sus acciones referenciales
     *
     * @param targetTableName Nombre de la tabla a crear.
     * @return Sentencia SQL completa.
     */
    private fun buildCreateTableSql(targetTableName: String): String {
        val columnDefinitions = schema.columns.map { column ->
            buildString {
                append("${quoted(column.name)} ${column.type}")
                if (column.isPrimaryKey) {
                    append(" PRIMARY KEY")
                    if (column.isAutoIncrement) append(" AUTOINCREMENT")
                }
                if (!column.isNullable) append(" NOT NULL")
                if (column.isUnique) append(" UNIQUE")
            }
        }.toMutableList()

        schema.columns
            .filter { it.foreignKey != null }
            .forEach { column ->
                val fk = column.foreignKey!!
                val targetTable = repository.table(fk.entity).schema.tableName
                columnDefinitions.add(
                    "FOREIGN KEY(${quoted(column.name)}) REFERENCES ${quoted(targetTable)}(${quoted(fk.field)}) " +
                            "ON DELETE ${fk.onDelete.name.replace("_", " ")} " +
                            "ON UPDATE ${fk.onUpdate.name.replace("_", " ")}"
                )
            }

        return """
            CREATE TABLE IF NOT EXISTS ${quoted(targetTableName)} (
                ${columnDefinitions.joinToString(",\n")}
            )
        """.trimIndent()
    }

    /**
     * Inicializa el soporte vectorial para cada columna vectorial del esquema.
     *
     * Para cada columna:
     * - determines the vector physical type
     * - determines the distance metric
     * - ejecuta `vector_init(...)`
     *
     * If an error occurs, it is logged without stopping initialization.
     */
    private fun initializeVectorSupport() {
        schema.getVectorColumns().forEach { column ->
            val type = when (column.vectorElementSize) {
                4 -> "FLOAT32"
                2 -> "FLOAT16"
                1 -> "INT8"
                else -> "FLOAT32"
            }

            val distance = when (column.distanceMetric) {
                DistanceMetric.L2 -> "L2"
                DistanceMetric.SQUARED_L2 -> "SQUARED_L2"
                DistanceMetric.L1 -> "L1"
                DistanceMetric.COSINE -> "COSINE"
                DistanceMetric.DOT_PRODUCT -> "DOT"
                null -> "COSINE"
            }

            try {
                repository.exec(
                    "SELECT vector_init('${schema.tableName}', '${column.name}', " +
                            "'dimension=${column.vectorDimensions},type=$type,distance=$distance')"
                )
            } catch (e: Exception) {
                android.util.Log.w("Table", "Vector init failed on ${schema.tableName}.${column.name}: ${e.message}")
            }
        }
    }

    /**
     * Prepares a vector column for approximate searches.
     *
     * Flujo:
     * - verifica que la columna exista dentro del conjunto vectorial
     * - si la tabla no tiene filas, marca el contexto como sucio y sale
     * - if the context is dirty, it tries to quantize
     * - luego intenta precargar el contexto cuantizado
     * - if preparation fails with a recoverable error and `strict` is false,
     *   el contexto queda marcado como sucio para un intento posterior
     *
     * @param columnName Nombre de la columna vectorial.
     * @param strict Si es `true`, propaga ciertos errores en lugar de tolerarlos.
     */
    internal fun prepareApproximateVectorSearch(columnName: String, strict: Boolean = true) {
        if (columnName !in vectorColumnNames) return

        if (!tableHasRows()) {
            cleanupVectorSearchArtifacts(strict = false)
            return
        }

        if (columnName in dirtyVectorContexts) {
            try {
                repository.exec(
                    "SELECT vector_quantize(?, ?)",
                    arrayOf(schema.tableName, columnName)
                )
            } catch (e: Exception) {
                val message = e.message.orEmpty()
                val canIgnore = message.contains("already", ignoreCase = true) ||
                        message.contains("exists", ignoreCase = true) ||
                        message.contains("not an error", ignoreCase = true)
                if (!canIgnore) throw e
            }
        }

        try {
            repository.exec(
                "SELECT vector_quantize_preload(?, ?)",
                arrayOf(schema.tableName, columnName)
            )
            dirtyVectorContexts.remove(columnName)
        } catch (e: Exception) {
            if (strict || !isRecoverableVectorPreparationError(e)) {
                throw e
            }
            // Initialization can happen before enough information exists
            // o contexto para realizar el preload correctamente.
            dirtyVectorContexts.add(columnName)
        }
    }

    /**
     * Marca todos los contextos vectoriales como desactualizados.
     *
     * Should be called after inserts, updates, or deletes
     * that may affect approximate searches.
     */
    internal fun markVectorSearchArtifactsDirty() {
        if (vectorColumnNames.isEmpty()) return
        dirtyVectorContexts.addAll(vectorColumnNames)
    }

    /**
     * Clears persisted quantization associated with the vector columns
     * de la tabla actual.
     *
     * Esto permite que un `VACUUM` posterior recupere realmente el espacio
     * occupied by stale ANN artifacts. After cleanup, quantization
     * will be rebuilt lazily the next time a search is used
     * aproximada.
     *
     * @param strict Si es `true`, propaga errores no recuperables.
     */
    internal fun cleanupVectorSearchArtifacts(strict: Boolean = false) {
        cleanupVectorSearchArtifactsFor(schema.tableName, strict = strict)
    }

    /**
     * Handles the additional work required after deleting rows:
     * - invalida artefactos vectoriales
     * - clears quantization if the table became empty
     * - emite el evento de cambio correspondiente
     *
     * @param affected Cantidad de filas eliminadas.
     */
    internal fun onRowsDeleted(affected: Int) {
        if (affected <= 0) return
        markVectorSearchArtifactsDirty()
        if (!tableHasRows()) {
            cleanupVectorSearchArtifacts(strict = false)
        }
        repository.refreshCascadeVectorArtifacts(schema.entityClass)
        repository.emitTableChange(
            TableChangeEvent(
                tableName = schema.tableName,
                operation = TableOperation.DELETE,
                affectedRows = affected
            )
        )
    }

    /**
     * Revalida artefactos vectoriales cuando la tabla pudo haber cambiado por
     * side effects of a referential action triggered by SQLite.
     */
    internal fun refreshVectorArtifactsAfterExternalDelete() {
        markVectorSearchArtifactsDirty()
        if (!tableHasRows()) {
            cleanupVectorSearchArtifacts(strict = false)
        }
    }

    /**
     * Verifica si la tabla contiene al menos una fila.
     *
     * @return `true` si existe al menos un registro.
     */
    private fun tableHasRows(): Boolean {
        return repository.exists(
            "SELECT 1 FROM ${quoted(schema.tableName)} LIMIT 1"
        )
    }

    /**
     * Determines whether an error that occurred during vector preparation
     * puede considerarse recuperable.
     *
     * @param error Error capturado.
     * @return `true` si el error admite reintento posterior o fallback.
     */
    private fun isRecoverableVectorPreparationError(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return message.contains("vector_quantize_preload", ignoreCase = true) ||
                message.contains("vector_quantize(", ignoreCase = true) ||
                message.contains("Ensure that vector_quantize() has been called", ignoreCase = true) ||
                message.contains("unable to retrieve context", ignoreCase = true) ||
                message.contains("not an error", ignoreCase = true)
    }

    /**
     * Clears persisted quantization for a specific table name.
     *
     * Used for both the current table and temporary migration tables that
     * may have left orphaned artifacts in an execution
     * anterior.
     */
    private fun cleanupVectorSearchArtifactsFor(
        tableName: String,
        strict: Boolean = false
    ) {
        if (vectorColumnNames.isEmpty()) return

        vectorColumnNames.forEach { columnName ->
            try {
                repository.exec(
                    "SELECT vector_quantize_cleanup(?, ?)",
                    arrayOf(tableName, columnName)
                )

                if (tableName == schema.tableName) {
                    dirtyVectorContexts.add(columnName)
                }
            } catch (error: Exception) {
                if (strict || !isRecoverableVectorCleanupError(error)) {
                    throw error
                }
            }
        }
    }

    private fun isRecoverableVectorCleanupError(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return message.contains("vector_quantize_cleanup", ignoreCase = true) ||
                message.contains("unable to retrieve context", ignoreCase = true) ||
                message.contains("no such function: vector_quantize_cleanup", ignoreCase = true) ||
                message.contains("not found", ignoreCase = true) ||
                message.contains("not an error", ignoreCase = true)
    }

    /**
     * Carga relaciones registradas mediante la API tipada.
     *
     * Solo carga:
     * - all relationships, if no subset was requested
     * - or only the relationships included in `requestedRelationships`
     *
     * @param entity Entidad sobre la cual se deben cargar relaciones.
     * @param requestedRelationships Conjunto de nombres de relaciones solicitadas.
     * @return Conjunto con los nombres de relaciones ya resueltas.
     */
    private fun loadTypedRelationships(entity: T, requestedRelationships: Set<String>): Set<String> {
        val handledProperties = mutableSetOf<String>()
        typedRelationships.forEach { relation ->
            if (requestedRelationships.isNotEmpty() && relation.propertyName !in requestedRelationships) {
                return@forEach
            }
            relation.load(entity)
            handledProperties += relation.propertyName
        }
        return handledProperties
    }

    /**
     * Carga relaciones declaradas mediante anotaciones en el esquema.
     *
     * If a relationship was already resolved by the typed API, it is not loaded again.
     *
     * Casos soportados:
     * - MANY_TO_ONE
     * - ONE_TO_MANY
     * - ONE_TO_ONE
     *
     * @param entity Entidad sobre la cual asignar las relaciones.
     * @param baseArgs Base values for columns already read from the cursor.
     * @param handledProperties Relationships already handled by the typed API.
     * @param requestedRelationships Conjunto opcional de nombres de relaciones solicitadas.
     */
    private fun loadAnnotatedRelationships(
        entity: T,
        baseArgs: Map<String, Any?>,
        handledProperties: Set<String>,
        requestedRelationships: Set<String>
    ) {
        schema.relationships.forEach { relationship ->
            if (relationship.propertyName in handledProperties) return@forEach
            if (requestedRelationships.isNotEmpty() && relationship.propertyName !in requestedRelationships) {
                return@forEach
            }
            val targetTable = repository.table(relationship.targetEntity)
            when (relationship.type) {
                RelationshipType.MANY_TO_ONE -> {
                    val foreignKeyColumn = resolveManyToOneForeignKeyColumn(relationship)
                    if (foreignKeyColumn != null) {
                        val foreignKeyValue = baseArgs[foreignKeyColumn.name]
                        if (foreignKeyValue != null) {
                            val related = targetTable.query()
                                .withoutRelationships()
                                .whereColumn(resolveManyToOneTargetKey(foreignKeyColumn), SqlOperator.EQUAL, foreignKeyValue)
                                .findFirst()
                            assignRelationship(entity, relationship.propertyName, related)
                        }
                    }
                }

                RelationshipType.ONE_TO_MANY -> {
                    val localKeyValue = resolveLocalPrimaryKeyValue(entity, baseArgs)
                    val mappedByColumn = resolveMappedByColumn(relationship, targetTable)
                    if (localKeyValue != null && mappedByColumn != null) {
                        val relatedList = targetTable.query()
                            .withoutRelationships()
                            .whereColumn(mappedByColumn, SqlOperator.EQUAL, localKeyValue)
                            .findAll()
                        assignRelationship(entity, relationship.propertyName, relatedList)
                    }
                }

                RelationshipType.ONE_TO_ONE -> {
                    val localKeyValue = resolveLocalPrimaryKeyValue(entity, baseArgs)
                    val mappedByColumn = resolveMappedByColumn(relationship, targetTable)
                    if (localKeyValue != null && mappedByColumn != null) {
                        val related = targetTable.query()
                            .withoutRelationships()
                            .whereColumn(mappedByColumn, SqlOperator.EQUAL, localKeyValue)
                            .findFirst()
                        assignRelationship(entity, relationship.propertyName, related)
                    }
                }
            }
        }
    }

    /**
     * Attempts to resolve the foreign key column for a MANY_TO_ONE relationship.
     *
     * Estrategia:
     * - first looks for a column whose name follows the `${propertyName}Id` convention
     * - if none exists, takes the first column with a foreign key to the target entity
     *
     * @param relationship Relationship to resolve.
     * @return Foreign key column, or null if it cannot be resolved.
     */
    private fun resolveManyToOneForeignKeyColumn(relationship: RelationshipInfo): ColumnInfo? {
        return schema.columns.find {
            it.foreignKey?.entity == relationship.targetEntity &&
                    it.propertyName == "${relationship.propertyName}Id"
        } ?: schema.columns.find { it.foreignKey?.entity == relationship.targetEntity }
    }

    /**
     * Resolves the target field name for a MANY_TO_ONE foreign key.
     *
     * If the foreign key metadata does not define an explicit field, uses `id`.
     *
     * @param foreignKeyColumn Foreign key column.
     * @return Nombre del campo destino.
     */
    private fun resolveManyToOneTargetKey(foreignKeyColumn: ColumnInfo): String {
        return foreignKeyColumn.foreignKey?.field ?: "id"
    }

    /**
     * Obtiene el valor de la clave primaria local de la entidad.
     *
     * First tries to use the value already read from the cursor; if absent,
     * lo obtiene leyendo la propiedad directamente desde la instancia.
     *
     * @param entity Entidad actual.
     * @param baseArgs Base arguments extracted from the cursor.
     * @return Valor de la clave primaria local, o null si no existe.
     */
    private fun resolveLocalPrimaryKeyValue(entity: T, baseArgs: Map<String, Any?>): Any? {
        val primaryKey = schema.primaryKeyColumn ?: return null
        return baseArgs[primaryKey.name] ?: primaryKey.readValue(entity)
    }

    /**
     * Resuelve la columna `mappedBy` en la tabla destino para relaciones inversas.
     *
     * Reglas:
     * - if the annotation defines `mappedBy`, it is used directly
     * - otherwise, it tries to infer it by looking for foreign keys to the current entity
     * - if more than one option is found, it throws an ambiguity exception
     *
     * @param relationship Relationship to resolve.
     * @param targetTable Target table.
     * @return Nombre de la columna que referencia a la entidad actual, o null si no existe.
     */
    private fun resolveMappedByColumn(
        relationship: RelationshipInfo,
        targetTable: Table<*>
    ): String? {
        if (relationship.mappedBy.isNotBlank()) return relationship.mappedBy

        val candidates = targetTable.schema.columns
            .filter { it.foreignKey?.entity == schema.entityClass }

        return when {
            candidates.isEmpty() -> null
            candidates.size == 1 -> candidates.first().name
            else -> throw VectorLiteException.General(
                "Relationship '${schema.entityClass.simpleName}.${relationship.propertyName}' is ambiguous. " +
                        "Use @Relationship(mappedBy = ...) or typed relation APIs (hasMany/hasOne/belongsTo)."
            )
        }
    }

    /**
     * Contrato interno para relaciones tipadas que saben cargar su propio valor.
     */
    private sealed interface TypedRelationship<Owner : Any> {
        val propertyName: String
        fun load(owner: Owner)
    }

    /**
     * Implementation of a typed many-to-one relationship.
     *
     * Given a local foreign key, looks up a target entity and assigns it
     * a la propiedad relacional correspondiente.
     */
    private class BelongsToRelationship<Owner : Any, Target : Any, FK>(
        val relation: KMutableProperty1<Owner, Target?>,
        val foreignKey: KProperty1<Owner, FK?>,
        val targetTable: Table<Target>,
        val targetKey: KProperty1<Target, FK>
    ) : TypedRelationship<Owner> {
        override val propertyName: String = relation.name

        override fun load(owner: Owner) {
            val keyValue = foreignKey.get(owner)
            if (keyValue == null) {
                relation.set(owner, null)
                return
            }
            val target = targetTable.query()
                .withoutRelationships()
                .where(targetKey equal keyValue)
                .findFirst()
            relation.set(owner, target)
        }
    }

    /**
     * Implementation of a typed one-to-one relationship.
     *
     * Looks up a single target entity whose foreign key points
     * a la clave local del propietario.
     */
    private class HasOneRelationship<Owner : Any, Target : Any, FK>(
        val relation: KMutableProperty1<Owner, Target?>,
        val localKey: KProperty1<Owner, FK?>,
        val targetTable: Table<Target>,
        val foreignKey: KProperty1<Target, FK?>
    ) : TypedRelationship<Owner> {
        override val propertyName: String = relation.name

        override fun load(owner: Owner) {
            val ownerKey = localKey.get(owner)
            if (ownerKey == null) {
                relation.set(owner, null)
                return
            }
            val target = targetTable.query()
                .withoutRelationships()
                .where(foreignKey equal ownerKey)
                .findFirst()
            relation.set(owner, target)
        }
    }

    /**
     * Implementation of a typed one-to-many relationship.
     *
     * Looks up all target entities whose foreign key points
     * a la clave local del propietario.
     */
    private class HasManyRelationship<Owner : Any, Target : Any, FK>(
        val relation: KMutableProperty1<Owner, List<Target>>,
        val localKey: KProperty1<Owner, FK?>,
        val targetTable: Table<Target>,
        val foreignKey: KProperty1<Target, FK?>
    ) : TypedRelationship<Owner> {
        override val propertyName: String = relation.name

        override fun load(owner: Owner) {
            val ownerKey = localKey.get(owner)
            if (ownerKey == null) {
                relation.set(owner, emptyList())
                return
            }
            val related = targetTable.query()
                .withoutRelationships()
                .where(foreignKey equal ownerKey)
                .findAll()
            relation.set(owner, related)
        }
    }

    /**
     * Assigns a relationship value to a mutable property via reflection.
     *
     * If the property does not exist, is not mutable, or assignment fails,
     * el error se ignora silenciosamente.
     *
     * @param entity Entidad a modificar.
     * @param propertyName Nombre de la propiedad relacional.
     * @param value Valor a asignar.
     */
    private fun assignRelationship(entity: T, propertyName: String, value: Any?) {
        try {
            val property = entity::class.members.find { it.name == propertyName }
            if (property is KMutableProperty<*>) {
                property.isAccessible = true
                property.setter.call(entity, value)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Validates that a vector has the expected dimension for the column.
     *
     * @param column Vector column.
     * @param value Vector a validar.
     * @throws VectorLiteException.InvalidVectorDimension If the size does not match.
     */
    private fun validateVectorDimensions(column: ColumnInfo, value: FloatArray) {
        val expectedDimensions = column.vectorDimensions
        if (expectedDimensions != null && value.size != expectedDimensions) {
            throw VectorLiteException.InvalidVectorDimension(
                "Expected $expectedDimensions dimensions for '${column.name}', got ${value.size}"
            )
        }
    }

    /**
     * Escapa e incluye entre comillas dobles un identificador SQL.
     *
     * Used for table and column names.
     *
     * @param identifier Identificador a escapar.
     * @return Identificador entrecomillado y escapado.
     */
    private fun quoted(identifier: String): String {
        return "\"${identifier.replace("\"", "\"\"")}\""
    }

    companion object {

        /**
         * Name of the internal table used to store the hash
         * of the schema for each table managed by the ORM.
         */
        private const val SCHEMA_REGISTRY_TABLE = "vectorialdb_schema_registry"
    }
}
