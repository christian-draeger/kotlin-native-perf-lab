plugins {
    kotlin("jvm") version "2.4.21"
    `java-library`
}

repositories { mavenCentral() }

group = "io.github.christian-draeger"
version = "0.1.0-preview"

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:4.2.0"))
    api("org.springframework.boot:spring-boot-starter-webflux")
    api("io.github.christian-draeger:ktserve-webflux")
    implementation(kotlin("reflect"))
    testImplementation(kotlin("test"))
}
