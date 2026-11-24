#!/usr/bin/env bash
set -euo pipefail

operation="${1:?Usage: publish-ktserve-maven.sh submit|verify|public VERSION [BUNDLE|DEPLOYMENT_ID]}"
version="${2:?Provide the release version}"
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z]+([.-][0-9A-Za-z]+)*)?$ ]] || grep -iq -- '-snapshot$' <<< "$version"; then
  echo 'Provide a release version.' >&2
  exit 1
fi

portal='https://central.sonatype.com/api/v1/publisher'
repository='https://repo.maven.apache.org/maven2/codes/draeger'

if [[ "$operation" == submit || "$operation" == verify ]]; then
  : "${MAVEN_CENTRAL_USERNAME:?Configure the Central Portal token username}"
  : "${MAVEN_CENTRAL_PASSWORD:?Configure the Central Portal token password}"
  bearer="$(printf '%s:%s' "$MAVEN_CENTRAL_USERNAME" "$MAVEN_CENTRAL_PASSWORD" | base64 | tr -d '\n')"
  unset MAVEN_CENTRAL_USERNAME MAVEN_CENTRAL_PASSWORD
fi
portal_request() {
  printf 'header = "Authorization: Bearer %s"\n' "$bearer" |
    curl --config - --silent --show-error --fail --connect-timeout 15 --max-time 90 "$@"
}

central_status() {
  curl --silent --show-error --head --connect-timeout 10 --max-time 15 \
    --output /dev/null --write-out '%{http_code}' "$1"
}

deployment_id_pattern='^[[:xdigit:]]{8}-[[:xdigit:]]{4}-[[:xdigit:]]{4}-[[:xdigit:]]{4}-[[:xdigit:]]{12}$'

case "$operation" in
  submit)
    bundle="${3:?Provide the signed bundle ZIP}"
    test -f "$bundle"
    status="$(central_status "$repository/ktserve/$version/ktserve-$version.pom")"
    if [[ "$status" != 404 ]]; then
      echo "Central returned HTTP $status for codes.draeger:ktserve:$version. A new upload requires a free version." >&2
      exit 1
    fi
    # An upload may have been accepted even if the response is lost. Do not retry this request automatically.
    if ! deployment="$(portal_request --request POST --form "bundle=@$bundle;type=application/octet-stream" \
      "$portal/upload?publishingType=AUTOMATIC&name=codes.draeger%3Aktserve%3A$version")" ||
      [[ ! "$deployment" =~ $deployment_id_pattern ]]; then
      echo 'Central did not return a deployment ID. Check the Portal before retrying an upload.' >&2
      exit 1
    fi
    echo "Central deployment $deployment submitted."
    if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
      printf 'deployment=%s\n' "$deployment" >> "$GITHUB_OUTPUT"
    fi
    if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
      printf 'Maven Central deployment `%s` for `codes.draeger:ktserve:%s`.\n' "$deployment" "$version" >> "$GITHUB_STEP_SUMMARY"
    fi
    exit 0
    ;;
  verify) deployment="${3:?Provide the deployment ID returned by Central}" ;;
  public) ;;
  *) echo 'Use submit, verify or public.' >&2; exit 1 ;;
esac

if [[ "$operation" == verify ]]; then
  if [[ ! "$deployment" =~ $deployment_id_pattern ]]; then
    echo 'Provide the deployment ID returned by Central.' >&2
    exit 1
  fi
  deadline=$((SECONDS + 45 * 60))
  previous_state=''
  while true; do
    response="$(portal_request --retry 2 --retry-max-time 60 --request POST "$portal/status?id=$deployment")"
    state="$(jq -er '.deploymentState' <<< "$response")"
    if [[ "$state" != "$previous_state" ]]; then
      echo "Central deployment $deployment: $state"
      previous_state="$state"
    fi
    case "$state" in
      PUBLISHED) exit 0 ;;
      FAILED) jq -c '.errors // {}' <<< "$response" >&2; exit 1 ;;
      PENDING|VALIDATING|VALIDATED|PUBLISHING) ;;
      *) echo "Unexpected Central deployment status: $state" >&2; exit 1 ;;
    esac
    if (( SECONDS >= deadline )); then
      echo "Central deployment $deployment is still $state. Rerun the failed verification job to resume checking it." >&2
      exit 1
    fi
    sleep 20
  done
fi

# PUBLISHED may precede propagation to the public CDN. Check metadata, binaries and signatures.
deadline=$((SECONDS + 5 * 60))
for artifact in ktserve ktserve-jvm ktserve-linuxx64 ktserve-linuxarm64 ktserve-macosarm64; do
  extension=klib
  if [[ "$artifact" == ktserve || "$artifact" == ktserve-jvm ]]; then extension=jar; fi
  for suffix in pom module "$extension" "$extension.asc"; do
    url="$repository/$artifact/$version/$artifact-$version.$suffix"
    until [[ "$(central_status "$url")" == 200 ]]; do
      if (( SECONDS >= deadline )); then
        echo "Central has not made $url available yet. Rerun the failed verification job to check again." >&2
        exit 1
      fi
      sleep 10
    done
  done
done
echo "Central published codes.draeger:ktserve:$version and all platform variants."
