# Spring MVC and Servlet feasibility

Defer a Servlet engine until the paired WebFlux measurements establish where NativeServe reduces server cost. Spring MVC can run on JVM NativeServe in principle. Adopting the HTTP exchange alone leaves significant Servlet container semantics to implement, so the first implementation should reuse a maintained container if a transport hook proves viable. [Servlet specification](https://jakarta.ee/specifications/servlet/6.1/jakarta-servlet-spec-6.1.html) · [Spring Boot ServletWebServerFactory](https://github.com/spring-projects/spring-boot/blob/v4.2.0/module/spring-boot-web-server/src/main/java/org/springframework/boot/web/server/servlet/ServletWebServerFactory.java)

| Servlet contract | NativeServe capability and remaining work |
| --- | --- |
| Container bootstrap and lifecycle | NativeServe can bind and drain an HTTP listener. Spring Boot passes `ServletContextInitializer` instances to a `ServletWebServerFactory`. The container owns context creation, component registration, listeners, Servlet init/destroy, error dispatch, and application restart. |
| Request and response | The HTTP exchange exposes method, target, fields, status, cookies through adapters, and bounded body streams. A Servlet bridge must implement request wrappers, encoding, stream and writer modes, flushing and commitment, buffer reset, multipart, redirects, dispatch paths, and listener readiness. |
| Filters and dispatch | The HTTP core can dispatch one active exchange per connection. Servlet filter ordering and wrapper lifetimes must survive `REQUEST`, `ASYNC`, `ERROR`, `FORWARD` and `INCLUDE` dispatch. |
| Sessions and security | Fields and cookies can carry session identifiers. Session lookup, rotation, invalidation, listener events, expiry, and security context semantics need a container or verified application-owned session infrastructure. |
| Blocking and asynchronous I/O | NativeServe suspends on bounded I/O. Blocking Servlet handlers need an executor off the I/O loop. `AsyncContext` needs container dispatch, timeout and error semantics. Servlet nonblocking I/O needs its own read/write listener and readiness contract. |
| Connection and operations | Existing NativeServe limits apply to HTTP/1.x. A Servlet deployment also needs peer metadata, access logging, management endpoints, TLS and upgrade handling, and resource limits compatible with the selected container. |

## Implementation options

- Keep Tomcat or Jetty and run NativeServe as a separate service or ingress-side route. This preserves existing MVC behavior and is suitable when both server models are useful in the same system.
- Reuse a maintained Servlet container behind a version-pinned connector adapter. This keeps Servlet dispatch, filters, sessions, and lifecycle inside the container. Prototype only after identifying a supported boundary that preserves backpressure and avoids sending HTTP through two parsers. Measure compatibility effort at each container upgrade.
- Implement a targeted Servlet subset over the JVM exchange. Even a small controller demo needs substantial compatibility work across output commitment, filters, error pages, session management, and asynchronous dispatch before it can replace a server in an existing Spring Boot application.

## Reference workload

Run one unchanged Spring Boot MVC application on its pinned Tomcat configuration and on the candidate, with identical application JAR, JVM flags, and HTTP/1.1 semantics. Include text health, typed JSON, a parameterized path, validated request bodies, a streaming response, an application filter, a session lifecycle, an asynchronous controller, and an error dispatch. Hold concurrency and offered load constant. Retain raw success and failure data, latency percentiles, CPU-seconds, peak RSS, and limits. Use a separate load host when scoring either server.

The WebFlux adapter tests currently establish HTTP behavior, backpressure, cancellation, and startup/shutdown. The remaining controlled results in [#45](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/45) determine whether transport work has enough resource or latency benefit to support a Servlet implementation milestone. Keep [#46](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/46) open through that decision.
