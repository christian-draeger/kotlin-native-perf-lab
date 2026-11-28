# Taskboard API with ktServe

Create a task, list the tasks in a workspace, and mark a task complete. The example
runs on JVM and Kotlin/Native. It binds to loopback, keeps tasks in memory and uses
a fixed token for local demonstrations. Workspaces group demo data and share that
token. A production app supplies its own credential verifier and persistent store.

## Run the app

From the repository root, build and run with JDK 21. The default build uses the
ktServe checkout. Apple Silicon users pass `-Pbenchmark.targetOs=macos` to Gradle
and use `build/macos` in the launch command.

```sh
app=components/ktserve/examples/extensions
./components/ktserve/gradlew -p "$app" installJvmConsumer
java -cp "$app/build/jvm-consumer/*" codes.draeger.examples.extensions.MainKt 8080
```

For a Native build, use `linkReleaseExecutableNative` and launch
`$app/build/bin/native/releaseExecutable/extension-consumer.kexe 8080`. The Native
app uses the same routes and portable extensions.

## Create and complete a task

Run these commands against a fresh instance.

```sh
base=http://127.0.0.1:8080
curl -fsS "$base/api/tasks" -H 'Authorization: Bearer taskboard-demo' \
  -H 'X-Workspace: design' -H 'Content-Type: application/json' -d '{"title":"Review design"}'
curl -fsS "$base/api/tasks/1" -X PATCH -H 'Authorization: Bearer taskboard-demo' \
  -H 'X-Workspace: design' -H 'Content-Type: application/json' -d '{"done":true}'
curl -fsS "$base/api/tasks" -H 'Authorization: Bearer taskboard-demo' -H 'X-Workspace: design'
curl -fsS "$base/openapi.json"
```

The Bearer extension parses the Authorization header. A custom request hook checks
the demo token, passes a typed workspace attribute to handlers and echoes the workspace
in the response. CORS allows `http://localhost:5173` and handles browser preflights
before the authorization hook. Validation returns HTTP 422 for a title shorter than
three characters, and the generated OpenAPI schema exposes the same constraint.
The shared access logger writes to stderr. The JVM app also prints completed server
spans from a bounded background batch processor.

## Tests and profiling

The [HTTP smoke](smoke.py) checks authorization, allowed and denied preflights, typed
validation, workspace isolation, task updates and OpenAPI. The Maven rehearsal runs
the same smoke against staged artifacts with `-Pktserve.version=<version>`.

Use the [extension benchmark](../../../../scripts/benchmark-extensions.md) to measure
the installed runtime cost of each extension on the same HTTP workload.
