package io.github.kkokotero.vectorlite.orm

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * Core utility for converting vectors between in-memory representations
 * (`FloatArray`) and binary representations (`ByteArray`) compatible with
 * SQLite storage and vector extensions.
 *
 * It also provides helpers for:
 * - conversion across element sizes (`f32`, `f16`, `i8`)
 * - similarity score calculation
 * - metric-based maximum distance estimation
 */
object VectorConverter {

    /**
     * Converts a binary blob into a float array using the specified element size.
     *
     * @param blob Stored binary content.
     * @param dimensions Expected vector dimensions.
     * @param elementSize Element size:
     * - 4 = float32
     * - 2 = float16
     * - 1 = int8
     * @return Decoded vector as `FloatArray`.
     * @throws IllegalArgumentException If the element size is unsupported.
     */
    fun blobToFloatArray(blob: ByteArray, dimensions: Int, elementSize: Int = 4): FloatArray {
        return when (elementSize) {
            4 -> float32BlobToFloatArray(blob, dimensions)
            2 -> float16BlobToFloatArray(blob, dimensions)
            1 -> int8BlobToFloatArray(blob, dimensions)
            else -> throw IllegalArgumentException("Unsupported element size: $elementSize")
        }
    }

    /** Converts a `FloatArray` to a little-endian float32 blob. */
    private fun floatArrayToFloat32Blob(floatArray: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floatArray.size * 4)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        floatArray.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    /**
     * Converts a little-endian float32 blob back to a `FloatArray`.
     *
     * @throws IllegalArgumentException If the blob size does not match `dimensions * 4`.
     */
    private fun float32BlobToFloatArray(blob: ByteArray, dimensions: Int): FloatArray {
        if (blob.size != dimensions * 4) {
            throw IllegalArgumentException("Blob size ${blob.size} doesn't match dimensions $dimensions * 4")
        }
        val buffer = ByteBuffer.wrap(blob)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val result = FloatArray(dimensions)
        for (i in 0 until dimensions) {
            result[i] = buffer.float
        }
        return result
    }

    /** Converts a `FloatArray` to a little-endian float16 blob. */
    private fun floatArrayToFloat16Blob(floatArray: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floatArray.size * 2)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        floatArray.forEach { buffer.putShort(floatToHalf(it)) }
        return buffer.array()
    }

    /**
     * Converts a little-endian float16 blob back to a `FloatArray`.
     *
     * @throws IllegalArgumentException If the blob size does not match `dimensions * 2`.
     */
    private fun float16BlobToFloatArray(blob: ByteArray, dimensions: Int): FloatArray {
        if (blob.size != dimensions * 2) {
            throw IllegalArgumentException("Blob size ${blob.size} doesn't match dimensions $dimensions * 2")
        }
        val buffer = ByteBuffer.wrap(blob)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val result = FloatArray(dimensions)
        for (i in 0 until dimensions) {
            result[i] = halfToFloat(buffer.short)
        }
        return result
    }

    /**
     * Converts a `FloatArray` to an int8-quantized blob.
     *
     * The conversion assumes values are ideally in the approximate range
     * `[-1, 1]`. Each value is scaled by 127 and clamped to the valid
     * `Int8` range.
     */
    private fun floatArrayToInt8Blob(floatArray: FloatArray): ByteArray {
        val result = ByteArray(floatArray.size)
        floatArray.forEachIndexed { index, value ->
            val scaled = (value * 127f)
                .roundToInt()
                .coerceIn(-128, 127)
            result[index] = scaled.toByte()
        }
        return result
    }

    /**
     * Converts an int8-quantized blob back to a `FloatArray`.
     *
     * Each byte is interpreted as a signed integer and rescaled by 127.
     */
    private fun int8BlobToFloatArray(blob: ByteArray, dimensions: Int): FloatArray {
        require(blob.size == dimensions)
        return FloatArray(dimensions) { i ->
            blob[i].toFloat() / 127f
        }
    }

    /**
     * Converts a float16 value stored in a `Short` to single-precision `Float`.
     *
     * Handles normal numbers, subnormals, infinity, and NaN.
     */
    private fun halfToFloat(half: Short): Float {
        val hBits = half.toInt() and 0xffff
        val sign = (hBits and 0x8000) shl 16
        var exponent = (hBits shr 10) and 0x1f
        var mantissa = hBits and 0x3ff

        if (exponent == 0x1f) {
            exponent = 0xff
            mantissa = if (mantissa != 0) 0x400000 else 0
        } else if (exponent == 0) {
            if (mantissa != 0) {
                mantissa = mantissa shl 1
                while ((mantissa and 0x400) == 0) {
                    mantissa = mantissa shl 1
                    exponent--
                }
                exponent += 1
                mantissa = mantissa and 0x3ff
            }
            exponent = -14 + 127
        } else {
            exponent += (127 - 15)
            mantissa = mantissa shl 13
        }

        val bits = sign or (exponent shl 23) or mantissa
        return java.lang.Float.intBitsToFloat(bits)
    }

    /**
     * Calculates a normalized similarity score between 0 and 1 from a
     * distance reported by the vector engine.
     *
     * The interpretation depends on the metric:
     * - `COSINE`: uses `1 - distance`
     * - `DOT_PRODUCT`: normalizes from `[-1, 1]` to `[0, 1]`
     * - `L2` and `SQUARED_L2`: use exponential decay
     * - `L1`: uses max-distance normalization when available, otherwise
     *   falls back to exponential decay
     */
    fun calculateSimilarityScore(distance: Float, metric: DistanceMetric, maxDistance: Float? = null): Float {
        return when (metric) {
            DistanceMetric.COSINE -> {
                // sqlite-vector reports cosine distance where 0 means an exact match.
                // Applying 1 - distance preserves the expected similarity semantics.
                (1f - distance).coerceIn(0f, 1f)
            }

            DistanceMetric.DOT_PRODUCT -> {
                // Dot product requires normalization, assuming normalized vectors.
                // Expected range: -1 to 1. Map it to 0 to 1.
                ((distance + 1f) / 2f).coerceIn(0f, 1f)
            }

            DistanceMetric.L2 -> {
                // Euclidean distance ranges from 0 (identical) to infinity.
                // Exponential decay produces a usable similarity score.
                exp(-distance).toFloat().coerceIn(0f, 1f)
            }

            DistanceMetric.SQUARED_L2 -> {
                // Squared Euclidean distance.
                exp(-distance).toFloat().coerceIn(0f, 1f)
            }

            DistanceMetric.L1 -> {
                // Manhattan distance.
                if (maxDistance != null && maxDistance > 0) {
                    1f - (distance / maxDistance).coerceIn(0f, 1f)
                } else {
                    exp(-distance / 10f).toFloat().coerceIn(0f, 1f)
                }
            }
        }
    }

    /**
     * Estimates a theoretical maximum distance for vectors based on the metric.
     *
     * These estimates help normalize distances and produce comparable
     * similarity scores.
     */
    fun calculateMaxDistanceForVectors(dimensions: Int, metric: DistanceMetric): Float {
        return when (metric) {
            DistanceMetric.COSINE -> 2f
            DistanceMetric.DOT_PRODUCT -> 2f
            DistanceMetric.L2 -> sqrt(dimensions.toFloat() * 4f) // Approximate maximum distance for normalized vectors
            DistanceMetric.SQUARED_L2 -> dimensions.toFloat() * 4f
            DistanceMetric.L1 -> dimensions.toFloat() * 2f // Approximate maximum L1 distance
        }
    }

    /**
     * Converts a `FloatArray` to a blob compatible with sqlite-vector.
     *
     * @param elementSize Element size:
     * - 4 = f32
     * - 2 = f16
     * - 1 = i8
     */
    fun floatArrayToBlob(
        vector: FloatArray,
        elementSize: Int
    ): ByteArray {
        return when (elementSize) {
            4 -> toF32(vector)
            2 -> toF16(vector)
            1 -> toI8(vector)
            else -> error("Unsupported vector element size: $elementSize")
        }
    }

    /** Converts a vector to IEEE 754 little-endian float32 format. */
    private fun toF32(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer
            .allocate(vector.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)

        for (v in vector) {
            buffer.putFloat(v)
        }

        return buffer.array()
    }

    /** Converts a vector to IEEE 754 little-endian float16 format. */
    private fun toF16(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer
            .allocate(vector.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)

        for (v in vector) {
            buffer.putShort(floatToHalf(v))
        }

        return buffer.array()
    }

    /**
     * Converts a vector to quantized int8 format.
     *
     * Each value is scaled by 127, rounded, and clamped to `[-128, 127]`.
     */
    private fun toI8(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size)

        for (v in vector) {
            val clamped = (v * 127f)
                .roundToInt()
                .coerceIn(-128, 127)

            buffer.put(clamped.toByte())
        }

        return buffer.array()
    }

    /**
     * Converts a single-precision `Float` to float16.
     *
     * Manual implementation with no external dependencies. Handles underflow,
     * overflow, normal numbers, and simple subnormals.
     */
    private fun floatToHalf(value: Float): Short {
        val bits = java.lang.Float.floatToIntBits(value)

        val sign = (bits ushr 16) and 0x8000
        var exponent = ((bits ushr 23) and 0xFF) - 127 + 15
        var mantissa = bits and 0x7FFFFF

        return when {
            exponent <= 0 -> {
                if (exponent < -10) {
                    sign.toShort()
                } else {
                    mantissa = mantissa or 0x800000
                    val shift = 14 - exponent
                    val half = sign or (mantissa ushr shift)
                    half.toShort()
                }
            }

            exponent >= 31 -> {
                (sign or 0x7C00).toShort()
            }

            else -> {
                val half = sign or (exponent shl 10) or (mantissa ushr 13)
                half.toShort()
            }
        }
    }
}
