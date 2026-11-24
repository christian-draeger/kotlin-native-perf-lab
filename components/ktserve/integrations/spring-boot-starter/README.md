# ktServe Spring Boot starter

Add the starter to a Spring Boot 4.2.0 WebFlux application on JDK 21 and enable ktServe. The [independent example](../../examples/spring-webflux) resolves the starter from this checkout through a Gradle composite.

```kotlin
dependencies {
    implementation("io.github.christian-draeger:ktserve-spring-boot-starter")
}
```

```yaml
server:
  address: 127.0.0.1
  port: 8080
  shutdown: graceful
ktserve:
  enabled: true
  connections: 1024
  request-body-bytes: 65536
  response-body-bytes: 65536
  drain-timeout: 2s
```

Boot's standard address and port settings configure the listener. The `ktserve` properties configure header and body bounds and handler, idle and shutdown timeouts. An explicitly provided `ReactiveWebServerFactory` takes precedence. With the opt-in disabled, Boot selects its configured WebFlux server.

```sh
KTSERVE_ENABLED=true SERVER_ADDRESS=127.0.0.1 \
  ./components/ktserve/gradlew -p components/ktserve/examples/spring-webflux run
curl -fsS http://127.0.0.1:8080/health
```

The starter supports reactive controllers and functional routes through Spring's existing HTTP handler. It listens on one fixed-port IPv4 HTTP/1.x connector. TLS and HTTP/2 deployment use an ingress. WebSocket upgrades and server-specific Netty customizers are outside this adapter's supported surface. Management endpoints can use the application listener. Separate management listeners and Netty-specific metrics have no compatibility guarantee.
