// Top-level build file where you can add configuration options common to all sub-projects/modules.
group = "io.github.kkokotero"
version = "1.0.0"

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
}
