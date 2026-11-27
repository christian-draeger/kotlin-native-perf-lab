import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE

plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
}

repositories { mavenCentral() }

val extraDependencies = providers.fileContents(layout.projectDirectory.file("dependencies.txt")).asText.orElse("")
val bundledExtensions = providers.fileContents(layout.projectDirectory.file("bundled-extensions.txt")).asText.orElse("")

kotlin {
    val target = when (providers.gradleProperty("benchmark.targetOs").get()) {
        "linux" -> when (providers.gradleProperty("benchmark.targetArch").get()) {
            "amd64", "x86_64" -> linuxX64("native")
            "aarch64", "arm64" -> linuxArm64("native")
            else -> error("Unsupported Linux architecture")
        }
        "macos" -> macosArm64("native")
        else -> error("Unsupported native target OS")
    }
    target.binaries.executable(listOf(RELEASE)) {
        baseName = "ktserve-app"
        entryPoint = "main"
    }
    sourceSets.named("nativeMain") {
        dependencies {
            implementation("codes.draeger:ktserve")
            bundledExtensions.get().lineSequence().filter(String::isNotEmpty).forEach {
                implementation("codes.draeger:ktserve-$it")
            }
            extraDependencies.get().lineSequence().filter(String::isNotEmpty).forEach { implementation(it) }
        }
    }
}
