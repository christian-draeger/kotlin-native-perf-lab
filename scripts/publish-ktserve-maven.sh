#!/usr/bin/env bash
set -euo pipefail

version="${1:?Usage: publish-ktserve-maven.sh VERSION BUNDLE}"
bundle="${2:?Provide the signed bundle ZIP}"
: "${MAVEN_CENTRAL_USERNAME:?Configure the Central Portal token username}"
: "${MAVEN_CENTRAL_PASSWORD:?Configure the Central Portal token password}"
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z]+([.-][0-9A-Za-z]+)*)?$ ]] || [[ ! -f "$bundle" ]]; then
  echo 'Provide a release version and an existing signed bundle.' >&2
  exit 1
fi

published="https://repo.maven.apache.org/maven2/codes/draeger/ktserve/$version/ktserve-$version.pom"
status="$(curl --silent --show-error --head --connect-timeout 15 --max-time 30 --retry 2 \
  --output /dev/null --write-out '%{http_code}' "$published")"
if [[ "$status" != 404 ]]; then
  echo "Central returned HTTP $status for codes.draeger:ktserve:$version. A new upload requires a free version." >&2
  exit 1
fi

portal='https://central.sonatype.com/api/v1/publisher'
bearer="$(printf '%s:%s' "$MAVEN_CENTRAL_USERNAME" "$MAVEN_CENTRAL_PASSWORD" | base64 | tr -d '\n')"
unset MAVEN_CENTRAL_USERNAME MAVEN_CENTRAL_PASSWORD
portal_request() {
  printf 'header = "Authorization: Bearer %s"\n' "$bearer" |
    curl --config - --silent --show-error --fail --connect-timeout 15 --max-time 90 "$@"
}

deployment="$(portal_request --request POST --form "bundle=@$bundle;type=application/zip" \
  "$portal/upload?publishingType=AUTOMATIC&name=codes.draeger%3Aktserve%3A$version")"
if [[ ! "$deployment" =~ ^[[:xdigit:]]{8}-[[:xdigit:]]{4}-[[:xdigit:]]{4}-[[:xdigit:]]{4}-[[:xdigit:]]{12}$ ]]; then
  echo 'Central did not return a deployment ID. Check the Portal before retrying an upload.' >&2
  exit 1
fi
echo "Central deployment $deployment submitted."
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  printf 'Maven Central deployment `%s` for `codes.draeger:ktserve:%s`\n' "$deployment" "$version" >> "$GITHUB_STEP_SUMMARY"
fi

for ((attempt = 1; attempt <= 60; attempt++)); do
  response="$(portal_request --retry 2 --request POST "$portal/status?id=$deployment")"
  state="$(jq -er '.deploymentState' <<< "$response")"
  case "$state" in
    PUBLISHED) echo "Central published codes.draeger:ktserve:$version."; exit 0 ;;
    FAILED) jq -c '.errors // {}' <<< "$response" >&2; exit 1 ;;
    PENDING|VALIDATING|VALIDATED|PUBLISHING) sleep 10 ;;
    *) echo "Unexpected Central deployment status: $state" >&2; exit 1 ;;
  esac
done
echo "Check deployment $deployment in Central Portal before retrying. It is still $state." >&2
exit 1
