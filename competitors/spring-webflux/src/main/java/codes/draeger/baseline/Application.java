package codes.draeger.baseline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@SpringBootApplication(proxyBeanMethods = false)
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    RouterFunction<ServerResponse> routes() {
        return RouterFunctions.route()
                .GET("/health", request -> ServerResponse.ok().contentType(MediaType.TEXT_PLAIN).bodyValue("ok"))
                .GET("/json", request -> jsonUser(42))
                .GET("/users/{id}", this::userById)
                .build();
    }

    private Mono<ServerResponse> userById(ServerRequest request) {
        final int id;
        try {
            id = Integer.parseInt(request.pathVariable("id"));
        } catch (NumberFormatException invalid) {
            return ServerResponse.badRequest().build();
        }
        return jsonUser(id);
    }

    private static Mono<ServerResponse> jsonUser(int id) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new User(id, "benchmark-user", true));
    }

    record User(int id, String name, boolean active) {}
}
