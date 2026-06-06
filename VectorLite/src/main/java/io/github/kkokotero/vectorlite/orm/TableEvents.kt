package io.github.kkokotero.vectorlite.orm

/**
 * Describes a change event emitted by a table.
 *
 * @property tableName Affected table name.
 * @property operation Type of write operation.
 * @property affectedRows Number of rows changed by the operation.
 * @property rowId Affected row id when available.
 * @property timestampMs Event timestamp in milliseconds.
 */
data class TableChangeEvent(
    val tableName: String,
    val operation: TableOperation,
    val affectedRows: Int,
    val rowId: Long? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Write operations that can produce a table change event.
 */
enum class TableOperation {

    /** One or more rows were inserted. */
    INSERT,

    /** One or more rows were updated. */
    UPDATE,

    /** One or more rows were deleted. */
    DELETE
}
