package io.github.kkokotero.vectorlite

import android.content.Context
import kotlin.reflect.KClass

/**
 * Entry point for the public VectorLite API.
 */
object VectorLite {

    /**
     * Creates a database session using the provided DSL.
     */
    fun database(
        context: Context,
        block: DatabaseBuilder.() -> Unit
    ): VectorLiteSession {
        val builder = DatabaseBuilder(context.applicationContext)
        builder.block()
        return builder.build()
    }
}

/**
 * DSL used to configure a VectorLite database session.
 */
class DatabaseBuilder internal constructor(
    private val context: Context
) {
    var name: String = VectorLiteDatabase.defaultDatabaseName(context)

    private val entityTypes = linkedSetOf<KClass<out Any>>()
    private val serviceTypes = linkedSetOf<KClass<out Any>>()

    fun entities(vararg entityTypes: KClass<out Any>) {
        this.entityTypes += entityTypes
    }

    inline fun <reified T : Any> entities() {
        entities(T::class)
    }

    fun services(vararg serviceTypes: KClass<out Any>) {
        this.serviceTypes += serviceTypes
    }

    inline fun <reified T : Any> services() {
        services(T::class)
    }

    internal fun build(): VectorLiteSession {
        return VectorLiteSession(
            applicationContext = context,
            databaseName = name,
            entityTypes = EntityGraph.sort(entityTypes),
            serviceTypes = serviceTypes.toList()
        )
    }
}
