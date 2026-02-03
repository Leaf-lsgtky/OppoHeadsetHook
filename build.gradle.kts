// Top-level build file
plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.kotlin) apply false
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
