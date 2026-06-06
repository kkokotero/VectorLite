package io.github.kkokotero.vectorlite

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import kotlin.math.roundToInt

/**
 * Opens and configures the SQLite database used by VectorLite.
 *
 * The database is stored in the application's private database directory and
 * loaded through AndroidX's bundled SQLite driver so the vector extension can
 * be registered without relying on the legacy Requery wrapper.
 */
class VectorLiteDatabase(
    context: Context,
    databaseName: String = defaultDatabaseName(context)
) {

    /** Open SQLite connection ready for use. */
    val databaseFile: File
    private val driver = BundledSQLiteDriver()
    private var _client: SQLiteConnection

    init {
        val appContext = context.applicationContext
        databaseFile = resolveDatabaseFile(appContext, databaseName)

        runCatching {
            loadVectorExtension(appContext, driver, listOf("vector"))
        }.onFailure { error ->
            Log.w(
                "VectorLiteDatabase",
                "Could not load the vector extension. Continuing without it: ${error.message}"
            )
        }

        _client = driver.open(databaseFile.absolutePath)

        configurePragma(_client)
        verifyVectorExtensionAvailability()
    }

    /** Connection currently in use by the database wrapper. */
    val client: SQLiteConnection
        get() = _client

    /** Close the underlying SQLite connection. */
    fun close() {
        _client.close()
    }

    /**
     * Reopens the database connection against the same backing file.
     *
     * This is used for restore/import flows that replace the physical
     * file while keeping the runtime instance alive.
     */
    fun reopen() {
        _client = driver.open(databaseFile.absolutePath)
        configurePragma(_client)
        verifyVectorExtensionAvailability()
    }

    /** Execute a SQL statement without bind arguments. */
    fun exec(sql: String) {
        if (shouldRunWithQuery(sql)) {
            queryOne(sql) { Unit }
            return
        }
        client.execSQL(sql)
    }

    /** Execute a SQL statement with bind arguments. */
    fun exec(sql: String, args: Array<Any?>) {
        if (shouldRunWithQuery(sql)) {
            queryOne(sql, args) { Unit }
            return
        }
        query(sql, args).use { statement ->
            statement.step()
        }
    }

    /** Prepare a SQL statement for manual execution. */
    fun prepare(sql: String): SQLiteStatement = client.prepare(sql)

    /** Compatibility alias for older code paths. */
    fun compile(sql: String): SQLiteStatement = prepare(sql)

    /** Returns the backing database file. */
    fun file(): File = databaseFile

    /**
     * Prepare a SQL statement and bind the provided arguments.
     *
     * The returned statement is ready to be stepped through or closed by the caller.
     */
    fun query(sql: String, args: Array<Any?> = emptyArray()): SQLiteStatement {
        return prepare(sql).apply { bindAll(args) }
    }

    /** Map each row from the query result to a Kotlin value. */
    inline fun <T> queryList(
        sql: String,
        args: Array<Any?>,
        mapper: (SQLiteStatement) -> T
    ): List<T> {
        val result = mutableListOf<T>()
        query(sql, args).use { statement ->
            while (statement.step()) {
                result += mapper(statement)
            }
        }
        return result
    }

    /** Return the first mapped row, or null if the result set is empty. */
    inline fun <T> queryOne(
        sql: String,
        args: Array<Any?> = emptyArray(),
        mapper: (SQLiteStatement) -> T
    ): T? {
        query(sql, args).use { statement ->
            return if (statement.step()) mapper(statement) else null
        }
    }

    /** Return true when the query produces at least one row. */
    fun exists(
        sql: String,
        args: Array<Any?> = emptyArray()
    ): Boolean {
        return queryOne(sql, args) { true } ?: false
    }

    private fun resolveDatabaseFile(
        context: Context,
        databaseName: String
    ): File {
        val normalizedName = normalizeDatabaseName(databaseName)
        val dbFile = context.getDatabasePath(normalizedName)
        dbFile.parentFile?.mkdirs()
        return dbFile
    }

    private fun loadVectorExtension(
        context: Context,
        driver: BundledSQLiteDriver,
        extensionNames: List<String>
    ) {
        extensionNames.forEach { extensionName ->
            val extensionPath = resolveExtensionPath(context, extensionName)
            driver.addExtension(extensionPath, extensionEntryPoint(extensionName))
        }
    }

    private fun extensionEntryPoint(extensionName: String): String {
        return "sqlite3_${extensionName}_init"
    }

    private fun resolveExtensionPath(context: Context, libraryName: String): String {
        val libraryFileNames = listOf(
            "$libraryName.so",
            libraryName,
            System.mapLibraryName(libraryName)
        ).distinct()

        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        libraryFileNames.forEach { fileName ->
            val candidate = File(nativeDir, fileName)
            if (candidate.exists()) return candidate.absolutePath
        }

        val extracted = extractFromApkIfPresent(context, libraryName, libraryFileNames)
        if (extracted != null) return extracted.absolutePath

        val searched = libraryFileNames.joinToString(", ") { File(nativeDir, it).absolutePath }
        throw IllegalStateException(
            "SQLite extension '$libraryName' not found. Looked in: $searched and APK native entries for ABIs: ${Build.SUPPORTED_ABIS.joinToString()}."
        )
    }

    private fun extractFromApkIfPresent(
        context: Context,
        libraryName: String,
        libraryFileNames: List<String>
    ): File? {
        val apkPaths = buildList {
            add(context.applicationInfo.sourceDir)
            context.applicationInfo.splitSourceDirs?.let { addAll(it) }
        }

        val preferredAbis = Build.SUPPORTED_ABIS.toList()
        val destinationRoot = File(context.noBackupFilesDir, "vector_db/extensions").apply { mkdirs() }
        val cachedExtension = File(destinationRoot, "$libraryName/$libraryName.so")
        if (cachedExtension.exists() && cachedExtension.length() > 0L) return cachedExtension

        apkPaths.forEach { apkPath ->
            ZipFile(apkPath).use { zip ->
                val selectedEntryName = preferredAbis
                    .asSequence()
                    .mapNotNull { abi ->
                        libraryFileNames.firstNotNullOfOrNull { fileName ->
                            zip.getEntry("lib/$abi/$fileName")?.name
                        }
                    }
                    .firstOrNull()
                    ?: zip.entries().asSequence()
                        .map { it.name }
                        .firstOrNull { name ->
                            name.startsWith("lib/") &&
                                libraryFileNames.any { fileName -> name.endsWith("/$fileName") }
                        }

                if (selectedEntryName != null) {
                    val extensionDir = File(destinationRoot, libraryName).apply { mkdirs() }
                    val extensionFile = File(extensionDir, "$libraryName.so")
                    val entry = zip.getEntry(selectedEntryName) ?: return@use
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(extensionFile).use { output -> input.copyTo(output) }
                    }
                    return extensionFile
                }
            }
        }

        return null
    }

    private fun configurePragma(db: SQLiteConnection) {
        ensureAutoVacuum(db)
        db.execSQL("PRAGMA journal_mode = WAL;")
        db.execSQL("PRAGMA wal_autocheckpoint = 256;")
        db.execSQL("PRAGMA mmap_size = 268435456;")
        db.execSQL("PRAGMA busy_timeout = 3000;")
        db.execSQL("PRAGMA synchronous = NORMAL;")
        db.execSQL("PRAGMA temp_store = MEMORY;")
        db.execSQL("PRAGMA foreign_keys = ON;")
        db.execSQL("PRAGMA journal_size_limit = 1048576;")
    }

    private fun verifyVectorExtensionAvailability() {
        runCatching {
            queryOne("SELECT vector_version();") { statement ->
                if (statement.getColumnCount() > 0) statement.getText(0) else ""
            }?.let { version ->
                if (version.isNotBlank()) {
                    Log.i("VectorLiteDatabase", "Vector extension loaded: version=$version")
                }
            }
        }.onFailure { error ->
            Log.w("VectorLiteDatabase", "Vector extension not available: ${error.message}")
        }
    }

    private fun ensureAutoVacuum(db: SQLiteConnection) {
        val currentMode = queryOne("PRAGMA auto_vacuum;") { statement ->
            if (statement.getColumnCount() > 0) statement.getLong(0).toInt() else 0
        } ?: 0
        if (currentMode == AUTO_VACUUM_FULL) return

        db.execSQL("PRAGMA auto_vacuum = FULL;")
        db.execSQL("VACUUM;")
    }

    private fun SQLiteStatement.bindAll(params: Array<Any?>) {
        params.forEachIndexed { i, value ->
            val index = i + 1
            when (value) {
                null -> bindNull(index)
                is ByteArray -> bindBlob(index, value)
                is FloatArray -> bindBlob(index, VectorLiteBindings.floatArrayToBlob(value, 4))
                is Int -> bindLong(index, value.toLong())
                is Long -> bindLong(index, value)
                is Short -> bindLong(index, value.toLong())
                is Byte -> bindLong(index, value.toLong())
                is Float -> bindDouble(index, value.toDouble())
                is Double -> bindDouble(index, value)
                is Boolean -> bindLong(index, if (value) 1 else 0)
                is java.time.Instant -> bindLong(index, value.toEpochMilli())
                is java.time.LocalDate -> bindText(index, value.toString())
                is java.time.LocalTime -> bindText(index, value.toString())
                is java.time.LocalDateTime -> bindText(index, value.toString())
                is String -> bindText(index, value)
                else -> bindText(index, value.toString())
            }
        }
    }

    private fun shouldRunWithQuery(sql: String): Boolean {
        val normalized = sql.trimStart()
        return normalized.startsWith("SELECT", ignoreCase = true) ||
            normalized.startsWith("PRAGMA", ignoreCase = true) ||
            normalized.startsWith("WITH", ignoreCase = true) ||
            normalized.startsWith("EXPLAIN", ignoreCase = true)
    }

    private fun normalizeDatabaseName(databaseName: String): String {
        val trimmed = databaseName.trim()
        require(trimmed.isNotEmpty()) { "Database name cannot be empty" }
        return if (trimmed.endsWith(".db")) trimmed else "$trimmed.db"
    }

    companion object {
        private const val AUTO_VACUUM_FULL = 1

        fun defaultDatabaseName(context: Context): String {
            val label = runCatching {
                context.applicationInfo.loadLabel(context.packageManager).toString()
            }.getOrNull().orEmpty()

            val fallback = context.packageName
                .substringAfterLast('.')
                .ifBlank { context.packageName }

            val stem = sanitizeDatabaseStem(label.ifBlank { fallback })
            return "$stem.db"
        }

        private fun sanitizeDatabaseStem(value: String): String {
            val normalized = value
                .lowercase()
                .trim()
                .replace(Regex("[^a-z0-9._-]+"), "_")
                .trim('_', '.', '-')

            return normalized.ifBlank { "vectorlite" }
        }
    }
}

private object VectorLiteBindings {
    fun floatArrayToBlob(floatArray: FloatArray, elementSize: Int): ByteArray {
        return when (elementSize) {
            4 -> java.nio.ByteBuffer.allocate(floatArray.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                floatArray.forEach { putFloat(it) }
            }.array()
            2 -> java.nio.ByteBuffer.allocate(floatArray.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                floatArray.forEach { putShort(floatToHalf(it)) }
            }.array()
            1 -> ByteArray(floatArray.size) { index ->
                (floatArray[index] * 127f).roundToInt().coerceIn(-128, 127).toByte()
            }
            else -> java.nio.ByteBuffer.allocate(floatArray.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                floatArray.forEach { putFloat(it) }
            }.array()
        }
    }

    private fun floatToHalf(value: Float): Short {
        val f = java.lang.Float.floatToIntBits(value)
        val sign = (f ushr 16) and 0x8000
        var exponent = ((f ushr 23) and 0xff) - 127 + 15
        var mantissa = (f ushr 13) and 0x3ff

        return when {
            exponent <= 0 -> {
                if (exponent < -10) {
                    sign.toShort()
                } else {
                    mantissa = (mantissa or 0x400) shr (1 - exponent)
                    (sign or mantissa).toShort()
                }
            }
            exponent >= 31 -> {
                (sign or 0x7c00 or if ((f and 0x7fffff) != 0) 1 else 0).toShort()
            }
            else -> {
                (sign or (exponent shl 10) or mantissa).toShort()
            }
        }
    }
}
