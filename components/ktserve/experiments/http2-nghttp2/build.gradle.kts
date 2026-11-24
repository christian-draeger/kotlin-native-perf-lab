import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE

plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
}

repositories { mavenCentral() }

val nghttp2Headers = providers.gradleProperty("nghttp2.headers")
val nghttp2Archive = providers.gradleProperty("nghttp2.archive")

kotlin {
    val arch = providers.gradleProperty("benchmark.targetArch").getOrElse(System.getProperty("os.arch"))
    val native = when (arch) {
        "amd64", "x86_64" -> linuxX64("native")
        "aarch64", "arm64" -> linuxArm64("native")
        else -> error("Unsupported Linux architecture $arch")
    }
    native.compilations.getByName("main").cinterops.create("http2Probe") {
        includeDirs(project.file("src/nativeInterop/cinterop"), file(nghttp2Headers.get()))
    }
    native.compilations.getByName("main").cinterops.create("ktServeSignals") {
        val bindings = project.file("../../src/nativeInterop/cinterop")
        definitionFile.set(bindings.resolve("ktServeSignals.def"))
        includeDirs(bindings)
    }
    native.binaries.executable {
        baseName = "ktserve-http2-session-probe"
        entryPoint = "dev.perflab.ktserve.http2.main"
        linkerOpts(file(nghttp2Archive.get()).absolutePath)
    }
    native.binaries.executable("h2c", listOf(RELEASE)) {
        baseName = "ktserve-h2c"
        entryPoint = "dev.perflab.ktserve.http2.h2cMain"
        linkerOpts(file(nghttp2Archive.get()).absolutePath)
    }
    sourceSets.commonMain {
        kotlin.srcDir("../../src/commonMain/kotlin")
        dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
    }
    sourceSets.getByName("nativeMain") {
        kotlin.srcDir("../../src/nativeMain/kotlin/dev/perflab/ktserve/transport")
        kotlin.srcDir("../../src/nativeShared/kotlin")
    }
}
