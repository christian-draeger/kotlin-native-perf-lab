plugins {
    kotlin("multiplatform") version "2.4.21"
}

val stagedVersion = providers.gradleProperty("ktserve.version").orNull

repositories {
    mavenCentral()
    if (stagedVersion != null) exclusiveContent {
        forRepository { maven { url = uri("../../build/maven-staging/$stagedVersion") } }
        filter { includeGroup("codes.draeger") }
    }
}

val targetOs = providers.gradleProperty("benchmark.targetOs").getOrElse("linux")
if (targetOs == "macos") layout.buildDirectory.set(layout.projectDirectory.dir("build/macos"))

kotlin {
    val arch = providers.gradleProperty("benchmark.targetArch").getOrElse(System.getProperty("os.arch"))
    val native = when (targetOs) {
        "linux" -> when (arch) {
            "amd64", "x86_64" -> linuxX64("native")
            "aarch64", "arm64" -> linuxArm64("native")
            else -> error("Unsupported Linux architecture $arch")
        }
        "macos" -> macosArm64("native")
        else -> error("Unsupported native target OS")
    }
    native.binaries.executable {
        baseName = "hello"
        entryPoint = if (targetOs == "linux") "codes.draeger.examples.hello.linuxMain" else "codes.draeger.examples.hello.main"
    }
    sourceSets.named("nativeMain") {
        if (targetOs == "linux") kotlin.srcDir("src/linuxMain/kotlin")
        dependencies {
            implementation(if (stagedVersion == null) "codes.draeger:ktserve" else "codes.draeger:ktserve:$stagedVersion")
        }
    }
}
