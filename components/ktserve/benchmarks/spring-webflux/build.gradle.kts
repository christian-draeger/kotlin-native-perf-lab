plugins {
    kotlin("jvm") version "2.4.21"
    application
}

repositories { mavenCentral() }

dependencies { implementation("dev.perflab:ktserve-spring-boot-starter") }

application { mainClass.set("dev.perflab.benchmark.spring.BenchmarkKt") }
