plugins {
    kotlin("multiplatform") version "2.4.21"
}

repositories { mavenCentral() }

val targetOs = providers.gradleProperty("benchmark.targetOs").getOrElse("linux")
val targetArch = providers.gradleProperty("benchmark.targetArch").getOrElse(System.getProperty("os.arch"))
val opensslRoot = providers.gradleProperty("ktserve.opensslRoot").orNull?.let(project::file)
val https = opensslRoot != null
require(!https || targetOs == "linux") { "ktserve.opensslRoot is available for the Linux HTTPS experiment" }

kotlin {
    val native = when (targetOs) {
        "linux" -> when (targetArch) {
            "amd64", "x86_64" -> linuxX64("native")
            "aarch64", "arm64" -> linuxArm64("native")
            else -> error("Unsupported Linux architecture $targetArch")
        }
        "macos" -> macosArm64("native")
        else -> error("Unsupported native target OS $targetOs")
    }
    native.binaries.executable {
        baseName = "ktserve"
        entryPoint = "codes.draeger.ktserve.cli.staticfiles.main"
    }
    if (opensslRoot != null) {
        val triplet = if (targetArch in listOf("aarch64", "arm64")) "aarch64-linux-gnu" else "x86_64-linux-gnu"
        native.binaries.configureEach { linkerOpts("-L${opensslRoot}/lib/$triplet", "-L${opensslRoot}/lib") }
    }
    native.compilations.getByName("main").cinterops.create("cliSignals") {
        includeDirs(file("src/nativeInterop/cinterop"), file("../../../src/nativeInterop/cinterop"))
    }
    sourceSets.named("nativeMain") {
        kotlin.srcDir(if (https) "src/httpsEnabled/kotlin" else "src/httpsUnavailable/kotlin")
        dependencies {
            implementation("codes.draeger:ktserve")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
    }
    sourceSets.named("nativeTest") {
        dependencies { implementation(kotlin("test")) }
    }
}
