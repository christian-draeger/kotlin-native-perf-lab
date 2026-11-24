plugins {
    kotlin("jvm") version "2.4.21"
    `java-library`
}

repositories { mavenCentral() }

group = "dev.perflab"
version = "0.1.0-preview"

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:4.2.0"))
    api("org.springframework.boot:spring-boot-starter-webflux")
    api("dev.perflab:ktserve-webflux")
    implementation(kotlin("reflect"))
    testImplementation(kotlin("test"))
}
