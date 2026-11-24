package codes.draeger.examples.spring

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@SpringBootApplication(proxyBeanMethods = false)
class HelloApplication

@RestController
class HealthController {
    @GetMapping("/health")
    fun health() = mapOf("status" to "ok")
}

fun main(args: Array<String>) {
    runApplication<HelloApplication>(*args)
}
