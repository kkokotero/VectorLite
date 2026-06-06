package io.github.kkokotero.vectorlite.orm

import kotlin.reflect.KProperty1

/**
 * SQL operators supported by the query builder.
 *
 * Each enum entry stores the exact SQL token used when compiling
 * predicates into an executable expression.
 *
 * @property token Textual SQL representation of the operator.
 */
enum class SqlOperator(val token: String) {
    /** Equality operator. Example SQL: `=`. */
    EQUAL("="),

    /** Inequality operator. Example SQL: `!=`. */
    NOT_EQUAL("!="),

    /** Greater-than operator. Example SQL: `>`. */
    GREATER_THAN(">"),

    /** Greater-than-or-equal operator. Example SQL: `>=`. */
    GREATER_THAN_OR_EQUAL(">="),

    /** Less-than operator. Example SQL: `<`. */
    LESS_THAN("<"),

    /** Less-than-or-equal operator. Example SQL: `<=`. */
    LESS_THAN_OR_EQUAL("<="),

    /** Pattern-matching operator. Example SQL: `LIKE`. */
    LIKE("LIKE")
}

/**
 * Configuration for vector similarity searches.
 *
 * Controls how many results to return, whether the query should use
 * an approximate scan, and whether a minimum similarity threshold applies.
 *
 * @property topK Maximum number of results to return.
 * @property approximate Whether the search should use an approximate scan.
 * @property minSimilarity Minimum accepted similarity score.
 */
data class VectorSearchOptions(
    val topK: Long = 10,
    val approximate: Boolean = false,
    val minSimilarity: Float? = null
)

/**
 * Base contract for any query predicate.
 *
 * A predicate is a condition that can be compiled to SQL and can append
 * positional arguments to a mutable parameter list.
 *
 * @param T Entity type associated with the query.
 */
sealed interface QueryPredicate<T : Any> {

    /**
     * Compiles the predicate into a SQL expression.
     *
     * @param schema Table schema used to resolve columns.
     * @param params Mutable list where query arguments are appended.
     * @return Compiled SQL expression.
     */
    fun compile(schema: TableSchema<T>, params: MutableList<Any?>): String
}

/**
 * Simple binary predicate composed of:
 * - a property
 * - an SQL operator
 * - a value
 *
 * Example:
 * `age > 18`
 *
 * @param T Entity type.
 * @property property Model property used in the condition.
 * @property operator SQL operator to apply.
 * @property value Value to compare.
 */
data class BinaryPredicate<T : Any>(
    val property: KProperty1<T, *>,
    val operator: SqlOperator,
    val value: Any?
) : QueryPredicate<T> {

    /**
     * Converts the binary predicate into a SQL expression by resolving
     * the column name from the schema.
     *
     * If the value is null:
     * - `EQUAL` becomes `IS NULL`
     * - `NOT_EQUAL` becomes `IS NOT NULL`
     *
     * Other operators do not accept null.
     */
    override fun compile(schema: TableSchema<T>, params: MutableList<Any?>): String {
        val column = schema.requireColumn(property)
        if (value == null) {
            return when (operator) {
                SqlOperator.EQUAL -> "${column.name} IS NULL"
                SqlOperator.NOT_EQUAL -> "${column.name} IS NOT NULL"
                else -> throw IllegalArgumentException(
                    "Operator ${operator.name} does not accept null for column '${column.name}'"
                )
            }
        }

        params.add(value)
        return "${column.name} ${operator.token} ?"
    }
}

/**
 * `IN` predicate, useful for checking whether a column belongs to a
 * set of accepted values.
 *
 * Example:
 * `status IN ('ACTIVE', 'PENDING')`
 *
 * @param T Entity type.
 * @property property Property being evaluated.
 * @property values Accepted values.
 */
data class InPredicate<T : Any>(
    val property: KProperty1<T, *>,
    val values: List<Any?>
) : QueryPredicate<T> {

    /**
     * Compiles the predicate to SQL using placeholders for every value.
     *
     * If the value list is empty, returns `1 = 0` to force an impossible
     * condition and avoid generating an invalid `IN ()` clause.
     */
    override fun compile(schema: TableSchema<T>, params: MutableList<Any?>): String {
        if (values.isEmpty()) return "1 = 0"
        val column = schema.requireColumn(property)
        val placeholders = values.joinToString(", ") { "?" }
        params.addAll(values)
        return "${column.name} IN ($placeholders)"
    }
}

/**
 * Ordering specification for a query.
 *
 * @param T Entity type.
 * @property property Property used for ordering.
 * @property ascending Whether the sort is ascending.
 */
data class SortSpec<T : Any>(
    val property: KProperty1<T, *>,
    val ascending: Boolean
)

/** Creates an equality predicate for a property. Example: `User::name equal "Ana"`. */
infix fun <T : Any, V> KProperty1<T, V>.equal(value: V?): QueryPredicate<T> =
    BinaryPredicate(this, SqlOperator.EQUAL, value)

/** Creates an inequality predicate for a property. */
infix fun <T : Any, V> KProperty1<T, V>.notEqual(value: V?): QueryPredicate<T> =
    BinaryPredicate(this, SqlOperator.NOT_EQUAL, value)

/** Creates a `>` predicate for a property. */
infix fun <T : Any, V> KProperty1<T, V>.greaterThan(value: V): QueryPredicate<T> =
    BinaryPredicate(this, SqlOperator.GREATER_THAN, value)

/** Creates a `>=` predicate for a property. */
infix fun <T : Any, V> KProperty1<T, V>.greaterThanOrEqual(value: V): QueryPredicate<T> =
    BinaryPredicate(this, SqlOperator.GREATER_THAN_OR_EQUAL, value)

/** Creates a `<` predicate for a property. */
infix fun <T : Any, V> KProperty1<T, V>.lessThan(value: V): QueryPredicate<T> =
    BinaryPredicate(this, SqlOperator.LESS_THAN, value)

/** Creates a `<=` predicate for a property. */
infix fun <T : Any, V> KProperty1<T, V>.lessThanOrEqual(value: V): QueryPredicate<T> =
    BinaryPredicate(this, SqlOperator.LESS_THAN_OR_EQUAL, value)

/** Creates a `LIKE` predicate for a property. */
infix fun <T : Any> KProperty1<T, *>.matches(pattern: String): QueryPredicate<T> =
    BinaryPredicate(this, SqlOperator.LIKE, pattern)

/** Creates an `IN` predicate for a property from a collection. */
infix fun <T : Any, V> KProperty1<T, V>.inside(values: Collection<V>): QueryPredicate<T> =
    InPredicate(this, values.map { it as Any? })

/** Creates an ascending sort specification. */
fun <T : Any> KProperty1<T, *>.ascending(): SortSpec<T> = SortSpec(this, true)

/** Creates a descending sort specification. */
fun <T : Any> KProperty1<T, *>.descending(): SortSpec<T> = SortSpec(this, false)

/**
 * A composable query against an ORM table.
 *
 * Supports filtering, sorting, pagination, vector search, counting,
 * and deletion while keeping the SQL fragments and bound parameters
 * internally.
 *
 * @param T Entity type queried by this instance.
 * @property table Table associated with the query.
 * @property conditions Internal SQL conditions.
 * @property orderBy Internal ORDER BY clauses.
 * @property limit Maximum number of returned rows.
 * @property offset Row offset.
 * @property parameters Internal SQL arguments.
 */
class Query<T : Any>(
    val table: Table<T>,
    private val conditions: MutableList<String> = mutableListOf(),
    private val orderBy: MutableList<String> = mutableListOf(),
    private var limit: Long? = null,
    private var offset: Long? = null,
    private val parameters: MutableList<Any?> = mutableListOf(),
    internal var includeSimilarity: Boolean = false,
    internal var similarityColumn: String? = null,
    internal var queryVector: FloatArray? = null,
    internal var useApproximateSearch: Boolean = false,
    internal var includeRelationships: Boolean = false,
    internal var minSimilarityThreshold: Float? = null,
    internal val requestedRelationshipNames: MutableSet<String> = linkedSetOf()
) {

    /**
     * Adds the first condition to the query.
     *
     * If the query already has conditions, the internal helper picks the
     * default logical connector automatically.
     *
     * @param predicate Predicate to add.
     * @return The same query instance for chaining.
     */
    fun where(predicate: QueryPredicate<T>): Query<T> {
        addPredicate(predicate, connector = null)
        return this
    }

    /** Adds a condition connected with `AND`. */
    fun andWhere(predicate: QueryPredicate<T>): Query<T> {
        addPredicate(predicate, connector = "AND")
        return this
    }

    /** Adds a condition connected with `OR`. */
    fun orWhere(predicate: QueryPredicate<T>): Query<T> {
        addPredicate(predicate, connector = "OR")
        return this
    }

    /** Adds a `LIKE` condition joined with `AND`. */
    fun whereMatches(property: KProperty1<T, *>, pattern: String): Query<T> = andWhere(property matches pattern)

    /** Adds a `LIKE` condition joined with `OR`. */
    fun orWhereMatches(property: KProperty1<T, *>, pattern: String): Query<T> = orWhere(property matches pattern)

    /** Adds an `IN` condition joined with `AND`. */
    fun <V> whereInside(property: KProperty1<T, V>, values: Collection<V>): Query<T> = andWhere(property inside values)

    /**
     * Adds a condition directly by column name, without requiring a model property.
     *
     * Useful internally for advanced compositions.
     *
     * @param column Column name.
     * @param operator SQL operator.
     * @param value Comparison value.
     * @return The same query instance.
     */
    internal fun whereColumn(column: String, operator: SqlOperator, value: Any?): Query<T> {
        addColumnCondition(column, operator, value, connector = null)
        return this
    }

    /**
     * Configures a vector similarity search on a vector column.
     *
     * This method validates the query vector size, sets the result limit,
     * selects approximate mode when requested, and stores an optional
     * minimum similarity threshold.
     *
     * @param column Vector property on the entity.
     * @param vector Query vector.
     * @param options Vector search options.
     * @return The same query instance.
     */
    fun nearestTo(
        column: KProperty1<T, FloatArray>,
        vector: FloatArray,
        options: VectorSearchOptions = VectorSearchOptions()
    ): Query<T> {
        configureSimilarity(table.schema.requireVectorColumn(column), vector, options.topK)
        useApproximateSearch = options.approximate
        minSimilarityThreshold = options.minSimilarity
        return this
    }

    /** Sets a manual minimum similarity threshold. The value must be between 0 and 1. */
    fun withMinSimilarity(minSimilarity: Float): Query<T> {
        require(minSimilarity in 0f..1f) { "minSimilarity must be between 0 and 1" }
        minSimilarityThreshold = minSimilarity
        return this
    }

    /** Loads all available relationships and clears any previous selection. */
    fun withRelationships(): Query<T> {
        includeRelationships = true
        requestedRelationshipNames.clear()
        return this
    }

    /** Loads only the specified relationships. */
    fun withRelationships(vararg relationships: KProperty1<T, *>): Query<T> {
        includeRelationships = true
        requestedRelationshipNames.clear()
        relationships.forEach { requestedRelationshipNames += it.name }
        return this
    }

    /** Disables relationship loading. */
    fun withoutRelationships(): Query<T> {
        includeRelationships = false
        requestedRelationshipNames.clear()
        return this
    }

    /** Adds an ORDER BY clause using a property. */
    fun orderBy(property: KProperty1<T, *>, ascending: Boolean = true): Query<T> {
        val columnName = table.schema.requireColumn(property).name
        orderBy.add("$columnName ${if (ascending) "ASC" else "DESC"}")
        return this
    }

    /** Adds an ORDER BY clause using a prebuilt sort specification. */
    fun orderBy(sort: SortSpec<T>): Query<T> = orderBy(sort.property, sort.ascending)

    /** Sets the maximum number of rows to return. */
    fun limit(limit: Long): Query<T> {
        this.limit = limit
        return this
    }

    /** Sets the row offset. */
    fun offset(offset: Long): Query<T> {
        this.offset = offset
        return this
    }

    /**
     * Builds the final SQL statement and bound arguments.
     *
     * If the query was configured for vector similarity, delegates to
     * `buildVectorScanQuery`.
     *
     * @return A pair containing the SQL statement and its arguments.
     */
    fun build(): Pair<String, Array<Any?>> {
        if (includeSimilarity && similarityColumn != null && queryVector != null) {
            return buildVectorScanQuery()
        }

        val sql = StringBuilder("SELECT * FROM ${table.schema.tableName}")
        if (conditions.isNotEmpty()) sql.append(" WHERE ${conditions.joinToString(" ")}")
        if (orderBy.isNotEmpty()) sql.append(" ORDER BY ${orderBy.joinToString(", ")}")
        limit?.let { sql.append(" LIMIT $it") }
        offset?.let { sql.append(" OFFSET $it") }
        return sql.toString() to parameters.toTypedArray()
    }

    /** Executes the query and returns all matching entities. */
    fun findAll(): List<T> = table.repository.findAll(this)

    /** Executes the query and returns the first result, if any. */
    fun findFirst(): T? = table.repository.findFirst(this)

    /** Returns `true` when the query matches at least one row. */
    fun exists(): Boolean = findFirst() != null

    /**
     * Executes a vector similarity search and returns enriched results.
     *
     * If a minimum similarity threshold is configured, the results are
     * filtered before returning.
     */
    fun vectorSearch(): VectorSearchResult<T> {
        val result = table.repository.findAllWithSimilarity(this)
        val threshold = minSimilarityThreshold
        return if (threshold != null) result.filterBySimilarity(threshold) else result
    }

    /** Counts the rows matching the current conditions. */
    fun count(): Long = table.repository.count(table.schema.tableName, conditions, parameters)

    /**
     * Executes a DELETE operation using the current conditions.
     *
     * Restrictions:
     * - vector similarity is not supported
     * - ORDER BY is not supported
     * - OFFSET is not supported
     * - LIMIT is not supported
     *
     * If rows are deleted, vector artifacts are invalidated and a table
     * change event is emitted.
     */
    fun delete(): Int {
        check(!includeSimilarity) { "Delete queries do not support vector similarity search." }
        check(orderBy.isEmpty()) { "Delete queries do not support ORDER BY." }
        check(offset == null) { "Delete queries do not support OFFSET." }
        check(limit == null) { "Delete queries do not support LIMIT." }

        val sql = buildString {
            append("DELETE FROM ${table.schema.tableName}")
            if (conditions.isNotEmpty()) {
                append(" WHERE ${conditions.joinToString(" ")}")
            }
        }

        val affected = table.repository.executeAffecting(sql, parameters.toTypedArray())
        if (affected > 0) {
            table.onRowsDeleted(affected)
        }
        return affected
    }

    /**
     * Builds the specialized SQL query used for vector scanning.
     *
     * The query uses the engine's vector scan function, converts the input
     * vector to the expected format, joins the base table to recover the
     * full row, and orders results by ascending distance.
     */
    private fun buildVectorScanQuery(): Pair<String, Array<Any?>> {
        val vectorColumnName = similarityColumn
            ?: throw VectorLiteException.ColumnNotFound("Vector column not configured for similarity query")
        val columnInfo = table.schema.requireVectorColumn(vectorColumnName)

        val scanFunction = if (useApproximateSearch) "vector_quantize_scan_stream" else "vector_full_scan_stream"
        val typeFunc = when (columnInfo.vectorElementSize) {
            4 -> "vector_as_f32"
            2 -> "vector_as_f16"
            1 -> "vector_as_i8"
            else -> "vector_as_f32"
        }

        val vector = queryVector
            ?: throw VectorLiteException.InvalidVectorDimension("Query vector is required for similarity search")

        val sql = StringBuilder()
        sql.append("SELECT t.*, v.distance ")
        sql.append("FROM $scanFunction('${table.schema.tableName}', '$vectorColumnName', $typeFunc(?)) as v ")
        sql.append("JOIN ${table.schema.tableName} as t ON t.rowid = v.rowid")

        val buildParams = mutableListOf<Any?>(
            VectorConverter.floatArrayToBlob(vector, columnInfo.vectorElementSize ?: 4)
        )

        if (conditions.isNotEmpty()) {
            sql.append(" WHERE ${conditions.joinToString(" ")}")
            buildParams.addAll(parameters)
        }

        sql.append(" ORDER BY v.distance ASC")
        limit?.let { sql.append(" LIMIT $it") }
        offset?.let { sql.append(" OFFSET $it") }

        return sql.toString() to buildParams.toTypedArray()
    }

    /**
     * Internal vector similarity configuration helper.
     *
     * Validates the query vector length against the configured column,
     * enables similarity mode, and applies the `topK` limit.
     */
    private fun configureSimilarity(column: ColumnInfo, vector: FloatArray, topK: Long): Query<T> {
        val dimensions = column.vectorDimensions
            ?: throw VectorLiteException.InvalidVectorDimension(
                "Vector column '${column.name}' has no configured dimensions"
            )
        if (vector.size != dimensions) {
            throw VectorLiteException.InvalidVectorDimension(
                "Vector dimension mismatch for '${column.name}': expected $dimensions, got ${vector.size}"
            )
        }
        includeSimilarity = true
        similarityColumn = column.name
        queryVector = vector
        limit = topK
        return this
    }

    /** Compiles a predicate and appends it to the internal conditions list. */
    private fun addPredicate(predicate: QueryPredicate<T>, connector: String?) {
        val localParams = mutableListOf<Any?>()
        val expression = predicate.compile(table.schema, localParams)
        addRawCondition("($expression)", values = localParams, connector = connector)
    }

    /**
     * Adds a condition using a raw column name.
     *
     * Null comparisons are handled correctly:
     * - `=` becomes `IS NULL`
     * - `!=` becomes `IS NOT NULL`
     */
    private fun addColumnCondition(
        columnName: String,
        operator: SqlOperator,
        value: Any?,
        connector: String?
    ) {
        val column = table.schema.requireColumn(columnName)
        val (expression, localParams) = if (value == null) {
            when (operator) {
                SqlOperator.EQUAL -> "${column.name} IS NULL" to emptyList()
                SqlOperator.NOT_EQUAL -> "${column.name} IS NOT NULL" to emptyList()
                else -> throw IllegalArgumentException(
                    "Operator ${operator.name} does not accept null for column '${column.name}'"
                )
            }
        } else {
            "${column.name} ${operator.token} ?" to listOf(value)
        }
        addRawCondition("($expression)", values = localParams, connector = connector)
    }

    /**
     * Adds an already compiled SQL condition to the query.
     *
     * The helper resolves the logical prefix when needed, appends the
     * SQL fragment, and adds one or more bound arguments to the query.
     */
    private fun addRawCondition(
        expression: String,
        value: Any? = null,
        values: Collection<Any?> = emptyList(),
        connector: String?
    ) {
        val prefix = if (conditions.isEmpty()) "" else "${connector ?: "AND"} "
        conditions += "$prefix$expression"

        if (values.isNotEmpty()) {
            parameters.addAll(values)
            return
        }

        if (value != null || expression.contains("?")) {
            parameters.add(value)
        }
    }
}
