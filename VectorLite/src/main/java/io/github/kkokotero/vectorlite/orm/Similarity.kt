package io.github.kkokotero.vectorlite.orm

import kotlin.math.roundToInt

/**
 * Represents a single result from a vector similarity search.
 *
 * Each instance contains the matched entity, the raw distance reported by
 * the vector engine, a normalized similarity score, and the ranking within
 * the ordered result set.
 */
data class SimilarityResult<T>(
    val entity: T,
    val distance: Float,
    val similarity: Float, // Score 0-1
    val rank: Int
) : Comparable<SimilarityResult<T>> {

    /** Similarity percentage with two decimal places. */
    val similarityPercentage: Float
        get() = (similarity * 10000f).roundToInt() / 100f // 2 decimal places

    /** Compares two results by similarity only. */
    override fun compareTo(other: SimilarityResult<T>): Int {
        return similarity.compareTo(other.similarity)
    }
}

/**
 * Represents the full result set of a vector similarity search.
 */
data class VectorSearchResult<T>(
    val results: List<SimilarityResult<T>>,
    val queryTimeMs: Long,
    val totalResults: Int
) {

    /** Best match found. Assumes `results` is already sorted from best to worst. */
    val bestMatch: SimilarityResult<T>?
        get() = results.firstOrNull()

    /** Returns a new result set containing only the first `k` elements. */
    fun getTopK(k: Int): VectorSearchResult<T> {
        return if (results.size <= k) this
        else copy(results = results.take(k))
    }

    /** Filters the results by a minimum similarity threshold. */
    fun filterBySimilarity(minSimilarity: Float): VectorSearchResult<T> {
        val filtered = results.filter { it.similarity >= minSimilarity }
        return copy(results = filtered, totalResults = filtered.size)
    }
}
