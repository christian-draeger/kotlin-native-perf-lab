plugins {
    kotlin("multiplatform") version "2.4.21"
}

repositories { mavenCentral() }

kotlin {
    val arch = providers.gradleProperty("benchmark.targetArch").getOrElse(System.getProperty("os.arch"))
    val linux = when (arch) {
        "amd64", "x86_64" -> linuxX64("native")
        "aarch64", "arm64" -> linuxArm64("native")
        else -> error("Unsupported Linux architecture $arch")
    }
    linux.binaries.executable {
        baseName = "hello"
        entryPoint = "dev.perflab.examples.hello.main"
    }
    sourceSets.named("nativeMain") {
        dependencies {
            implementation("dev.perflab:nativeserve")
        }
    }
}
