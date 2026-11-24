import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE

plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
}

repositories { mavenCentral() }

group = "dev.perflab"
version = "0.1.0-preview"

kotlin {
    jvm()
    val arch = providers.gradleProperty("benchmark.targetArch").getOrElse(System.getProperty("os.arch"))
    val native = when (arch) {
        "amd64", "x86_64" -> linuxX64("native")
        "aarch64", "arm64" -> linuxArm64("native")
        else -> error("Unsupported Linux architecture $arch")
    }
    val main = native.compilations.getByName("main")
    val example = native.compilations.create("example") {
        associateWith(main)
        defaultSourceSet.kotlin.srcDir("src/example/kotlin")
        defaultSourceSet.dependencies {
            implementation("io.ktor:ktor-server-cio:3.6.1")
            implementation("io.ktor:ktor-server-content-negotiation:3.6.1")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.1")
        }
    }
    native.binaries.executable(buildTypes = listOf(RELEASE)) {
        compilation = example
        baseName = "nativeserve-ktor-example"
        entryPoint = "dev.perflab.nativeserve.ktor.example.main"
    }
    sourceSets {
        commonMain.dependencies {
            api("io.ktor:ktor-server-core:3.6.1")
            api("dev.perflab:nativeserve")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        jvmTest {
            kotlin.srcDir("src/example/kotlin")
            dependencies {
                implementation(kotlin("test"))
                implementation("io.ktor:ktor-server-cio:3.6.1")
                implementation("io.ktor:ktor-server-netty:3.6.1")
                implementation("io.ktor:ktor-server-content-negotiation:3.6.1")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.1")
            }
        }
    }
}
