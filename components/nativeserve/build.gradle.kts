import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE

plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
}

repositories { mavenCentral() }

val targetOs = providers.gradleProperty("benchmark.targetOs").getOrElse("linux")
if (targetOs == "macos") layout.buildDirectory.set(layout.projectDirectory.dir("build/macos"))

kotlin {
    jvm()
    val arch = providers.gradleProperty("benchmark.targetArch").getOrElse(System.getProperty("os.arch"))
    val native = when (targetOs) {
        "linux" -> when (arch) {
            "amd64", "x86_64" -> linuxX64("native")
            "aarch64", "arm64" -> linuxArm64("native")
            else -> error("Unsupported Linux architecture $arch")
        }
        "macos" -> macosArm64("native")
        else -> error("Unsupported native target OS $targetOs")
    }
    native.compilations.getByName("main").cinterops.create("reactorSignals") {
        includeDirs(project.file("src/nativeInterop/cinterop"))
    }
    if (targetOs == "macos") native.compilations.getByName("main").cinterops.create("kqueue") {
        includeDirs(project.file("src/nativeInterop/cinterop"))
    }
    native.binaries.executable {
        baseName = "nativeserve"
        entryPoint = "dev.perflab.nativeserve.example.main"
    }
    val stressCompilation = native.compilations.create("stress") {
        associateWith(native.compilations.getByName("main"))
    }
    native.binaries.executable("stress", listOf(RELEASE)) {
        compilation = stressCompilation
        baseName = "reactor-stress-fixture"
        entryPoint = "dev.perflab.nativeserve.fixture.main"
    }
    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
        getByName("nativeMain") {
            if (targetOs == "macos") kotlin.setSrcDirs(listOf("src/macosMain/kotlin"))
            kotlin.srcDir("src/nativeShared/kotlin")
        }
        if (targetOs == "linux") getByName("nativeTest").kotlin.srcDir("src/linuxTest/kotlin")
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
