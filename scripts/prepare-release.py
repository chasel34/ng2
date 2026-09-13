#!/usr/bin/env python3
"""Validate the built APK metadata and stage versioned release assets."""

import hashlib
import json
from pathlib import Path
import re
import shutil
import sys


def main():
    tag = sys.argv[1]
    if not re.fullmatch(r"v\d+\.\d+\.\d+", tag):
        raise SystemExit("Release tag must be vX.Y.Z")
    notes = Path("docs/releases") / f"{tag}.md"
    if not notes.is_file() or not notes.read_text().strip():
        raise SystemExit(f"Missing release notes: {notes}")
    apk_dir = Path("app/build/outputs/apk/release")
    metadata = json.loads((apk_dir / "output-metadata.json").read_text())
    if metadata["applicationId"] != "com.chasel.ng2":
        raise SystemExit("Unexpected release application ID")
    elements = metadata["elements"]
    if len(elements) != 1 or elements[0].get("filters"):
        raise SystemExit("Expected one universal APK")
    apk = elements[0]
    if apk["versionName"] != tag[1:] or apk["versionCode"] <= 0:
        raise SystemExit("APK version does not match release tag")
    output = Path("build/release")
    output.mkdir(parents=True, exist_ok=True)
    for stale in output.iterdir():
        if stale.is_file():
            stale.unlink()
    target = output / f"ng2-{tag}.apk"
    shutil.copyfile(apk_dir / apk["outputFile"], target)
    checksum = hashlib.sha256(target.read_bytes()).hexdigest()
    (output / f"{target.name}.sha256").write_text(f"{checksum}  {target.name}\n")
    (output / "update.json").write_text(json.dumps({
        "versionName": apk["versionName"],
        "versionCode": apk["versionCode"],
        "applicationId": metadata["applicationId"],
        "apkName": target.name,
        "sha256": checksum,
        "size": target.stat().st_size,
    }, indent=2) + "\n")
    print(f"Prepared {target.name}, versionCode {apk['versionCode']}")


if __name__ == "__main__":
    main()
