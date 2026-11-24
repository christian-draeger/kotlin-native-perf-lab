plugins {
    kotlin("jvm") version "2.4.21"
    application
}

val stagedVersion = providers.gradleProperty("ktserve.version").orNull
val useMavenCentral = providers.gradleProperty("ktserve.publicRepository").orNull == "true"

repositories {
    mavenCentral()
    if (stagedVersion != null && !useMavenCentral) exclusiveContent {
        forRepository { maven { url = uri("../../build/maven-staging/$stagedVersion") } }
        filter { includeGroup("codes.draeger") }
    }
}

dependencies {
    implementation(if (stagedVersion == null) "codes.draeger:ktserve" else "codes.draeger:ktserve:$stagedVersion")
}

application {
    mainClass.set("codes.draeger.examples.hello.MainKt")
}
