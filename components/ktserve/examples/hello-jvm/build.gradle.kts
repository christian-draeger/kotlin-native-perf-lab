plugins {
    kotlin("jvm") version "2.4.21"
    application
}

val stagedVersion = providers.gradleProperty("ktserve.version").orNull

repositories {
    mavenCentral()
    if (stagedVersion != null) exclusiveContent {
        forRepository { maven { url = uri("../../build/maven-staging/$stagedVersion") } }
        filter { includeGroup("io.github.christian-draeger") }
    }
}

dependencies {
    implementation(if (stagedVersion == null) "dev.perflab:ktserve" else "io.github.christian-draeger:ktserve:$stagedVersion")
}

application {
    mainClass.set("dev.perflab.examples.hello.MainKt")
}
