import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    kotlin("plugin.serialization") version "2.4.21"
}

extensions.configure<KotlinMultiplatformExtension> {
    sourceSets.commonMain.dependencies {
        api("io.konform:konform:0.11.1")
        api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    }
    sourceSets.commonTest.dependencies {
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    }
}
