# ktServe Maven releases

The first Maven Central candidate is `codes.draeger:ktserve:0.2.0-preview.2`. The Maven group uses the verified `codes.draeger` domain namespace. Kotlin and Java imports use `codes.draeger.ktserve`.

Once the version is live on Central, a Kotlin/JVM application on JDK 21 can use the following dependency. In a Kotlin Multiplatform project, place the dependency in the source set that uses ktServe. Shared routing can live in `commonMain`. Server startup uses the platform source set.

```kotlin
repositories { mavenCentral() }

dependencies {
    implementation("codes.draeger:ktserve:0.2.0-preview.2")
}
```

Gradle reads the root module metadata and selects JVM, Linux x86_64, Linux ARM64 or macOS ARM64. Native variants include their C interop libraries. For a JVM project with a Maven `pom.xml`, use `codes.draeger:ktserve-jvm` at the same version. The first publication covers the standalone ktServe library. Ktor and WebFlux adapters are still available as checkout integrations.

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

## Publish from GitHub Actions

Publish a GitHub Release from the release commit with a tag such as `v0.2.0-preview.2`. The tag supplies the Maven version. The workflow stages all variants, runs the JVM and Native consumers, signs the exact tested files and waits for Central to report `PUBLISHED`. The Actions summary contains the Central deployment ID. A manual run of **ktServe Maven staging** builds and tests a version for inspection.

Configure the GitHub `maven-central` environment for release tags matching `v*`. Use these environment secrets, named as in the [Kotlin library template](https://github.com/christian-draeger/kotlin-library-template/blob/main/INITIAL_SETUP.md).

| Secret | Value |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | The username from a Central Portal user token with publishing access to `codes.draeger`. |
| `MAVEN_CENTRAL_PASSWORD` | The password from the same Central Portal user token. |
| `SIGNING_IN_MEMORY_KEY` | The ASCII-armored private release key or the base64 key body used by the template. |
| `SIGNING_IN_MEMORY_KEY_ID` | The primary release key fingerprint. |
| `SIGNING_IN_MEMORY_KEY_PASSWORD` | The release key passphrase when the key is protected. |

Check the recorded deployment in Central Portal before retrying any run that submitted a bundle and then failed. Central versions are immutable. [Generate a Portal token](https://central.sonatype.org/publish/generate-portal-token/) · [Publisher API](https://central.sonatype.org/publish/publish-portal-api/)

## Publish via the Central Portal

1. Verify `codes.draeger` in the [Central Publisher Portal](https://central.sonatype.com/). The repository and Maven POM declare the [Apache License 2.0](../../LICENSE). [Namespace verification](https://central.sonatype.org/register/namespace/)
2. Make the public part of the release PGP key available to Central. Download `ktserve-maven-<version>` from the successful staging run for the release revision. With the release key in the local GnuPG keyring, run the command below from the repository root. [Signing requirements](https://central.sonatype.org/publish/requirements/gpg/)
3. Upload the signed ZIP via Central Portal and review the validation result before publishing. [Central Portal bundle upload](https://central.sonatype.org/publish/publish-portal-upload/)
4. Once Central lists the version, verify the dependency in a fresh JVM project using `mavenCentral()` and check `/health`. Publish an updated version for follow-up changes. [Central version immutability](https://central.sonatype.org/publish/requirements/immutability/)

```sh
python3 scripts/package-ktserve-maven.py /path/to/staged-repository 0.2.0-preview.2 \
  "$RELEASE_KEY_FINGERPRINT" ktserve-0.2.0-preview.2-central.zip
```

The staging directory is the one containing `codes/draeger/`. The command checks the full set of versioned JVM and Native artifacts, verifies their checksums, signs the existing files through GnuPG and writes the Central directory layout into the ZIP. GnuPG prompts locally if the key needs a passphrase. The manual Portal upload uses your Portal session.
