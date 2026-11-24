plugins {
    kotlin("jvm") version "2.4.21"
    application
}

repositories { mavenCentral() }

dependencies { implementation("codes.draeger:ktserve-ktor") }

application { mainClass.set("codes.draeger.examples.ktor.MainKt") }
