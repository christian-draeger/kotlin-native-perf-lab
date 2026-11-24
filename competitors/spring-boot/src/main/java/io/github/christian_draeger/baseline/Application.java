package io.github.christian_draeger.baseline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @GetMapping(value = "/health", produces = MediaType.TEXT_PLAIN_VALUE)
    String health() {
        return "ok";
    }

    @GetMapping(value = "/json", produces = MediaType.APPLICATION_JSON_VALUE)
    User jsonUser() {
        return new User(42, "benchmark-user", true);
    }

    @GetMapping(value = "/users/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<User> userById(@PathVariable String id) {
        try {
            return ResponseEntity.ok(new User(Integer.parseInt(id), "benchmark-user", true));
        } catch (NumberFormatException invalid) {
            return ResponseEntity.badRequest().build();
        }
    }

    record User(int id, String name, boolean active) {}
}
