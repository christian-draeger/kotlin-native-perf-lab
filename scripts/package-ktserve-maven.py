#!/usr/bin/env python3
"""Sign a tested ktServe Maven staging repository and build a Central upload ZIP."""

import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile


GROUP_PATH = Path("io/github/christian-draeger")
MODULES = {
    "ktserve": ".jar",
    "ktserve-jvm": ".jar",
    "ktserve-linuxx64": ".klib",
    "ktserve-linuxarm64": ".klib",
    "ktserve-macosarm64": ".klib",
}


def release_files(repository, version):
    for module, extension in MODULES.items():
        directory = repository / GROUP_PATH / module / version
        stem = f"{module}-{version}"
        required = {stem + suffix for suffix in (extension, ".pom", ".module", "-sources.jar", "-javadoc.jar")}
        artifacts = sorted(
            file for file in directory.glob(f"{stem}*")
            if file.is_file() and file.suffix in {".jar", ".klib", ".pom", ".module"}
        )
        missing = required.difference(file.name for file in artifacts)
        if missing:
            raise ValueError(f"Missing staged artifacts in {directory}: {', '.join(sorted(missing))}")
        for artifact in artifacts:
            data = artifact.read_bytes()
            checksums = []
            for algorithm in ("md5", "sha1", "sha256", "sha512"):
                checksum = artifact.with_name(f"{artifact.name}.{algorithm}")
                if algorithm in {"sha256", "sha512"} and not checksum.is_file():
                    continue
                contents = checksum.read_bytes()
                actual = hashlib.new(algorithm, data, usedforsecurity=False).hexdigest()
                if actual.encode() != contents.strip():
                    raise ValueError(f"The staged {algorithm} checksum does not match {artifact}")
                checksums.append((checksum.relative_to(repository), contents))
            yield artifact.relative_to(repository), data, checksums


def build_bundle(repository, version, signing_key, output):
    if output.exists():
        raise FileExistsError(f"Bundle already exists at {output}")
    files = list(release_files(repository, version))
    with tempfile.TemporaryDirectory(prefix="ktserve-central-", dir=output.parent) as work:
        scratch = Path(work)
        candidate = scratch / "central.zip"
        with zipfile.ZipFile(candidate, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
            for name, data, checksums in files:
                file = scratch / name.name
                file.write_bytes(data)
                signature = scratch / f"{name.name}.asc"
                subprocess.run([
                    "gpg", "--local-user", signing_key, "--armor", "--detach-sign",
                    "--output", str(signature), str(file),
                ], check=True)
                subprocess.run([
                    "gpg", "--batch", "--verify", str(signature), str(file),
                ], check=True, capture_output=True)
                bundle.write(file, name)
                bundle.write(signature, f"{name}.asc")
                for checksum, contents in checksums:
                    bundle.writestr(checksum.as_posix(), contents)
        # A retry must not replace an existing release bundle or leave a partial ZIP.
        output.hardlink_to(candidate)
    print(f"Signed {len(files)} artifacts and created {output}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("staged_repository", type=Path)
    parser.add_argument("version")
    parser.add_argument("signing_key", help="Fingerprint of the release key in the local GnuPG keyring")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    version_pattern = r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?"
    if not re.fullmatch(version_pattern, args.version) or args.version.upper().endswith("-SNAPSHOT"):
        parser.error("provide the staged release version, such as 0.2.0-preview.2")
    try:
        build_bundle(args.staged_repository.resolve(), args.version, args.signing_key, args.output.absolute())
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Could not create the Central bundle. {error}\n")


if __name__ == "__main__":
    main()
