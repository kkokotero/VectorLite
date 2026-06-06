package io.github.kkokotero.vectorlite.orm

import kotlin.reflect.KClass

/**
 * Marks a class as a database table mapped by the ORM.
 *
 * @property name Database table name.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class DataTable(val name: String)

/**
 * Describes a standard SQL column mapping.
 *
 * Can be applied to fields, properties, or constructor parameters.
 *
 * @property primaryKey Whether the column is a primary key.
 * @property autoIncrement Whether SQLite should auto-generate the value.
 * @property nullable Whether the column allows NULL values.
 * @property unique Whether the column must be unique.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Column(
    val primaryKey: Boolean = false,
    val autoIncrement: Boolean = false,
    val nullable: Boolean = true,
    val unique: Boolean = false,
    val sqliteType: SQLiteColumnType = SQLiteColumnType.AUTO
)

/**
 * SQL column types supported for explicit declarations.
 *
 * `AUTO` lets the ORM infer the type from the Kotlin property.
 */
enum class SQLiteColumnType(val sqlType: String) {
    AUTO(""),
    INTEGER("INTEGER"),
    TINYINT("TINYINT"),
    SMALLINT("SMALLINT"),
    MEDIUMINT("MEDIUMINT"),
    BIGINT("BIGINT"),
    INT2("INT2"),
    INT8("INT8"),
    UNSIGNED_BIG_INT("UNSIGNED BIG INT"),
    INT("INT"),
    UINT("UINT"),
    UINT8("UINT8"),
    UINT16("UINT16"),
    UINT32("UINT32"),
    UINT64("UINT64"),
    ULONG("ULONG"),
    REAL("REAL"),
    FLOAT("FLOAT"),
    DOUBLE("DOUBLE"),
    DOUBLE_PRECISION("DOUBLE PRECISION"),
    TEXT("TEXT"),
    CHAR("CHAR"),
    VARCHAR("VARCHAR"),
    NCHAR("NCHAR"),
    NVARCHAR("NVARCHAR"),
    CLOB("CLOB"),
    BLOB("BLOB"),
    NUMERIC("NUMERIC"),
    DATE("DATE"),
    TIME("TIME"),
    DATETIME("DATETIME"),
    TIMESTAMP("TIMESTAMP"),
    BOOLEAN("BOOLEAN"),
    BIT("BIT"),
    BIT1("BIT(1)"),
    BIT8("BIT(8)"),
    BIT16("BIT(16)"),
    BIT32("BIT(32)"),
    BIT64("BIT(64)"),
    JSON("JSON")
}

/**
 * Defines a vector column for embeddings or semantic search.
 *
 * @property dimensions Number of vector dimensions.
 * @property elementSize Size of each element in bytes:
 * - 4 = float32
 * - 2 = float16 / bfloat16
 * - 1 = int8 / uint8
 * @property distanceMetric Distance metric used to compare vectors.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class VectorColumn(
    val dimensions: Int = 512,
    val elementSize: Int = 4, // 4 = float32, 2 = float16/bfloat16, 1 = int8/uint8
    val distanceMetric: DistanceMetric = DistanceMetric.COSINE
)

/**
 * Defines a foreign key reference to another entity.
 *
 * @property entity Referenced entity class.
 * @property field Referenced field name (defaults to `id`).
 * @property onDelete Action to apply when the referenced row is deleted.
 * @property onUpdate Action to apply when the referenced row is updated.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class ForeignKey(
    val entity: KClass<*>,
    val field: String = "id",
    val onDelete: ReferenceAction = ReferenceAction.NO_ACTION,
    val onUpdate: ReferenceAction = ReferenceAction.NO_ACTION
)

/**
 * Describes an ORM relationship between two entities.
 *
 * Supported shapes include one-to-one, one-to-many, and many-to-one.
 *
 * @property targetEntity Target entity class.
 * @property type Relationship type.
 * @property mappedBy Property or column on the opposite side that owns the relation.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Relationship(
    val targetEntity: KClass<*>,
    val type: RelationshipType,
    val mappedBy: String = ""
)

/**
 * Distance metrics used to compare vectors in semantic search.
 */
enum class DistanceMetric {
    /** Euclidean distance (L2). */
    L2,

    /** Squared Euclidean distance. */
    SQUARED_L2,

    /** Manhattan distance (L1). */
    L1,

    /** Cosine similarity, commonly used for embeddings. */
    COSINE,

    /** Dot product between vectors. */
    DOT_PRODUCT
}

/**
 * Referential actions applied when a referenced row changes.
 */
enum class ReferenceAction {

    /** No automatic action. */
    NO_ACTION,

    /** Prevent the operation if dependent rows exist. */
    RESTRICT,

    /** Set the foreign key value to NULL. */
    SET_NULL,

    /** Set the foreign key value to its default. */
    SET_DEFAULT,

    /** Propagate the operation to related rows. */
    CASCADE
}

/**
 * Relationship types supported by the ORM.
 */
enum class RelationshipType {

    /** One-to-one relationship. */
    ONE_TO_ONE,

    /** One-to-many relationship. */
    ONE_TO_MANY,

    /** Many-to-one relationship. */
    MANY_TO_ONE
}
