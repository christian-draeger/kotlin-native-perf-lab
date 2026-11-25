# Security policy

The latest ktServe preview on Maven Central receives security fixes. Upgrade to the newest patch in that release line. The release notes and advisories identify any affected versions and the first version containing a fix. The supported deployment scope accompanies each release. System libraries, the JDK, and the ingress need their own security updates.

## Report a vulnerability

Use [GitHub private vulnerability reporting](https://github.com/christian-draeger/kotlin-native-perf-lab/security/advisories/new). Include the ktServe version or commit, the affected platform and deployment configuration, the impact, and sanitized observations that help us verify it. Send security reports through the private form. Keep credentials, personal data, and production traffic out of attachments.

The repository maintainers triage reports privately, confirm affected versions, and coordinate the fix and its disclosure. Public advisories document the impact, upgrade instructions, and any temporary mitigation.

## Maintain a release

The Maven workflow inventories resolved dependencies and checks them against OSV. A failed check requires triage before shipping. Maintainers confirm applicability to the distributed artifacts, update affected dependencies, and re-run the checks. The inventory accompanies staging evidence. Maintainers also review advisories from providers of the deployment's system libraries, JDK, and proxy.

Use the [release guide](components/ktserve/RELEASING.md) to rehearse a patch, stage the fixed artifacts, and publish a signed patch release. After verifying an independent consumer upgrade, coordinate the public advisory with the reporter.
