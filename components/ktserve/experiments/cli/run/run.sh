#!/usr/bin/env bash
set -euo pipefail

usage() {
    printf '%s\n' 'Usage: ktserve run [--dependency <group:artifact:version>]... [--extension <name>]... <App.kt> [app arguments...]' \
        '       ktserve run [--dependency <group:artifact:version>]... [--extension <name>]... -' \
        '       ktserve run [--dependency <group:artifact:version>]... [--extension <name>]... --app -' \
        '       ktserve run [--dependency <group:artifact:version>]... [--extension <name>]... --source - [app arguments...]' >&2
    exit 2
}

build_only=false
if [[ "${1:-}" == --build-only ]]; then
    build_only=true
    shift
fi

dependencies=()
bundled_extensions=()
coordinate_pattern='^[A-Za-z0-9_][A-Za-z0-9_.-]*:[A-Za-z0-9_][A-Za-z0-9_.-]*:[A-Za-z0-9_][A-Za-z0-9_.-]*$'
while [[ "${1:-}" == --dependency || "${1:-}" == --extension ]]; do
    if [[ "$1" == --dependency ]]; then
        coordinate="${2:-}"
        version="${coordinate##*:}"
        if [[ ! "$coordinate" =~ $coordinate_pattern || "$version" == latest.* ]]; then
            echo '--dependency expects group:artifact:version with a fixed version.' >&2
            exit 2
        fi
        dependencies+=("$coordinate")
    else
        case "${2:-}" in
            validation|openapi) bundled_extensions+=("${2}") ;;
            *) echo '--extension expects validation or openapi.' >&2; exit 2 ;;
        esac
    fi
    shift 2
done

stdin_mode=routes
case "${1:-}" in
    --app) [[ "${2:-}" == - ]] || usage; stdin_mode=app; shift ;;
    --source) [[ "${2:-}" == - ]] || usage; stdin_mode=source; shift ;;
esac

source_file="${1:-}"
if [[ "$source_file" == - ]]; then
    [[ $# -eq 1 || "$stdin_mode" == source ]] || usage
else
    [[ "$source_file" == *.kt && -f "$source_file" ]] || usage
fi
shift
if [[ "$source_file" != - && "$source_file" != /* ]]; then
    source_file="$PWD/$source_file"
fi

os="$(uname -s)"
arch="$(uname -m)"
if [[ "$source_file" == - && "$os" == Linux && ( "$arch" == aarch64 || "$arch" == arm64 ) ]]; then
    echo 'ktserve run cannot compile on Linux ARM64 with this Kotlin/Native toolchain. Use Linux x86-64 or Apple Silicon macOS.' >&2
    exit 2
fi
case "$os:$arch" in
    Linux:x86_64|Linux:aarch64|Linux:arm64) target_os=linux; checksum=(sha256sum) ;;
    Darwin:arm64) target_os=macos; checksum=(shasum -a 256) ;;
    *) echo "Unsupported host: $os $arch" >&2; exit 2 ;;
esac
if [[ "$target_os" == macos && -z "${DEVELOPER_DIR:-}" ]] && { [[ "$source_file" == - ]] || "$build_only"; }; then
    full_xcode=/Applications/Xcode.app/Contents/Developer
    if [[ -d "$full_xcode" && "$(xcode-select -p 2>/dev/null || true)" == */CommandLineTools ]]; then
        export DEVELOPER_DIR="$full_xcode"
    fi
fi

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
core="$(cd "$here/../../.." && pwd)"
build_dir="$here/build"
if "$build_only"; then
    build_dir="${KTSERVE_CLI_BUILD_ROOT:-$build_dir}"
fi
mkdir -p "$build_dir/apps"
build_dir="$(cd "$build_dir" && pwd)"
work="$(mktemp -d "$build_dir/.run.XXXXXXXX")"
trap 'rm -rf -- "$work"' EXIT

source_dir="$work/src/nativeMain/kotlin"
core_copy="$work/ktserve"
mkdir -p "$source_dir" "$core_copy"
if [[ "$source_file" == - ]]; then
    snippet="$work/input.kt"
    snippet_limit=$((1024 * 1024))
    head -c "$((snippet_limit + 1))" > "$snippet"
    if (( $(wc -c < "$snippet") > snippet_limit )); then
        echo 'ktserve run accepts up to 1 MiB of Kotlin code on stdin.' >&2
        exit 2
    fi
    if ! iconv -f UTF-8 -t UTF-8 "$snippet" >/dev/null 2>&1; then
        echo 'ktserve run expects UTF-8 input.' >&2
        exit 2
    fi
    if ! LC_ALL=C grep -q '[^[:space:]]' "$snippet"; then
        echo 'Pass Kotlin code on stdin to ktserve run.' >&2
        exit 2
    fi
    if [[ "$stdin_mode" == source ]]; then
        cp "$snippet" "$source_dir/App.kt"
    elif [[ "$stdin_mode" == app ]]; then
        {
            printf 'import codes.draeger.ktserve.*\n\nfun main() {\n'
            cat "$snippet"
            printf '\n}\n'
        } > "$source_dir/App.kt"
    else
        {
            cat <<'KOTLIN'
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import codes.draeger.ktserve.*
import kotlinx.cinterop.toKString
import platform.posix.getenv

fun main() {
    val portText = getenv("PORT")?.toKString() ?: "8080"
    val port = portText.toIntOrNull()
    require(port != null && port in 1..65535) { "PORT must be between 1 and 65535" }
    val host = getenv("BIND_HOST")?.toKString() ?: "127.0.0.1"
    serve(host = host, port = port) {
KOTLIN
            cat "$snippet"
            printf '\n    }\n}\n'
        } > "$source_dir/App.kt"
    fi
else
    cp "$source_file" "$source_dir/App.kt"
fi
cp "$here/settings.gradle.kts" "$here/build.gradle.kts" "$work/"
if (( ${#dependencies[@]} > 0 )); then
    printf '%s\n' "${dependencies[@]}" > "$work/dependencies.txt"
fi
# Independent composite builds also need independent outputs for the included build.
cp "$core/build.gradle.kts" "$core/settings.gradle.kts" "$core/gradle.properties" "$core_copy/"
cp -R "$core/src" "$core_copy/"
if (( ${#bundled_extensions[@]} > 0 )); then
    printf '%s\n' "${bundled_extensions[@]}" | LC_ALL=C sort -u > "$work/bundled-extensions.txt"
    source_extensions="$core/extensions"
    extensions_copy="$core_copy/extensions"
    mkdir -p "$extensions_copy"
    cp "$source_extensions/build.gradle.kts" "$source_extensions/settings.gradle.kts" "$extensions_copy/"
    for module in cors bearer openapi opentelemetry access-log validation; do
        mkdir -p "$extensions_copy/$module"
        if [[ -f "$source_extensions/$module/build.gradle.kts" ]]; then
            cp "$source_extensions/$module/build.gradle.kts" "$extensions_copy/$module/"
        fi
    done
    for module in "${bundled_extensions[@]}"; do
        if [[ ! -d "$extensions_copy/$module/src" ]]; then
            cp -R "$source_extensions/$module/src" "$extensions_copy/$module/"
        fi
    done
fi

gradle_args=(
    -p "$work" --console=plain --build-cache
    -Pkotlin.mpp.applyDefaultHierarchyTemplate=false
    "-Pbenchmark.targetOs=$target_os" "-Pbenchmark.targetArch=$arch" linkReleaseExecutableNative
)
if "$build_only"; then
    "$core/gradlew" "${gradle_args[@]}" >&2
else
    "$core/gradlew" "${gradle_args[@]}"
fi
binary="$work/build/bin/native/releaseExecutable/ktserve-app.kexe"
if [[ ! -x "$binary" ]]; then
    echo "The Kotlin/Native build did not produce an executable for $os $arch." >&2
    exit 1
fi

# Each build gets a private input and output. Publish complete executables atomically
# so another launch of the same input cannot observe a partially copied binary.
digest="$(
    {
        cat "$source_dir/App.kt"
        if [[ -f "$work/dependencies.txt" ]]; then
            printf '\0'
            cat "$work/dependencies.txt"
        fi
        if [[ -f "$work/bundled-extensions.txt" ]]; then
            printf '\0extensions\0'
            cat "$work/bundled-extensions.txt"
        fi
    } | "${checksum[@]}"
)"
app="$build_dir/apps/ktserve-app-$target_os-$arch-${digest%% *}"
mv -f "$binary" "$app"
if "$build_only"; then
    printf '%s\n' "$app"
    exit
fi
rm -rf -- "$work"
trap - EXIT
exec "$app" "$@"
