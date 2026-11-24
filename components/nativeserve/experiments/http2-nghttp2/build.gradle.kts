plugins {
    kotlin("multiplatform") version "2.4.21"
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
    native.binaries.executable {
        baseName = "nativeserve-http2-session-probe"
        entryPoint = "dev.perflab.nativeserve.http2.main"
        linkerOpts(file(nghttp2Archive.get()).absolutePath)
    }
}
