package io.github.kkokotero.vectorlite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kkokotero.vectorlite.orm.Column
import io.github.kkokotero.vectorlite.orm.DataTable
import io.github.kkokotero.vectorlite.orm.ForeignKey
import io.github.kkokotero.vectorlite.orm.Table
import io.github.kkokotero.vectorlite.orm.TableOperation
import io.github.kkokotero.vectorlite.orm.equal
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.time.LocalDateTime

@DataTable("session_users")
data class SessionUser(
    @param:Column(primaryKey = true, autoIncrement = true, nullable = false)
    val id: Long = 0,

    @param:Column(nullable = false, unique = true)
    val email: String = "",

    @param:Column(nullable = false)
    val name: String = ""
)

@DataTable("session_posts")
data class SessionPost(
    @param:Column(primaryKey = true, autoIncrement = true, nullable = false)
    val id: Long = 0,

    @param:Column(nullable = false)
    @param:ForeignKey(entity = SessionUser::class, field = "id")
    val userId: Long = 0,

    @param:Column(nullable = false)
    val title: String = "",

    @param:Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.MIN
)

@Service
class SessionUserService(
    table: Table<SessionUser>,
    session: VectorLiteSession
) : DefaultTableService<SessionUser>(table, session) {
    fun findByEmail(email: String): SessionUser? = first { SessionUser::email equal email }
}

@RunWith(AndroidJUnit4::class)
class VectorLiteSessionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun exposesDirectTableAccessAndDefaultCrudService() {
        val db = openDatabase()
        try {
            val users = db.tableService<SessionUser>()

            users.create(SessionUser(email = "kevin@example.com", name = "Kevin"))

            assertEquals(1L, users.count())
            assertEquals("Kevin", users.first { SessionUser::email equal "kevin@example.com" }?.name)
            assertNotNull(db.service<SessionUserService>().findByEmail("kevin@example.com"))
        } finally {
            db.close()
        }
    }

    @Test
    fun supportsCustomServiceInjectionAndReuse() {
        val db = VectorLite.database(context) {
            entities(SessionUser::class, SessionPost::class)
            services(SessionUserService::class)
        }
        try {
            val service = db.service<SessionUserService>()
            service.create(SessionUser(email = "test@test.com", name = "Test"))

            assertNotNull(service.findByEmail("test@test.com"))
            assertEquals(1L, service.count())
        } finally {
            db.close()
        }
    }

    @Test
    fun supportsBulkInsertsAndNestedTransactions() {
        val db = openDatabase()
        try {
            val users = db.repository<SessionUser>()

            db.transaction {
                users.insertAll(
                    SessionUser(email = "a@test.com", name = "A"),
                    SessionUser(email = "b@test.com", name = "B")
                )
                db.transaction {
                    users.upsert(SessionUser(email = "c@test.com", name = "C"))
                }
            }

            assertEquals(3L, users.count())
        } finally {
            db.close()
        }
    }

    @Test
    fun emitsTableChangesForWrites() = runBlocking {
        val db = openDatabase()
        try {
            val users = db.repository<SessionUser>()
            val eventDeferred = async(Dispatchers.Default) {
                withTimeout(5_000) {
                    db.tableChanges("session_users").first()
                }
            }

            users.insert(SessionUser(email = "events@test.com", name = "Events"))

            val event = eventDeferred.await()
            assertEquals("session_users", event.tableName)
            assertEquals(TableOperation.INSERT, event.operation)
            assertEquals(1, event.affectedRows)
        } finally {
            db.close()
        }
    }

    @Test
    fun exportsAndImportsDatabaseFiles() {
        val db = openDatabase("vectorlite_test_export.db")
        val exportFile = File.createTempFile("vectorlite-export", ".db")
        try {
            val users = db.repository<SessionUser>()
            users.insert(SessionUser(email = "export@test.com", name = "Export"))

            db.exportTo(exportFile)

            val imported = VectorLite.database(context) {
                name = "vectorlite_test_import.db"
                entities(SessionUser::class, SessionPost::class)
            }
            try {
                imported.importFrom(exportFile)
                assertEquals(1L, imported.repository<SessionUser>().count())
            } finally {
                imported.close()
            }
        } finally {
            db.close()
            exportFile.delete()
        }
    }

    private fun openDatabase(name: String = "vectorlite_test.db"): VectorLiteSession {
        return VectorLite.database(context) {
            this.name = name
            entities(SessionUser::class, SessionPost::class)
            services(SessionUserService::class)
        }
    }
}
