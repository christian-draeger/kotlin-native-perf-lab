import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    kotlin("plugin.serialization") version "2.4.21"
}

extensions.configure<KotlinMultiplatformExtension> {
    sourceSets.commonMain.dependencies {
        api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    }
    sourceSets.jvmTest.dependencies {
        implementation(project(":ktserve-validation"))
    }
}
