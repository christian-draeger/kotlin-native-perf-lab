import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

val openTelemetryVersion = "1.68.0"

extensions.configure<KotlinMultiplatformExtension> {
    sourceSets.jvmMain.dependencies {
        api("io.opentelemetry:opentelemetry-api:$openTelemetryVersion")
    }
    sourceSets.jvmTest.dependencies {
        implementation("io.opentelemetry:opentelemetry-sdk-testing:$openTelemetryVersion")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    }
}
