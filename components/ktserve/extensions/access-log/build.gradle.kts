import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

extensions.configure<KotlinMultiplatformExtension> {
    sourceSets.commonMain.dependencies {
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    }
}
