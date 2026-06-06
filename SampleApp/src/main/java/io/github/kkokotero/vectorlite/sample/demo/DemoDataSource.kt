package io.github.kkokotero.vectorlite.sample.demo

import android.content.Context
import io.github.kkokotero.vectorlite.sample.demo.database.DemoDatabase
import io.github.kkokotero.vectorlite.sample.demo.models.DemoState
import io.github.kkokotero.vectorlite.sample.demo.models.SearchPreset
import io.github.kkokotero.vectorlite.sample.demo.services.DemoContentService
import java.io.Closeable

class DemoDataSource(context: Context) : Closeable {
    private val database = DemoDatabase.open(context.applicationContext)
    private val demo = database.service<DemoContentService>()

    suspend fun loadState(searchPreset: SearchPreset = SearchPreset.Compose): DemoState =
        demo.loadState(searchPreset)

    suspend fun reset(searchPreset: SearchPreset = SearchPreset.Compose): DemoState =
        demo.reset(searchPreset)

    override fun close() {
        database.close()
    }
}
