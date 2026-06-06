package io.github.kkokotero.vectorlite.sample.demo.database

import android.content.Context
import io.github.kkokotero.vectorlite.VectorLite
import io.github.kkokotero.vectorlite.VectorLiteSession
import io.github.kkokotero.vectorlite.sample.demo.entities.DemoArticle
import io.github.kkokotero.vectorlite.sample.demo.entities.DemoAuthor
import io.github.kkokotero.vectorlite.sample.demo.services.DemoContentService

object DemoDatabase {
    fun open(context: Context): VectorLiteSession {
        return VectorLite.database(context) {
            entities(DemoAuthor::class, DemoArticle::class)
            services(DemoContentService::class)
        }
    }
}
