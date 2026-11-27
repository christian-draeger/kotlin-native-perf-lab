#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || "$1" != *.kt || ! -f "$1" ]]; then
    echo 'Usage: ktserve run App.kt [app arguments...]' >&2
    exit 2
fi

source_file="$1"
shift
if [[ "$source_file" != /* ]]; then
    source_file="$PWD/$source_file"
fi

os="$(uname -s)"
arch="$(uname -m)"
case "$os:$arch" in
    Linux:x86_64|Linux:aarch64|Linux:arm64) target_os=linux; checksum=(sha256sum) ;;
    Darwin:arm64) target_os=macos; checksum=(shasum -a 256) ;;
    *) echo "Unsupported host: $os $arch" >&2; exit 2 ;;
esac

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
core="$(cd "$here/../../.." && pwd)"
build_dir="$here/build"
mkdir -p "$build_dir/apps"
work="$(mktemp -d "$build_dir/.run.XXXXXXXX")"
trap 'rm -rf -- "$work"' EXIT

source_dir="$work/src/nativeMain/kotlin"
core_copy="$work/ktserve"
mkdir -p "$source_dir" "$core_copy"
cp "$source_file" "$source_dir/App.kt"
cp "$here/settings.gradle.kts" "$here/build.gradle.kts" "$work/"
# Independent composite builds also need independent outputs for the included build.
cp "$core/build.gradle.kts" "$core/settings.gradle.kts" "$core/gradle.properties" "$core_copy/"
cp -R "$core/src" "$core_copy/"

"$core/gradlew" -p "$work" --console=plain --build-cache \
    -Pkotlin.mpp.applyDefaultHierarchyTemplate=false \
    "-Pbenchmark.targetOs=$target_os" "-Pbenchmark.targetArch=$arch" linkReleaseExecutableNative

# Each build gets a private input and output. Publish complete executables atomically
# so another launch of the same input cannot observe a partially copied binary.
digest="$("${checksum[@]}" < "$source_dir/App.kt")"
app="$build_dir/apps/ktserve-app-$target_os-$arch-${digest%% *}"
mv -f "$work/build/bin/native/releaseExecutable/ktserve-app.kexe" "$app"
rm -rf -- "$work"
trap - EXIT
exec "$app" "$@"
