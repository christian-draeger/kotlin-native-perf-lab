#!/usr/bin/env bash
set -euo pipefail

version="${1:?Provide the version from the release tag}"
workflow='.github/workflows/ktserve-maven.yml'
repo="repos/$GITHUB_REPOSITORY"
artifact_name="ktserve-maven-$version"
wait_until=$((SECONDS + 15 * 60))

while true; do
  runs="$(gh api --method GET "$repo/actions/workflows/ktserve-maven.yml/runs" \
    -f head_sha="$GITHUB_SHA" -F per_page=100 | jq -c \
    --arg sha "$GITHUB_SHA" --arg repo "$GITHUB_REPOSITORY" --arg workflow "$workflow" '
      [.workflow_runs[] | select(
        .head_sha == $sha and .head_branch == "main" and .path == $workflow and
        .repository.full_name == $repo and .head_repository.full_name == $repo and
        (.event == "push" or .event == "workflow_dispatch"))]')"

  while read -r run_id; do
    artifact="$(gh api --method GET "$repo/actions/runs/$run_id/artifacts" -f name="$artifact_name" |
      jq -cr --arg name "$artifact_name" --arg sha "$GITHUB_SHA" --argjson run "$run_id" \
        --argjson repo "$GITHUB_REPOSITORY_ID" '
        .artifacts[] | select(
          .name == $name and .expired == false and .workflow_run.id == $run and
          .workflow_run.repository_id == $repo and .workflow_run.head_repository_id == $repo and
          .workflow_run.head_sha == $sha and ((.digest // "") | test("^sha256:[a-f0-9]{64}$")))')"
    [[ -n "$artifact" ]] || continue

    if gh api --method GET "$repo/actions/runs/$run_id/jobs" -f filter=latest | jq -e '
      [.jobs[] | select(.conclusion == "success") | .name] as $successful |
      ["Stage Maven artifacts", "Test Linux consumer (x86_64)", "Test Linux consumer (aarch64)"] |
      all(. as $job | $successful | index($job) != null)' > /dev/null; then
      artifact_id="$(jq -er '.id' <<< "$artifact")"
      digest="$(jq -er '.digest | sub("^sha256:"; "")' <<< "$artifact")"
      printf 'run_id=%s\nartifact_id=%s\nartifact_digest=%s\n' "$run_id" "$artifact_id" "$digest" >> "$GITHUB_OUTPUT"
      echo "Reusing the Maven archive from staging run $run_id for $GITHUB_SHA."
      exit 0
    fi
  done < <(jq -r '.[] | select(.conclusion == "success") | .id' <<< "$runs")

  if [[ "$version" != "$(cat components/ktserve/VERSION)" ]] || (( SECONDS >= wait_until )) ||
    ! jq -e 'any(.[]; .event == "push" and .status != "completed")' <<< "$runs" > /dev/null; then
    echo "No completed staging archive for $version at $GITHUB_SHA. Building it in this release run."
    exit 0
  fi
  echo "Waiting for staging of $version on main."
  sleep 30
done
