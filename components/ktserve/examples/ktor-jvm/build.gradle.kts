plugins {
    kotlin("jvm") version "2.4.21"
    application
}

repositories { mavenCentral() }

dependencies { implementation("io.github.christian-draeger:ktserve-ktor") }

application { mainClass.set("io.github.christian_draeger.examples.ktor.MainKt") }
