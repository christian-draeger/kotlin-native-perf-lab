import org.gradle.api.tasks.Sync
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
}

repositories { mavenCentral() }

val ktorVersion = "3.6.1"

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    val os = System.getProperty("os.name")
    val arch = providers.gradleProperty("benchmark.targetArch").getOrElse(System.getProperty("os.arch"))
    val nativeTarget = when {
        os == "Linux" && arch in listOf("x86_64", "amd64") -> linuxX64("native")
        os == "Linux" && arch == "aarch64" -> linuxArm64("native")
        os == "Mac OS X" && arch == "aarch64" -> macosArm64("native")
        os == "Mac OS X" && arch == "x86_64" -> macosX64("native")
        else -> error("Unsupported Kotlin/Native benchmark host: $os $arch")
    }
    nativeTarget.binaries.executable { entryPoint = "dev.perflab.main" }

    sourceSets {
        commonMain.dependencies {
            implementation("io.ktor:ktor-server-core:$ktorVersion")
            implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
            implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
        }
        jvmMain.dependencies {
            implementation("io.ktor:ktor-server-cio:$ktorVersion")
        }
        nativeMain.dependencies {
            implementation("io.ktor:ktor-server-cio:$ktorVersion")
        }
    }
}

// A child JVM process, rather than a Gradle daemon, is measured by the harness.
tasks.register<Sync>("jvmDist") {
    dependsOn(tasks.named("jvmJar"))
    into(layout.buildDirectory.dir("jvmDist"))
    from(tasks.named<Jar>("jvmJar")) { rename { "app.jar" } }
    from(configurations.named("jvmRuntimeClasspath")) { into("lib") }
}
