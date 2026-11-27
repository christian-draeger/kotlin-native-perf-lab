plugins { kotlin("multiplatform") version "2.4.21" }
repositories { mavenCentral() }

val arch = providers.gradleProperty("benchmark.targetArch").getOrElse(System.getProperty("os.arch"))
kotlin {
    val native = when (arch) {
        "amd64", "x86_64" -> linuxX64("native")
        "aarch64", "arm64" -> linuxArm64("native")
        else -> error("Unsupported Linux architecture $arch")
    }
    native.binaries.executable { entryPoint = "probe.main" }
    sourceSets.named("nativeMain") {
        dependencies {
            implementation("codes.draeger:ktserve")
            implementation("io.opentelemetry.kotlin:implementation:0.11.0")
            implementation("io.opentelemetry.kotlin:exporters-otlp:0.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
    }
}
