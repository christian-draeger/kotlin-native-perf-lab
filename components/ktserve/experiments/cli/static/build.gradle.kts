import java.security.MessageDigest
import java.util.Base64
import org.gradle.api.tasks.bundling.Compression

plugins {
    kotlin("multiplatform") version "2.4.21"
}

repositories { mavenCentral() }

val targetOs = providers.gradleProperty("benchmark.targetOs").getOrElse("linux")
val targetArch = providers.gradleProperty("benchmark.targetArch").getOrElse(System.getProperty("os.arch"))
val opensslRoot = providers.gradleProperty("ktserve.opensslRoot").orNull?.let(project::file)
val https = opensslRoot != null
require(!https || targetOs == "linux") { "ktserve.opensslRoot is available for the Linux HTTPS experiment" }

val coreDirectory = layout.projectDirectory.dir("../../..")
val runKit = tasks.register<Tar>("runKit") {
    archiveFileName.set("run-kit.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("runKit"))
    compression = Compression.GZIP
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    includeEmptyDirs = false
    from(coreDirectory) {
        into("components/ktserve")
        include(
            "gradlew", "gradle/wrapper/**", "gradle.properties", "settings.gradle.kts", "build.gradle.kts", "src/**",
            "experiments/cli/run/run.sh", "experiments/cli/run/build.gradle.kts", "experiments/cli/run/settings.gradle.kts",
            "experiments/cli/run/gradle.properties",
            "extensions/build.gradle.kts", "extensions/settings.gradle.kts", "extensions/*/build.gradle.kts",
            "extensions/openapi/src/commonMain/**", "extensions/validation/src/commonMain/**",
            "extensions/validation/src/jvmMain/**", "extensions/validation/src/nativeMain/**",
        )
    }
    from(coreDirectory.file("../../LICENSE"))
}
val generatedRunKit = layout.buildDirectory.dir("generated/runKit")
val generateRunKit = tasks.register("generateRunKit") {
    val archive = runKit.flatMap { it.archiveFile }
    inputs.file(archive)
    outputs.dir(generatedRunKit)
    doLast {
        val bytes = archive.get().asFile.readBytes()
        val chunks = Base64.getEncoder().encodeToString(bytes).chunked(16384)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        val output = generatedRunKit.get().file("RunKitData.kt").asFile
        output.parentFile.mkdirs()
        output.writeText(buildString {
            appendLine("package codes.draeger.ktserve.cli.staticfiles")
            appendLine()
            appendLine("import kotlin.io.encoding.Base64")
            appendLine()
            appendLine("internal const val RUN_KIT_ID = \"$digest\"")
            appendLine()
            appendLine("internal fun embeddedRunKit(): ByteArray = Base64.decode(listOf(")
            for (chunk in chunks) appendLine("    \"$chunk\",")
            appendLine(").joinToString(\"\"))")
        })
    }
}

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
        kotlin.srcDir(generateRunKit)
        dependencies {
            implementation("codes.draeger:ktserve")
            implementation("codes.draeger:ktserve-access-log")
        }
    }
    sourceSets.named("nativeTest") {
        dependencies { implementation(kotlin("test")) }
    }
}
