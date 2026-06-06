package io.github.kkokotero.vectorlite.orm

import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Foreign key information defined on a column.
 *
 * This structure describes the reference to another entity,
 * incluyendo el campo referenciado y las acciones que deben
 * to be applied during update or delete operations.
 *
 * @property entity Entity referenced by the foreign key.
 * @property field Field in the referenced entity targeted by the relationship.
 * @property onDelete Referential action to apply when the referenced row is deleted.
 * @property onUpdate Referential action to apply when the referenced row is updated.
 */
data class ForeignKeyInfo(
    val entity: KClass<*>,
    val field: String,
    val onDelete: ReferenceAction,
    val onUpdate: ReferenceAction
)

/**
 * Declarative relationship information between entities.
 *
 * This structure represents ORM relationships such as one-to-one,
 * one-to-many or many-to-one, plus the property name
 * y el campo inverso cuando corresponda.
 *
 * @property targetEntity Target entity for the relationship.
 * @property type Relationship type.
 * @property mappedBy Name of the inverse property that keeps the relationship.
 * @property propertyName Nombre de la propiedad relacional en la entidad actual.
 */
data class RelationshipInfo(
    val targetEntity: KClass<*>,
    val type: RelationshipType,
    val mappedBy: String,
    val propertyName: String
)

/**
 * Metadatos de una columna dentro del esquema de una tabla.
 *
 * Contains both classic relational information and details
 * specific to vector columns.
 *
 * @property name Nombre de la columna en la tabla.
 * @property type Tipo SQL de la columna.
 * @property isPrimaryKey Indica si la columna es clave primaria.
 * @property isAutoIncrement Indica si la columna es autoincremental.
 * @property isNullable Indica si la columna permite nulos.
 * @property isUnique Indica si la columna exige unicidad.
 * @property isVector Indica si la columna almacena datos vectoriales.
 * @property vectorDimensions Vector dimension count, if applicable.
 * @property vectorElementSize Element size of the vector, if applicable.
 * @property distanceMetric Distance metric used for vector searches, if applicable.
 * @property foreignKey Foreign key information, if present.
 * @property propertyName Nombre de la propiedad Kotlin asociada a la columna.
 * @property readValue Function that extracts the property value from an instance.
 */
data class ColumnInfo(
    val name: String,
    val type: String,
    val isPrimaryKey: Boolean,
    val isAutoIncrement: Boolean,
    val isNullable: Boolean,
    val isUnique: Boolean,
    val isVector: Boolean = false,
    val vectorDimensions: Int? = null,
    val vectorElementSize: Int? = null,
    val distanceMetric: DistanceMetric? = null,
    val foreignKey: ForeignKeyInfo? = null,
    val propertyName: String,
    val readValue: (Any) -> Any?
)

/**
 * Represents the schema of an entity mapped to a table.
 *
 * This class encapsulates:
 * - el nombre de la tabla
 * - la clase de la entidad
 * - columns discovered via reflection
 * - la clave primaria
 * - las relaciones declaradas
 *
 * It also provides utilities to:
 * - resolver columnas por nombre o propiedad
 * - obtener columnas vectoriales
 * - calcular una huella del esquema
 *
 * @param T Tipo de entidad asociado al esquema.
 * @property tableName Physical table name.
 * @property entityClass Clase Kotlin de la entidad.
 * @property columns Lista de columnas del esquema.
 * @property primaryKeyColumn Primary key column, if present.
 * @property relationships Lista de relaciones declaradas.
 */
class TableSchema<T : Any>(
    val tableName: String,
    val entityClass: KClass<T>,
    val columns: List<ColumnInfo>,
    val primaryKeyColumn: ColumnInfo?,
    val relationships: List<RelationshipInfo> = emptyList()
) {

    /**
     * Internal column index by name for fast lookups.
     */
    private val columnsByName: Map<String, ColumnInfo> = columns.associateBy { it.name }

    /**
     * Huella estable del esquema calculada de forma diferida.
     *
     * It is derived from the structural content of the schema and can
     * be used to detect changes in the table definition.
     */
    val schemaHash: String by lazy { computeSchemaHash() }

    companion object {

        /**
         * Construye un esquema de tabla a partir de una clase anotada.
         *
         * This method inspects via reflection:
         * - the `@DataTable` annotation on the class
         * - las propiedades miembro
         * - el constructor primario
         * - las anotaciones `@Column`, `@VectorColumn`, `@ForeignKey` y `@Relationship`
         *
         * Reglas importantes:
         * - la clase debe tener `@DataTable`
         * - una propiedad no puede tener `@Column` y `@VectorColumn` al mismo tiempo
         * - una propiedad vectorial debe ser de tipo `FloatArray`
         *
         * @param T Tipo de entidad a inspeccionar.
         * @return Esquema construido para la entidad.
         * @throws IllegalArgumentException Si la clase o sus propiedades no cumplen las reglas del ORM.
         */
        inline fun <reified T : Any> fromClass(): TableSchema<T> {
            return fromClass(T::class)
        }

        /**
         * Construye un esquema de tabla a partir de una clase anotada.
         *
         * This overload does not depend on a reified type and allows
         * registering entities dynamically from higher-level APIs.
         */
        fun <T : Any> fromClass(entityClass: KClass<T>): TableSchema<T> {
            val tableAnnotation = entityClass.findAnnotation<DataTable>()
                ?: throw IllegalArgumentException("Class ${entityClass.simpleName} must be annotated with @DataTable")

            val tableName = tableAnnotation.name
            val columns = mutableListOf<ColumnInfo>()
            val relationships = mutableListOf<RelationshipInfo>()
            var primaryKeyColumn: ColumnInfo? = null

            val primaryConstructor = entityClass.primaryConstructor

            entityClass.memberProperties.forEach { property ->
                val param = primaryConstructor?.parameters?.find { it.name == property.name }

                val columnAnnotation = property.findAnnotation<Column>()
                    ?: param?.findAnnotation<Column>()

                val vectorColumnAnnotation = property.findAnnotation<VectorColumn>()
                    ?: param?.findAnnotation<VectorColumn>()

                val foreignKeyAnnotation = property.findAnnotation<ForeignKey>()
                    ?: param?.findAnnotation<ForeignKey>()

                val relationshipAnnotation = property.findAnnotation<Relationship>()
                    ?: param?.findAnnotation<Relationship>()

                when {
                    relationshipAnnotation != null -> {
                        relationships.add(
                            RelationshipInfo(
                                targetEntity = relationshipAnnotation.targetEntity,
                                type = relationshipAnnotation.type,
                                mappedBy = relationshipAnnotation.mappedBy,
                                propertyName = property.name
                            )
                        )
                    }
                    columnAnnotation != null && vectorColumnAnnotation != null -> {
                        throw IllegalArgumentException("Property ${property.name} cannot have both @Column and @VectorColumn")
                    }
                    columnAnnotation != null -> {
                        val inferredType = when (property.returnType.classifier) {
                            Long::class -> "INTEGER"
                            Int::class -> "INTEGER"
                            String::class -> "TEXT"
                            Float::class -> "REAL"
                            Double::class -> "REAL"
                            Boolean::class -> "INTEGER"
                            ByteArray::class -> "BLOB"
                            LocalDate::class -> "DATE"
                            LocalTime::class -> "TIME"
                            LocalDateTime::class -> "DATETIME"
                            Instant::class -> "INTEGER"
                            else -> "TEXT"
                        }
                        val type = if (columnAnnotation.sqliteType == SQLiteColumnType.AUTO) {
                            inferredType
                        } else {
                            columnAnnotation.sqliteType.sqlType
                        }

                        val fkInfo = foreignKeyAnnotation?.let {
                            ForeignKeyInfo(it.entity, it.field, it.onDelete, it.onUpdate)
                        }

                        val columnInfo = ColumnInfo(
                            name = property.name,
                            type = type,
                            isPrimaryKey = columnAnnotation.primaryKey,
                            isAutoIncrement = columnAnnotation.autoIncrement,
                            isNullable = columnAnnotation.nullable,
                            isUnique = columnAnnotation.unique,
                            foreignKey = fkInfo,
                            propertyName = property.name,
                            readValue = { instance -> property.get(instance as T) }
                        )

                        columns.add(columnInfo)

                        if (columnAnnotation.primaryKey) {
                            primaryKeyColumn = columnInfo
                        }
                    }
                    vectorColumnAnnotation != null -> {
                        if (property.returnType.classifier != FloatArray::class) {
                            throw IllegalArgumentException("Vector property ${property.name} must be FloatArray")
                        }

                        val columnInfo = ColumnInfo(
                            name = property.name,
                            type = "BLOB",
                            isPrimaryKey = false,
                            isAutoIncrement = false,
                            isNullable = false,
                            isUnique = false,
                            isVector = true,
                            vectorDimensions = vectorColumnAnnotation.dimensions,
                            vectorElementSize = vectorColumnAnnotation.elementSize,
                            distanceMetric = vectorColumnAnnotation.distanceMetric,
                            propertyName = property.name,
                            readValue = { instance -> property.get(instance as T) }
                        )

                        columns.add(columnInfo)
                    }
                }
            }

            return TableSchema(tableName, entityClass, columns, primaryKeyColumn, relationships)
        }
    }

    /**
     * Returns all vector columns in the schema.
     *
     * @return Lista de columnas marcadas como vectoriales.
     */
    fun getVectorColumns(): List<ColumnInfo> = columns.filter { it.isVector }

    /**
     * Resuelve una columna por su nombre.
     *
     * @param columnName Nombre de la columna.
     * @return Column information.
     * @throws VectorLiteException.ColumnNotFound Si la columna no existe en el esquema.
     */
    fun requireColumn(columnName: String): ColumnInfo {
        return columnsByName[columnName]
            ?: throw VectorLiteException.ColumnNotFound(
                "Column '$columnName' not found in table '$tableName'"
            )
    }

    /**
     * Resuelve una columna a partir de una propiedad Kotlin.
     *
     * @param property Propiedad del modelo.
     * @return Associated column information.
     */
    fun <V> requireColumn(property: KProperty1<T, V>): ColumnInfo {
        return requireColumn(property.name)
    }

    /**
     * Resuelve una columna vectorial por nombre.
     *
     * Primero valida que la columna exista y luego verifica que
     * is actually marked as vector.
     *
     * @param columnName Nombre de la columna.
     * @return Vector column information.
     * @throws VectorLiteException.ColumnNotFound Si no existe o no es vectorial.
     */
    fun requireVectorColumn(columnName: String): ColumnInfo {
        val column = requireColumn(columnName)
        if (!column.isVector) {
            throw VectorLiteException.ColumnNotFound(
                "Column '$columnName' in table '$tableName' is not a vector column"
            )
        }
        return column
    }

    /**
     * Resuelve una columna vectorial a partir de una propiedad `FloatArray`.
     *
     * @param property Propiedad vectorial del modelo.
     * @return Vector column information.
     */
    fun requireVectorColumn(property: KProperty1<T, FloatArray>): ColumnInfo {
        return requireVectorColumn(property.name)
    }

    /**
     * Calcula una huella corta del esquema usando SHA-256.
     *
     * La firma del esquema incluye:
     * - nombre de la tabla
     * - columnas y sus atributos
     * - metadatos vectoriales
     * - foreign key metadata
     * - relaciones declaradas
     *
     * El resultado final:
     * - se genera con SHA-256
     * - se convierte a hexadecimal
     * - trimmed to 24 characters for practical use
     *
     * @return Hash corto representativo del esquema.
     */
    private fun computeSchemaHash(): String {
        val schemaSignature = buildString {
            append("table=").append(tableName).append(';')
            columns.sortedBy { it.name }.forEach { column ->
                append("column=").append(column.name)
                    .append("|type=").append(column.type)
                    .append("|pk=").append(column.isPrimaryKey)
                    .append("|auto=").append(column.isAutoIncrement)
                    .append("|nullable=").append(column.isNullable)
                    .append("|unique=").append(column.isUnique)
                    .append("|vector=").append(column.isVector)
                    .append("|dims=").append(column.vectorDimensions ?: -1)
                    .append("|esize=").append(column.vectorElementSize ?: -1)
                    .append("|metric=").append(column.distanceMetric?.name ?: "none")
                    .append("|fk=").append(column.foreignKey?.entity?.qualifiedName ?: "none")
                    .append('|').append(column.foreignKey?.field ?: "none")
                    .append('|').append(column.foreignKey?.onDelete?.name ?: "none")
                    .append('|').append(column.foreignKey?.onUpdate?.name ?: "none")
                    .append(';')
            }
            relationships.sortedBy { it.propertyName }.forEach { relation ->
                append("relation=").append(relation.propertyName)
                    .append("|target=").append(relation.targetEntity.qualifiedName ?: "none")
                    .append("|type=").append(relation.type.name)
                    .append("|mappedBy=").append(relation.mappedBy)
                    .append(';')
            }
        }

        return MessageDigest.getInstance("SHA-256")
            .digest(schemaSignature.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
    }
}
