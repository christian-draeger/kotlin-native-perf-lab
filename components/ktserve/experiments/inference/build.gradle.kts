plugins {
    kotlin("jvm") version "2.4.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("codes.draeger:ktserve")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("codes.draeger.examples.inference.MainKt")
}
