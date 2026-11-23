import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE

plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
}

repositories { mavenCentral() }

kotlin {
    jvm()
    val arch = providers.gradleProperty("benchmark.targetArch").getOrElse(System.getProperty("os.arch"))
    val linux = when (arch) {
        "amd64", "x86_64" -> linuxX64("native")
        "aarch64", "arm64" -> linuxArm64("native")
        else -> error("Unsupported Linux architecture $arch")
    }
    linux.compilations.getByName("main").cinterops.create("reactorSignals") {
        includeDirs(project.file("src/nativeInterop/cinterop"))
    }
    linux.binaries.executable {
        baseName = "nativeserve"
        entryPoint = "dev.perflab.nativeserve.example.main"
    }
    linux.binaries.executable("stress", listOf(RELEASE)) {
        compilation = linux.compilations.getByName("test")
        baseName = "reactor-stress-fixture"
        entryPoint = "dev.perflab.nativeserve.fixture.main"
    }
    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
