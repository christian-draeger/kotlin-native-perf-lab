import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
}

val ktserveVersion = providers.gradleProperty("ktserve.version").get()
val useMavenCentral = providers.gradleProperty("ktserve.publicRepository").orNull == "true"

repositories {
    mavenCentral()
    if (!useMavenCentral) exclusiveContent {
        forRepository { maven { url = uri("../../build/maven-staging/$ktserveVersion") } }
        filter { includeGroup("codes.draeger") }
    }
}

val macos = providers.gradleProperty("benchmark.targetOs").getOrElse("linux") == "macos"
val architecture = providers.gradleProperty("benchmark.targetArch").getOrElse(System.getProperty("os.arch"))
if (macos) layout.buildDirectory.set(layout.projectDirectory.dir("build/macos"))

kotlin {
    val jvm = jvm { compilerOptions.jvmTarget.set(JvmTarget.JVM_21) }
    val native = when {
        macos -> macosArm64("native")
        architecture in setOf("amd64", "x86_64") -> linuxX64("native")
        architecture in setOf("arm64", "aarch64") -> linuxArm64("native")
        else -> error("Unsupported Linux architecture $architecture")
    }
    native.binaries.executable {
        baseName = "extension-consumer"
        entryPoint = "codes.draeger.examples.extensions.main"
    }
    sourceSets {
        commonMain.dependencies {
            implementation("codes.draeger:ktserve:$ktserveVersion")
            for (name in listOf("access-log", "bearer", "cors", "openapi", "validation")) {
                implementation("codes.draeger:ktserve-$name:$ktserveVersion")
            }
        }
        jvmMain.dependencies {
            implementation("codes.draeger:ktserve-opentelemetry:$ktserveVersion")
        }
    }
    tasks.register<Sync>("installJvmConsumer") {
        from(tasks.named("jvmJar"), jvm.compilations["main"].runtimeDependencyFiles)
        into(layout.buildDirectory.dir("jvm-consumer"))
    }
}
