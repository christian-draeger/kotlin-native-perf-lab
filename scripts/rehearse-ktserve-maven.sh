#!/usr/bin/env bash
set -euo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
rehearsal="${1:?Usage: rehearse-ktserve-maven.sh WORK_DIRECTORY VERSION}"
version="${2:?Expected staging version}"
keys="$(mktemp -d)"
export GNUPGHOME="$keys"
trap 'gpgconf --kill gpg-agent; rm -rf -- "$keys"' EXIT

gpg --batch --pinentry-mode loopback --passphrase '' \
  --quick-generate-key 'ktServe Release Rehearsal <release-rehearsal@example.invalid>' rsa3072 sign 1d
fingerprint="$(gpg --batch --with-colons --list-secret-keys | awk -F: '$1 == "fpr" { print $10; exit }')"
test -n "$fingerprint"
python3 "$root/scripts/package-ktserve-maven.py" \
  "$rehearsal/staging" "$version" "$fingerprint!" "$rehearsal/bundle.zip"
unzip -q "$rehearsal/bundle.zip" -d "$rehearsal/verified"
find "$rehearsal/verified" -type f -name '*.asc' -print0 |
  while IFS= read -r -d '' signature; do
    gpg --batch --verify "$signature" "${signature%.asc}"
  done
