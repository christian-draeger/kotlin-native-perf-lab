# A private HTTP/1.1 origin behind Caddy

This deployment puts the public TLS listener and the ktServe origin on the same Linux host. Caddy binds the chosen ingress address. ktServe binds IPv4 loopback on the origin port. Both services can run as unprivileged users. Expose the ingress port and restrict the origin port to local processes. Use a trusted certificate issued for the public host.

Use a **version-pinned** build of your ktServe application. The standalone [Maven guide](../../RELEASING.md) shows how to consume a version. The Ubuntu smoke workflow also provides checksummed native binary snapshots for testing the deployment with the bundled routes. The release test pairs those routes with [Caddy 2.11.9](https://github.com/caddyserver/caddy/releases/tag/v2.11.9).

```sh
PORT=8080 BIND_HOST=127.0.0.1 ./ktserve
```

In a separate service process, point Caddy to the origin and configure the certificate. Set the binding address and port for the intended network. To test on the same host, use a certificate for `localhost`, bind `127.0.0.1`, and trust your issuing CA in the client.

```sh
export KTSERVE_INGRESS_HOST=api.example.com
export KTSERVE_INGRESS_BIND=192.0.2.10
export KTSERVE_INGRESS_PORT=8443
export KTSERVE_INGRESS_CERT=/etc/ktserve-ingress/certificate.pem
export KTSERVE_INGRESS_KEY=/etc/ktserve-ingress/private-key.pem
caddy validate --config components/ktserve/deployment/http1/Caddyfile
caddy run --config components/ktserve/deployment/http1/Caddyfile
```

## Request policy and resource limits

- Caddy handles TLS, matches the configured public host, and forwards HTTP/1.1 to loopback. The origin receives the configured authority. Caddy generates `X-Forwarded-For`, `X-Forwarded-Proto`, and `X-Forwarded-Host` for a directly connected client. The recipe discards other client-forwarding fields. Applications should trust forwarding metadata only from this ingress path. If another proxy precedes Caddy, qualify that trust configuration separately.
- Caddy caps headers and bodies, applies client and upstream timeouts, and caps origin connections at 256 by default. The server's `ServerLimits` add protocol and retained-resource bounds. Match the proxy and application limits before raising either. The configured connection cap protects the origin. Add client rate or concurrency limits at the hosting edge when the service requires them.
- The sample routes are public to anyone who can reach the TLS endpoint. For protected routes, enforce identity at a trusted access gateway or use verified application authentication and per-request authorization. Forwarding fields only describe connection metadata.
- Provide a trusted path for readiness and restrict operational endpoints to operators. For access logging, apply a policy that excludes credentials, bodies, and sensitive query parameters. Monitor request errors, timeouts, admission pressure, CPU, and memory.

## Upgrade, drain, and recover

Verify the candidate binary or package, and run health and application checks before routing traffic to it. For rolling updates, put independent ingress and origin instances behind a load balancer. Start the replacement instance, verify readiness, and remove the old one from rotation before stopping it. For a single instance, plan a maintenance window, stop Caddy, send SIGTERM to the origin, and verify both have exited before restarting. Keep the previous verified binary and matching configuration available for rollback.

If the ingress is reachable but the origin is unhealthy, check the bind address, the private port, and the origin's resource limits before increasing capacity. Record the certificate configuration, application release, proxy version, and limits for each deployment.

## Validate the deployment

The **ktServe HTTP/1.1 ingress** workflow runs release binaries on Linux x86_64 and ARM64. It checks certificate validation, a loopback-only origin, persistent connections, request bounds, forwarding policy, request bursts, and graceful shutdown. **ktServe paired release qualification** also rehearses replacement and rollback on the same private port behind a running proxy. That rehearsal expects a brief origin outage during the single-instance replacement. Both workflows retain logs and binary digests.

[Caddy server options](https://caddyserver.com/docs/caddyfile/options) · [Reverse proxy controls](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy)
