# VectorLite

<p align="center">
  <img src="docs/banner.svg" alt="VectorLite banner" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/kkokotero/VectorLite">
    <img src="https://img.shields.io/badge/GitHub-kkokotero%2FVectorLite-181717?style=for-the-badge&logo=github" alt="GitHub badge" />
  </a>
  <img src="https://img.shields.io/badge/Version-1.0.0-blue?style=for-the-badge" alt="Version badge" />
  <img src="https://img.shields.io/badge/License-MIT-2ea44f?style=for-the-badge" alt="MIT license badge" />
  <img src="https://img.shields.io/badge/Android-API%2027%2B-3ddc84?style=for-the-badge&logo=android" alt="Android badge" />
  <img src="https://img.shields.io/badge/Kotlin-JVM%2017-7f52ff?style=for-the-badge&logo=kotlin" alt="Kotlin badge" />
</p>

**VectorLite is an Android library for local relational data, CRUD services, reactive table changes, and vector search — all on-device.**

It is designed for apps that need:

- clean entity mapping
- simple CRUD access
- custom service layers
- query DSLs
- vector embeddings
- relationships between tables
- transactions
- backup and restore
- SQLite extensions

---

## Installation

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/kkokotero/VectorLite")
    }
}
```

### Add the dependency

```kotlin
dependencies {
    implementation("io.github.kkokotero:vectorlite:1.0.0")
}
```

---

## Why VectorLite

Most Android apps end up splitting persistence into several layers:

```txt
Database
↓
Repository
↓
Service
↓
Business logic
↓
Vector search integration
```

VectorLite keeps that flow in one library:

```txt
VectorLite
├── Entities
├── CRUD
├── Repositories
├── Services
├── Relations
├── Vector search
├── Triggers
└── Backup / restore
```

---

## Quick start

### 1) Define entities

```kotlin
import io.github.kkokotero.vectorlite.orm.Column
import io.github.kkokotero.vectorlite.orm.DataTable
import io.github.kkokotero.vectorlite.orm.ForeignKey
import io.github.kkokotero.vectorlite.orm.Relationship
import io.github.kkokotero.vectorlite.orm.RelationshipType
import io.github.kkokotero.vectorlite.orm.VectorColumn

@DataTable("users")
data class UserEntity(
    @Column(primaryKey = true, autoIncrement = true, nullable = false)
    val id: Long = 0,

    @Column(nullable = false, unique = true)
    val email: String,

    @Column(nullable = false)
    val name: String
)

@DataTable("face_embeddings")
data class FaceEmbeddingEntity(
    @Column(primaryKey = true, autoIncrement = true, nullable = false)
    val id: Long = 0,

    @ForeignKey(entity = UserEntity::class)
    @Column(nullable = false)
    val userId: Long,

    @VectorColumn(dimensions = 512, elementSize = 4)
    val embedding: FloatArray
)
```

### 2) Create the database

If you do not set a name, VectorLite uses the app label by default.

```kotlin
import io.github.kkokotero.vectorlite.VectorLite

val db = VectorLite.database(context) {
    entities(
        UserEntity::class,
        FaceEmbeddingEntity::class
    )

    services(
        FaceRecognitionService::class
    )
}
```

### 3) Use the API

```kotlin
val users = db.crud<UserEntity>()
val embeddings = db.repository<FaceEmbeddingEntity>()
val faceService = db.service<FaceRecognitionService>()
```

---

## CRUD

Use `crud<T>()` when you want a simple table-oriented service.

```kotlin
val users = db.crud<UserEntity>()

users.create(
    UserEntity(name = "Kevin", email = "kevin@example.com")
)

val kevin = users.first {
    UserEntity::email equal "kevin@example.com"
}

val allUsers = users.findAll()
```

If you want lower-level access and batch helpers, use `repository<T>()`.

```kotlin
val embeddings = db.repository<FaceEmbeddingEntity>()

embeddings.insertAll(
    FaceEmbeddingEntity(userId = 1, embedding = queryVector),
    FaceEmbeddingEntity(userId = 2, embedding = otherVector)
)
```

---

## Custom services

Custom services are the recommended place for business logic.

```kotlin
import io.github.kkokotero.vectorlite.DefaultTableService
import io.github.kkokotero.vectorlite.Repository
import io.github.kkokotero.vectorlite.Service
import io.github.kkokotero.vectorlite.VectorLiteSession
import io.github.kkokotero.vectorlite.orm.Table

@Service
class FaceRecognitionService(
    table: Table<FaceEmbeddingEntity>,
    session: VectorLiteSession,
    private val embeddings: Repository<FaceEmbeddingEntity>
) : DefaultTableService<FaceEmbeddingEntity>(table, session) {

    fun storeEmbedding(userId: Long, vector: FloatArray): FaceEmbeddingEntity {
        return create(
            FaceEmbeddingEntity(
                userId = userId,
                embedding = vector
            )
        )
    }
}
```

Usage:

```kotlin
val service = db.service<FaceRecognitionService>()
service.storeEmbedding(userId = 1, vector = embedding)
```

---

## Query DSL

```kotlin
val users = db.crud<UserEntity>()

val kevin = users.first {
    UserEntity::name equal "Kevin"
}

val matchedUsers = users.where {
    UserEntity::email matches "%@example.com"
}
```

Available operators:

- `equal`
- `notEqual`
- `greaterThan`
- `greaterThanOrEqual`
- `lessThan`
- `lessThanOrEqual`
- `matches`
- `inside`

---

## Vector search

```kotlin
val results = db.repository<FaceEmbeddingEntity>()
    .query()
    .nearestTo(
        column = FaceEmbeddingEntity::embedding,
        vector = queryVector,
        options = io.github.kkokotero.vectorlite.orm.VectorSearchOptions(
            topK = 5,
            approximate = true
        )
    )
    .vectorSearch()

val bestMatch = results.bestMatch
```

If you only need the closest result:

```kotlin
val match = db.table<FaceEmbeddingEntity>().nearestNeighbor(
    vectorColumn = FaceEmbeddingEntity::embedding,
    queryVector = queryVector
)
```

---

## Relationships

```kotlin
@DataTable("users")
data class UserEntity(
    @Column(primaryKey = true, autoIncrement = true, nullable = false)
    val id: Long = 0,

    @Column(nullable = false)
    val name: String,

    @Relationship(
        targetEntity = FaceEmbeddingEntity::class,
        type = RelationshipType.ONE_TO_MANY,
        mappedBy = "userId"
    )
    var embeddings: List<FaceEmbeddingEntity> = emptyList()
)
```

Relationships can be loaded automatically through the query API.

---

## Transactions

```kotlin
db.transaction {
    val users = db.repository<UserEntity>()
    val embeddings = db.repository<FaceEmbeddingEntity>()

    val userId = users.insert(
        UserEntity(name = "Kevin", email = "kevin@example.com")
    )

    embeddings.insert(
        FaceEmbeddingEntity(
            userId = userId,
            embedding = queryVector
        )
    )
}
```

Batch helpers such as `insertAll()` and `upsertAll()` already run inside a transaction.

---

## Reactive changes and triggers

```kotlin
db.tableChanges("users").collect { event ->
    println("${event.tableName} changed: ${event.operation}")
}
```

Typed triggers:

```kotlin
db.triggers.afterInsert<UserEntity> {
    println("User inserted")
}

db.triggers.afterUpdate<UserEntity> {
    println("User updated")
}

db.triggers.afterDelete<UserEntity> {
    println("User deleted")
}
```

---

## Backup and restore

```kotlin
import java.io.File

db.exportTo(File(context.filesDir, "vectorlite-backup.db"))
db.importFrom(File(context.filesDir, "vectorlite-backup.db"))
```

---

## Project structure

```txt
app/
├── data/
│   ├── entities/
│   ├── services/
│   └── database/
└── ui/
```

For a public library, keep the demo app focused on real usage and keep the library code small and readable.

---

## Good fit

- face recognition
- semantic search
- local AI memory
- offline-first apps
- embedded catalogs
- relationship-heavy data models

---

## Requirements

- Android-only
- minSdk 27
- Kotlin 17
- AndroidX bundled SQLite

---

## Status

VectorLite is under active development.

The API is intended to stay clean and public-facing, but it may still evolve before the first stable release.

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## Code of conduct

See [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## License

See [LICENSE](LICENSE).
