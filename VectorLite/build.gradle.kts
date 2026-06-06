import java.util.Properties

group = rootProject.group
version = rootProject.version

plugins {
    alias(libs.plugins.android.library)
    id("maven-publish")
}

fun Project.readLocalProperty(key: String): String? {
    val localPropertiesFile = rootProject.file("local.properties")
    if (!localPropertiesFile.exists()) return null

    val properties = Properties().apply {
        localPropertiesFile.inputStream().use { load(it) }
    }
    return properties.getProperty(key)?.takeIf { it.isNotBlank() }
}

android {
    namespace = "io.github.kkokotero.vectorlite"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 27

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])

                groupId = rootProject.group.toString()
                artifactId = "vectorlite"
                version = rootProject.version.toString()

                pom {
                    name.set("VectorLite")
                    description.set("Android library for local relational data, CRUD services, and vector search.")
                    url.set("https://github.com/kkokotero/VectorLite")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    developers {
                        developer {
                            id.set("kkokotero")
                            name.set("kkokotero")
                        }
                    }
                    scm {
                        url.set("https://github.com/kkokotero/VectorLite")
                        connection.set("scm:git:https://github.com/kkokotero/VectorLite.git")
                        developerConnection.set("scm:git:ssh://git@github.com/kkokotero/VectorLite.git")
                    }
                }
            }
        }

        repositories {
            maven {
                name = "GitHubPackages"
                val repoOwner = project.findProperty("githubOwner")?.toString()
                    ?: project.readLocalProperty("githubOwner")
                    ?: "kkokotero"
                val repoName = project.findProperty("githubRepo")?.toString()
                    ?: project.readLocalProperty("githubRepo")
                    ?: "VectorLite"
                url = uri("https://maven.pkg.github.com/$repoOwner/$repoName")
                credentials {
                    username = project.findProperty("gpr.user")?.toString()
                        ?: project.readLocalProperty("gpr.user")
                        ?: System.getenv("GITHUB_ACTOR")
                        ?: ""
                    password = project.findProperty("gpr.key")?.toString()
                        ?: project.readLocalProperty("gpr.key")
                        ?: System.getenv("GITHUB_TOKEN")
                        ?: ""
                }
            }
        }
    }
}

dependencies {
    implementation(kotlin("reflect"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.13")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("androidx.test:core:1.6.1")

    implementation("androidx.sqlite:sqlite:2.6.2")
    implementation("androidx.sqlite:sqlite-bundled:2.6.2")

    implementation("ai.sqlite:vector:0.9.93")
}
