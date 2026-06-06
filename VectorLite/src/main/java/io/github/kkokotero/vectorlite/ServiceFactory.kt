package io.github.kkokotero.vectorlite

import android.content.Context
import io.github.kkokotero.vectorlite.orm.Repository as CoreRepository
import io.github.kkokotero.vectorlite.orm.Table
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible

/**
 * Instantiates service objects with constructor injection.
 */
internal class ServiceFactory(
    private val session: VectorLiteSession
) {

    fun <T : Any> create(serviceClass: KClass<T>): T {
        val constructor = serviceClass.primaryConstructor
            ?: serviceClass.constructors.minByOrNull { it.parameters.size }
            ?: throw IllegalArgumentException("Service ${serviceClass.simpleName} must have at least one constructor")

        constructor.isAccessible = true
        val arguments = constructor.parameters.associateWith { parameter ->
            resolveParameter(parameter)
        }
        return constructor.callBy(arguments)
    }

    private fun resolveParameter(parameter: KParameter): Any? {
        val type = parameter.type
        val classifier = type.classifier as? KClass<*> ?: return null

        return when (classifier) {
            Repository::class -> {
                val entityType = type.arguments.firstOrNull()?.type?.classifier as? KClass<*>
                    ?: throw IllegalArgumentException(
                        "Repository injection requires a concrete entity type for parameter '${parameter.name}'"
                    )
                session.repository(entityType as KClass<Any>)
            }
            Table::class -> {
                val entityType = type.arguments.firstOrNull()?.type?.classifier as? KClass<*>
                    ?: throw IllegalArgumentException(
                        "Table injection requires a concrete entity type for parameter '${parameter.name}'"
                    )
                session.table(entityType as KClass<Any>)
            }
            VectorLiteSession::class -> session
            Context::class -> session.applicationContext
            CoreRepository::class -> session.coreStore
            else -> null
        } ?: throw IllegalArgumentException(
            "No injection mapping found for parameter '${parameter.name}' of type ${type}"
        )
    }
}
