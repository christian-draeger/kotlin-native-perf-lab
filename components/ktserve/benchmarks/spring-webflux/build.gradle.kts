plugins {
    kotlin("jvm") version "2.4.21"
    application
}

repositories { mavenCentral() }

dependencies { implementation("io.github.christian-draeger:ktserve-spring-boot-starter") }

application { mainClass.set("io.github.christian_draeger.benchmark.spring.BenchmarkKt") }
