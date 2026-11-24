# ktServe Maven releases

The first Maven Central candidate is `io.github.christian-draeger:ktserve:0.2.0-preview.2`. The Maven group follows the repository owner's GitHub namespace. Kotlin and Java imports use `io.github.christian_draeger.ktserve` because source package identifiers require a Java-compatible spelling.

Once the version is live on Central, a Kotlin/JVM application on JDK 21 can use the following dependency. In a Kotlin Multiplatform project, place the dependency in the source set that uses ktServe. Shared routing can live in `commonMain`. Server startup uses the platform source set.

```kotlin
repositories { mavenCentral() }

dependencies {
    implementation("io.github.christian-draeger:ktserve:0.2.0-preview.2")
}
```

Gradle reads the root module metadata and selects JVM, Linux x86_64, Linux ARM64 or macOS ARM64. Native variants include their C interop libraries. For a JVM project with a Maven `pom.xml`, use `io.github.christian-draeger:ktserve-jvm` at the same version. The first publication covers the standalone ktServe library. Ktor and WebFlux adapters are still available as checkout integrations.

## Stage and test a version

Use an Apple Silicon macOS host with full Xcode and JDK 21, or run **ktServe Maven staging** in GitHub Actions. Build all variants together so the root metadata links to the exact same version on every platform.

```sh
project=components/ktserve
version=0.2.0-preview.2
"$project/gradlew" -p "$project" "-Pktserve.version=$version" \
  publishAllPublicationsToStagingRepository
"$project/gradlew" -p "$project/examples/hello-jvm" "-Pktserve.version=$version" run
```

The repository is under `components/ktserve/build/maven-staging/<version>/`. With the version property set, the examples resolve from that repository and disable their composite build. Check the running JVM example with `curl -fsS http://127.0.0.1:8080/health`. CI also tests the packaged Native library by linking independent consumers and running their executables on all three Native targets.

## Publish to Maven Central

1. Verify `io.github.christian-draeger` in the [Central Publisher Portal](https://central.sonatype.com/). The repository and Maven POM declare the [Apache License 2.0](../../LICENSE). [Namespace verification](https://central.sonatype.org/register/namespace/)
2. Build the signed candidate from its release commit in a clean checkout. Pass the ASCII-armored private key as `KTSERVE_PGP_PRIVATE_KEY` and its passphrase as `KTSERVE_PGP_PASSPHRASE` through the environment. Make the public key available to Central. [Signing requirements](https://central.sonatype.org/publish/requirements/gpg/)
3. Zip the versioned repository content, preserving the `io/github/...` layout and excluding generated `maven-metadata.xml` files and their checksums. Upload the signed bundle via Central Portal and review the validation result before publishing. [Central Portal bundle upload](https://central.sonatype.org/publish/publish-portal-upload/)
4. Once Central lists the version, verify the dependency in a fresh JVM project using `mavenCentral()` and check `/health`. Publish an updated version for follow-up changes. [Central version immutability](https://central.sonatype.org/publish/requirements/immutability/)
