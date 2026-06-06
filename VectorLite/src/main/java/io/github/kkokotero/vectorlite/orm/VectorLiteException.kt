package io.github.kkokotero.vectorlite.orm

/**
 * Base exception for VectorLite ORM failures.
 *
 * All ORM-specific exceptions inherit from this sealed type.
 *
 * @param message Human-readable error message.
 */
sealed class VectorLiteException(message: String) : Exception(message) {

    /** Thrown when a table was used before it was registered. */
    class TableNotRegistered(message: String) : VectorLiteException(message)

    /** Thrown when a column cannot be resolved from the entity schema. */
    class ColumnNotFound(message: String) : VectorLiteException(message)

    /** Thrown when a vector dimension does not match the configured schema. */
    class InvalidVectorDimension(message: String) : VectorLiteException(message)

    /** Thrown when a database query fails. */
    class QueryExecutionFailed(message: String) : VectorLiteException(message)

    /** Thrown when a similarity score is invalid or out of range. */
    class InvalidSimilarityScore(message: String) : VectorLiteException(message)

    /** Generic ORM exception used when no specific category fits. */
    class General(message: String) : VectorLiteException(message)

    companion object {

        /**
         * Convenience factory for a generic ORM exception.
         *
         * Example:
         * `throw VectorLiteException("message")`
         */
        operator fun invoke(message: String) = General(message)
    }
}
