import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    kotlin("multiplatform") version "2.4.21" apply false
}

val publicationVersion = providers.gradleProperty("ktserve.version").orNull
val targetOs = providers.gradleProperty("benchmark.targetOs").getOrElse("linux")
val targetArch = providers.gradleProperty("benchmark.targetArch").getOrElse(System.getProperty("os.arch"))
val projectUrl = "https://github.com/christian-draeger/ktserve"

subprojects {
    apply(plugin = "org.jetbrains.kotlin.multiplatform")
    val supportsNative = name != "ktserve-opentelemetry"
    group = "codes.draeger"
    version = publicationVersion ?: "0.0.0-dev"
    repositories { mavenCentral() }

    extensions.configure<KotlinMultiplatformExtension> {
        jvm {
            if (publicationVersion != null || providers.gradleProperty("benchmark.routingProbe").isPresent) {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
            }
        }
        if (supportsNative && publicationVersion != null) {
            linuxX64()
            linuxArm64()
            macosArm64()
            applyDefaultHierarchyTemplate()
        } else if (supportsNative) {
            when (targetOs) {
                "linux" -> when (targetArch) {
                    "amd64", "x86_64" -> linuxX64("native")
                    "aarch64", "arm64" -> linuxArm64("native")
                    else -> error("Unsupported Linux architecture $targetArch")
                }
                "macos" -> macosArm64("native")
                else -> error("Unsupported native target OS $targetOs")
            }
        }
        sourceSets {
            commonMain.dependencies {
                api(if (publicationVersion == null) "codes.draeger:ktserve" else "codes.draeger:ktserve:$publicationVersion")
            }
            commonTest.dependencies {
                implementation(kotlin("test"))
            }
        }
    }

    if (publicationVersion != null) {
        apply(plugin = "maven-publish")
        val licenseFile = rootProject.file("../../../LICENSE")
        tasks.withType<Jar>().configureEach { from(licenseFile) { into("META-INF") } }
        val documentationJar = tasks.register<Jar>("publicationDocumentation") {
            archiveClassifier.set("javadoc")
            from(rootProject.file("README.md"))
        }
        extensions.configure<PublishingExtension> {
            repositories.maven {
                name = "staging"
                url = rootProject.layout.buildDirectory.dir("maven-staging/$publicationVersion").get().asFile.toURI()
            }
            publications.withType<MavenPublication>().configureEach {
                artifact(documentationJar)
                pom {
                    name.set(project.name)
                    description.set("An optional ktServe HTTP server extension")
                    url.set(projectUrl)
                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
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
}
