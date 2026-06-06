package io.github.kkokotero.vectorlite.orm

/**
 * Exportable SQL type constants for explicit column declarations.
 *
 * Useful when building tables or manual queries while staying aligned with
 * the ORM type system.
 */
object SqlTypes {
    const val INTEGER = "INTEGER"
    const val TINYINT = "TINYINT"
    const val SMALLINT = "SMALLINT"
    const val MEDIUMINT = "MEDIUMINT"
    const val BIGINT = "BIGINT"
    const val INT2 = "INT2"
    const val INT8 = "INT8"
    const val UNSIGNED_BIG_INT = "UNSIGNED BIG INT"
    const val INT = "INT"
    const val UINT = "UINT"
    const val UINT8 = "UINT8"
    const val UINT16 = "UINT16"
    const val UINT32 = "UINT32"
    const val UINT64 = "UINT64"
    const val ULONG = "ULONG"

    const val REAL = "REAL"
    const val FLOAT = "FLOAT"
    const val DOUBLE = "DOUBLE"
    const val DOUBLE_PRECISION = "DOUBLE PRECISION"
    const val DECIMAL = "DECIMAL"
    const val NUMERIC = "NUMERIC"

    const val TEXT = "TEXT"
    const val CHAR = "CHAR"
    const val VARCHAR = "VARCHAR"
    const val NCHAR = "NCHAR"
    const val NVARCHAR = "NVARCHAR"
    const val CLOB = "CLOB"

    const val BLOB = "BLOB"

    const val DATE = "DATE"
    const val TIME = "TIME"
    const val DATETIME = "DATETIME"
    const val TIMESTAMP = "TIMESTAMP"

    const val BOOLEAN = "BOOLEAN"
    const val BIT = "BIT"
    const val BIT1 = "BIT(1)"
    const val BIT8 = "BIT(8)"
    const val BIT16 = "BIT(16)"
    const val BIT32 = "BIT(32)"
    const val BIT64 = "BIT(64)"

    const val JSON = "JSON"
}
