package io.github.kkokotero.vectorlite

import io.github.kkokotero.vectorlite.orm.TableChangeEvent
import io.github.kkokotero.vectorlite.orm.TableOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

/**
 * Automatic trigger hub backed by the table change flow.
 */
class VectorLiteTriggers internal constructor(
    private val session: VectorLiteSession,
    scope: CoroutineScope
) {

    private val changeHandlers = linkedMapOf<KClass<*>, MutableList<(TableChangeEvent) -> Unit>>()
    private val job: Job = scope.launch {
        session.changeEvents.collectLatest { event ->
            dispatch(event)
        }
    }

    inline fun <reified T : Any> afterInsert(noinline block: (TableChangeEvent) -> Unit) {
        on(T::class, TableOperation.INSERT, block)
    }

    inline fun <reified T : Any> afterUpdate(noinline block: (TableChangeEvent) -> Unit) {
        on(T::class, TableOperation.UPDATE, block)
    }

    inline fun <reified T : Any> afterDelete(noinline block: (TableChangeEvent) -> Unit) {
        on(T::class, TableOperation.DELETE, block)
    }

    fun <T : Any> on(
        entityClass: KClass<T>,
        operation: TableOperation,
        block: (TableChangeEvent) -> Unit
    ) {
        changeHandlers.getOrPut(entityClass) { mutableListOf() }.add { event ->
            if (event.operation == operation) {
                block(event)
            }
        }
    }

    fun clear() {
        changeHandlers.clear()
    }

    internal fun cancel() {
        job.cancel()
    }

    private fun dispatch(event: TableChangeEvent) {
        val entityClass = session.tableByName(event.tableName)?.schema?.entityClass ?: return
        changeHandlers[entityClass]?.forEach { handler ->
            runCatching { handler(event) }
        }
    }
}
