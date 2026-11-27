#!/usr/bin/env bash
set -euo pipefail

binary="${1:?Usage: package-ktserve.sh EXECUTABLE VERSION OUTPUT_DIRECTORY [--cli]}"
version="${2:?Missing version}"
output="${3:?Missing output directory}"
mode="${4:-}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export LC_ALL=C

if [[ "$#" -gt 4 || ( -n "$mode" && "$mode" != --cli ) ]]; then
  echo 'Usage: package-ktserve.sh EXECUTABLE VERSION OUTPUT_DIRECTORY [--cli]' >&2
  exit 2
fi
if [[ ! "$version" =~ ^[0-9A-Za-z][0-9A-Za-z.+-]*$ ]]; then
  echo 'Use a version made of letters, digits, dots, plus signs and hyphens.' >&2
  exit 2
fi
case "$(uname -s)" in
  Linux)
    platform=linux
    machine="$(readelf -h "$binary" | awk '/Machine:/ { sub(/^.*Machine:[[:space:]]*/, ""); print }')"
    case "$machine" in
      'Advanced Micro Devices X86-64') arch=x86_64 ;;
      AArch64) arch=aarch64 ;;
      *) echo "Unsupported Linux executable architecture: $machine" >&2; exit 1 ;;
    esac
    interpreter="$(readelf -lW "$binary" | awk '/Requesting program interpreter/ { sub(/]$/, "", $NF); print $NF }')"
    target="Linux $arch"
    compatibility="Executable interpreter: ${interpreter:-none}"
    smoke_target='Ubuntu 24.04'
    ;;
  Darwin)
    platform=macos
    arch="$(lipo -archs "$binary")"
    [[ "$arch" == arm64 ]] || { echo "Unsupported macOS executable architecture: $arch" >&2; exit 1; }
    target='macOS ARM64'
    minimum="$(otool -l "$binary" | awk '$1 == "minos" { print $2 }')"
    compatibility="Mach-O deployment target: ${minimum:-unspecified}"
    smoke_target='macOS 15 on Apple Silicon'
    ;;
  *) echo "Packaging requires Linux or macOS." >&2; exit 1 ;;
esac

mkdir -p "$output"
stage="$(mktemp -d "$output/.ktserve.XXXXXXXX")"
trap 'rm -rf "$stage"' EXIT
archive="ktserve-$version-$platform-$arch.tar.gz"
install -m 0755 "$binary" "$stage/ktserve"
install -m 0644 "$root/LICENSE" "$stage/LICENSE"
cat > "$stage/compatibility.txt" <<EOF
ktServe $version
Target: $target
$compatibility
CI smoke target: $smoke_target

EOF
if [[ "$mode" == --cli ]]; then
  cp -pR "$root/components/ktserve/experiments/cli/public" "$stage/"
  cat >> "$stage/compatibility.txt" <<'EOF'
This archive contains the ktServe CLI preview for serving files over HTTP.
Run ./ktserve serve ./public and open http://127.0.0.1:8080/.
The sample JSON file is available at /api/status.json.
Set PORT or BIND_HOST to change the port or IPv4 address.
EOF
else
  cat >> "$stage/compatibility.txt" <<'EOF'
Run ./ktserve to start the sample at http://127.0.0.1:8080.
Set PORT or BIND_HOST to change the port or IPv4 address.
The health route is GET /health.
EOF
fi
chmod 0644 "$stage/compatibility.txt"
(
  cd "$stage"
  entries=(LICENSE compatibility.txt ktserve)
  if [[ "$mode" == --cli ]]; then
    while IFS= read -r -d '' entry; do
      entries+=("$entry")
    done < <(find public -print0 | sort -z)
  fi
  if [[ "$platform" == linux ]]; then
    tar --no-recursion --format=ustar --mtime=@0 --owner=0 --group=0 --numeric-owner \
      -cf - "${entries[@]}" | gzip -n > "$archive"
    sha256sum "$archive" > "$archive.sha256"
  else
    TZ=UTC touch -h -t 197001010000.00 "${entries[@]}"
    COPYFILE_DISABLE=1 tar --no-recursion -cf - --format ustar --uid 0 --gid 0 --uname root --gname wheel \
      "${entries[@]}" | gzip -n > "$archive"
    shasum -a 256 "$archive" > "$archive.sha256"
  fi
)
mv -- "$stage/$archive" "$stage/$archive.sha256" "$output/"
printf '%s\n' "$output/$archive"
