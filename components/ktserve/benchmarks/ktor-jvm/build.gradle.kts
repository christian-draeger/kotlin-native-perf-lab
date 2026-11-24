plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
}

repositories { mavenCentral() }

kotlin.sourceSets.main {
    kotlin.srcDir("../../integrations/ktor/src/example/kotlin")
}

dependencies {
    implementation("io.github.christian-draeger:ktserve-ktor")
    implementation("io.ktor:ktor-server-cio:3.6.1")
    implementation("io.ktor:ktor-server-netty:3.6.1")
    implementation("io.ktor:ktor-server-content-negotiation:3.6.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.1")
}

application { mainClass.set("io.github.christian_draeger.ktserve.ktor.example.BenchmarkKt") }
