import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE

plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
}

repositories { mavenCentral() }

val uringShim = providers.gradleProperty("liburing.shim")
val uringArchive = providers.gradleProperty("liburing.archive")

kotlin {
    val arch = providers.gradleProperty("benchmark.targetArch").getOrElse(System.getProperty("os.arch"))
    val native = when (arch) {
        "amd64", "x86_64" -> linuxX64("native")
        "aarch64", "arm64" -> linuxArm64("native")
        else -> error("Unsupported Linux architecture $arch")
    }
    val main = native.compilations.getByName("main")
    main.cinterops.create("uring") {
        includeDirs(project.file("src/nativeInterop/cinterop"))
    }
    native.binaries.executable(buildTypes = listOf(RELEASE)) {
        baseName = "nativeserve-io-uring"
        entryPoint = "dev.perflab.nativeserve.example.uringMain"
        linkerOpts(file(uringShim.get()).absolutePath, file(uringArchive.get()).absolutePath)
    }
    val stress = native.compilations.create("stress") {
        associateWith(main)
        defaultSourceSet.kotlin.srcDir("../../src/nativeStress/kotlin")
        defaultSourceSet.kotlin.srcDir("src/uringStress/kotlin")
    }
    native.binaries.executable("stress", listOf(RELEASE)) {
        compilation = stress
        baseName = "nativeserve-io-uring-stress"
        entryPoint = "dev.perflab.nativeserve.fixture.uringStressMain"
        linkerOpts(file(uringShim.get()).absolutePath, file(uringArchive.get()).absolutePath)
    }
    sourceSets.commonMain {
        kotlin.srcDir("../../src/commonMain/kotlin")
        dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
    }
    sourceSets.getByName("nativeMain") {
        kotlin.srcDir("../../src/nativeShared/kotlin")
        kotlin.srcDir("../../src/nativeMain/kotlin/dev/perflab/nativeserve/transport")
    }
}
