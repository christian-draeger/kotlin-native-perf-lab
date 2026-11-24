import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE

plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
}

repositories { mavenCentral() }

kotlin {
    mingwX64 {
        val main = compilations.getByName("main")
        main.cinterops.create("winsockProbe") {
            includeDirs(project.file("src/nativeInterop/cinterop"))
        }
        binaries.executable(buildTypes = listOf(RELEASE)) {
            baseName = "iocp-probe"
            entryPoint = "dev.perflab.ktserve.probe.main"
        }
        binaries.executable("server", listOf(RELEASE)) {
            baseName = "ktserve-iocp"
            entryPoint = "dev.perflab.ktserve.example.iocpMain"
        }
        val stress = compilations.create("stress") {
            associateWith(main)
            defaultSourceSet.kotlin.srcDir("../../src/nativeStress/kotlin")
            defaultSourceSet.kotlin.srcDir("src/windowsStress/kotlin")
            defaultSourceSet.kotlin.exclude("**/PosixProbeSocket.kt")
        }
        binaries.executable("stress", listOf(RELEASE)) {
            compilation = stress
            baseName = "ktserve-iocp-stress"
            entryPoint = "dev.perflab.ktserve.fixture.main"
        }
    }
    sourceSets.commonMain {
        kotlin.srcDir("../../src/commonMain/kotlin")
        dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
    }
    sourceSets.getByName("mingwX64Main").kotlin.srcDir("../../src/nativeShared/kotlin")
}
