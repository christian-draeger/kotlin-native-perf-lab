import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
}

repositories { mavenCentral() }

val publicationVersion = providers.gradleProperty("ktserve.version").orNull
val projectUrl = "https://github.com/christian-draeger/kotlin-native-perf-lab"
if (publicationVersion != null) {
    val versionPattern = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z]+([.-][0-9A-Za-z]+)*)?")
    require(versionPattern.matches(publicationVersion) && !publicationVersion.endsWith("-SNAPSHOT", ignoreCase = true)) {
        "ktserve.version must be a release version such as 0.2.0-preview.2"
    }
    group = "io.github.christian-draeger"
    version = publicationVersion
    apply(plugin = "maven-publish")
}

val targetOs = providers.gradleProperty("benchmark.targetOs").getOrElse("linux")
if (publicationVersion == null && targetOs == "macos") {
    layout.buildDirectory.set(layout.projectDirectory.dir("build/macos"))
}

val interopHeaders = project.file("src/nativeInterop/cinterop")
fun KotlinNativeTarget.useKtServeInterop(macos: Boolean) {
    val interops = compilations.getByName("main").cinterops
    interops.create("ktServeSignals") { includeDirs(interopHeaders) }
    if (macos) interops.create("kqueue") { includeDirs(interopHeaders) }
}

kotlin {
    if (publicationVersion != null) {
        jvm { compilerOptions.jvmTarget.set(JvmTarget.JVM_21) }
        linuxX64().useKtServeInterop(macos = false)
        linuxArm64().useKtServeInterop(macos = false)
        macosArm64().useKtServeInterop(macos = true)
        applyDefaultHierarchyTemplate()
        sourceSets {
            getByName("nativeMain").kotlin.setSrcDirs(emptyList<String>())
            getByName("linuxMain").kotlin.apply {
                srcDir("src/nativeMain/kotlin")
                exclude("dev/perflab/ktserve/example/**")
            }
            getByName("macosMain").kotlin.exclude("dev/perflab/ktserve/example/**")
        }
    } else {
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
        native.useKtServeInterop(macos = targetOs == "macos")
        native.binaries.executable {
            baseName = "ktserve"
            entryPoint = "dev.perflab.ktserve.example.main"
        }
        val stressCompilation = native.compilations.create("stress") {
            associateWith(native.compilations.getByName("main"))
        }
        native.binaries.executable("stress", listOf(RELEASE)) {
            compilation = stressCompilation
            baseName = "ktserve-stress-fixture"
            entryPoint = "dev.perflab.ktserve.fixture.main"
        }
        sourceSets {
            getByName("nativeMain") {
                if (targetOs == "macos") kotlin.setSrcDirs(listOf("src/macosMain/kotlin"))
                kotlin.srcDir("src/nativeShared/kotlin")
            }
            if (targetOs == "linux") getByName("nativeTest").kotlin.srcDir("src/linuxTest/kotlin")
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

if (publicationVersion != null) {
    val documentationJar = tasks.register<Jar>("publicationDocumentation") {
        archiveClassifier.set("javadoc")
        from("README.md", "TRANSPORT.md")
    }
    extensions.configure<PublishingExtension> {
        repositories.maven {
            name = "staging"
            url = layout.buildDirectory.dir("maven-staging/$publicationVersion").get().asFile.toURI()
        }
        publications.withType<MavenPublication>().configureEach {
            artifact(documentationJar)
            pom {
                name.set("ktServe")
                description.set("An HTTP framework for Kotlin on the JVM and Kotlin/Native")
                url.set(projectUrl)
                developers {
                    developer {
                        id.set("christian-draeger")
                        url.set("https://github.com/christian-draeger")
                    }
                }
                scm {
                    url.set(projectUrl)
                    connection.set("scm:git:$projectUrl.git")
                }
            }
        }
    }
    providers.environmentVariable("KTSERVE_PGP_PRIVATE_KEY").orNull?.let { key ->
        apply(plugin = "signing")
        val passphrase = providers.environmentVariable("KTSERVE_PGP_PASSPHRASE").getOrElse("")
        extensions.configure<SigningExtension> {
            useInMemoryPgpKeys(key, passphrase)
            sign(extensions.getByType<PublishingExtension>().publications)
        }
    }
}
