# Standalone REST JVM consumer

The separate Gradle application serves a lightweight HTTP/1.1 JSON endpoint. It listens on loopback. Run the origin and ingress in the same trusted network namespace. The origin accepts a user identity only across that deployment boundary. Processes with direct access to the origin are trusted to make identity assertions.

Build the consumer from the repository root with JDK 21.

```sh
example=components/ktserve/examples/rest-jvm
./components/ktserve/gradlew -p "$example" installDist
export KTSERVE_REST_USER=writer
"$example/build/install/ktserve-rest-jvm/bin/ktserve-rest-jvm" 8081
```

The sample caps request bodies at 4 KiB. An authenticated PUT to /items/42 with a JSON message returns the corresponding item. The application returns HTTP 400 for malformed JSON and HTTP 413 above the configured limit. The example validates and returns an item representation. Application persistence is left to the consumer.

## Authenticate clients at the TLS ingress

Run Caddy 2.8 or later with the supplied Caddyfile. Set KTSERVE_REST_USER to the origin's authorized username. Run `caddy hash-password` interactively and set KTSERVE_REST_PASSWORD_HASH to its output. Set KTSERVE_REST_TLS_CERT and KTSERVE_REST_TLS_KEY to a certificate valid for localhost and its private key. Keep the credential hash and key out of source control. The client must trust the issuing CA.

```sh
caddy run --config components/ktserve/examples/rest-jvm/Caddyfile --adapter caddyfile
```

The ingress verifies the credentials, overwrites the client-supplied identity field with the authenticated username, and removes the Authorization field before forwarding. Keep the origin's network namespace private and expose the public TLS listener from a trusted edge configuration. For a local check pass the trusted issuing CA to curl. Curl prompts for the password.

```sh
curl --cacert "$KTSERVE_REST_CA" --user "$KTSERVE_REST_USER" \
  --request PUT --header 'Content-Type: application/json' \
  --data '{"message":"hello"}' https://localhost:8443/items/42
```

[Caddy authentication](https://caddyserver.com/docs/caddyfile/directives/basic_auth) · [Caddy upstream headers](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy#headers)

The consumer smoke uses a trusted loopback test driver to verify authorization, valid JSON, malformed and oversized requests, and listener release after SIGTERM.

```sh
python3 "$example/smoke.py" "$example/build/install/ktserve-rest-jvm/bin/ktserve-rest-jvm"
```
