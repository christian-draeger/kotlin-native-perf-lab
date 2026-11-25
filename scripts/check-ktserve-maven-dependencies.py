#!/usr/bin/env python3
"""Check the resolved ktServe Maven package inventory against OSV."""

import json
from pathlib import Path
import sys
from urllib.request import Request, urlopen


def main(inventory_path, report_path):
    scopes = json.loads(inventory_path.read_text())
    packages = sorted({(item["name"], item["version"]) for scope in scopes.values() for item in scope})
    if not packages or any(not scope for scope in scopes.values()):
        raise SystemExit("Dependency inventory must contain resolved packages for every scope")

    queries = [{"package": {"ecosystem": "Maven", "name": name}, "version": version}
               for name, version in packages]
    findings = []
    for offset in range(0, len(queries), 100):
        batch = queries[offset:offset + 100]
        request = Request(
            "https://api.osv.dev/v1/querybatch",
            json.dumps({"queries": batch}).encode(),
            {"Content-Type": "application/json"},
        )
        with urlopen(request, timeout=30) as response:
            results = json.load(response)["results"]
        if len(results) != len(batch) or any(row.get("next_page_token") for row in results):
            raise SystemExit("OSV returned an incomplete advisory result")
        for query, result in zip(batch, results):
            name = query["package"]["name"]
            version = query["version"]
            for advisory in result.get("vulns", []):
                findings.append({"name": name, "version": version, "advisory": advisory["id"]})

    report_path.write_text(json.dumps({"checked": len(queries), "findings": findings}, indent=2) + "\n")
    if findings:
        for item in findings:
            print(f"{item['name']}@{item['version']}: https://osv.dev/vulnerability/{item['advisory']}")
        raise SystemExit("Resolve or triage the listed advisories before promotion")
    print(f"OSV checked {len(queries)} resolved Maven coordinates")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
