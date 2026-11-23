#!/usr/bin/env bash
set -euo pipefail

binary="${1:?Usage: package-nativeserve.sh EXECUTABLE VERSION OUTPUT_DIRECTORY}"
version="${2:?Missing version}"
output="${3:?Missing output directory}"
export LC_ALL=C

if [[ ! "$version" =~ ^[0-9A-Za-z][0-9A-Za-z.+-]*$ ]]; then
  echo 'Use a version made of letters, digits, dots, plus signs and hyphens.' >&2
  exit 2
fi
machine="$(readelf -h "$binary" | awk '/Machine:/ { sub(/^.*Machine:[[:space:]]*/, ""); print }')"
case "$machine" in
  'Advanced Micro Devices X86-64') arch=x86_64 ;;
  AArch64) arch=aarch64 ;;
  *) echo "Unsupported Linux executable architecture: $machine" >&2; exit 1 ;;
esac
interpreter="$(readelf -lW "$binary" | awk '/Requesting program interpreter/ { sub(/]$/, "", $NF); print $NF }')"

mkdir -p "$output"
stage="$(mktemp -d "$output/.nativeserve.XXXXXXXX")"
trap 'rm -rf "$stage"' EXIT
archive="nativeserve-$version-linux-$arch.tar.gz"
install -m 0755 "$binary" "$stage/nativeserve"
cat > "$stage/compatibility.txt" <<EOF
NativeServe $version
Target: Linux $arch
Executable interpreter: ${interpreter:-none}
CI smoke target: Ubuntu 24.04

Run ./nativeserve to start the sample at http://127.0.0.1:8080.
Set PORT or BIND_HOST to change the port or IPv4 address.
The health route is GET /health.
EOF
chmod 0644 "$stage/compatibility.txt"
(
  cd "$stage"
  tar --format=ustar --mtime=@0 --owner=0 --group=0 --numeric-owner \
    -cf - compatibility.txt nativeserve | gzip -n > "$archive"
  sha256sum "$archive" > "$archive.sha256"
)
mv -- "$stage/$archive" "$stage/$archive.sha256" "$output/"
printf '%s\n' "$output/$archive"
