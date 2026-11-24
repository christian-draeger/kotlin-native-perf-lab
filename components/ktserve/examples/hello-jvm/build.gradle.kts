plugins {
    kotlin("jvm") version "2.4.21"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("dev.perflab:ktserve")
}

application {
    mainClass.set("dev.perflab.examples.hello.MainKt")
}
