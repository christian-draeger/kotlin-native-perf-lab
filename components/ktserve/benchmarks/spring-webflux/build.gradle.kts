plugins {
    kotlin("jvm") version "2.4.21"
    application
}

repositories { mavenCentral() }

dependencies { implementation("codes.draeger:ktserve-spring-boot-starter") }

application { mainClass.set("codes.draeger.benchmark.spring.BenchmarkKt") }
