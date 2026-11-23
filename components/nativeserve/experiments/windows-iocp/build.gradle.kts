import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE

plugins {
    kotlin("multiplatform") version "2.4.21"
}

repositories { mavenCentral() }

kotlin {
    mingwX64 {
        compilations.getByName("main").cinterops.create("winsockProbe") {
            includeDirs(project.file("src/nativeInterop/cinterop"))
        }
        binaries.executable(buildTypes = listOf(RELEASE)) {
            baseName = "iocp-probe"
            entryPoint = "dev.perflab.nativeserve.probe.main"
        }
    }
}
