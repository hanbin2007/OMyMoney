#!/usr/bin/env python3
"""Sign a release manifest locally; never copy either signing private key to the server."""
import argparse
import base64
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
from receiver.publish import MAX_APK, ORIGIN, PACKAGE, SIGNER, file_digest, verify_manifest


def output(*command):
    return subprocess.run(command, capture_output=True, text=True, check=True, timeout=90).stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--private-key", type=Path, required=True)
    parser.add_argument("--public-key", type=Path, default=Path("app/src/main/assets/update-manifest-public.pem"))
    parser.add_argument("--notes", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--days", type=int, default=89)
    args = parser.parse_args()
    sdk = Path(os.environ["ANDROID_HOME"])
    analyzer = str(sdk / "cmdline-tools/latest/bin/apkanalyzer")
    apksigner = str(sdk / "build-tools" / os.environ.get("ANDROID_BUILD_TOOLS_VERSION", "36.0.0") / "apksigner")
    if not 1 <= args.days <= 90 or not 1 <= args.apk.stat().st_size <= MAX_APK:
        parser.error("Invalid expiry or APK size")
    certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})",
                              output(apksigner, "verify", "--print-certs", str(args.apk)))
    if certificates != [SIGNER] or output(analyzer, "manifest", "application-id", str(args.apk)) != PACKAGE:
        parser.error("APK identity differs from the existing release")
    version = int(output(analyzer, "manifest", "version-code", str(args.apk)))
    sha = file_digest(args.apk)
    now = datetime.now(timezone.utc).replace(microsecond=0)
    payload = dict(schema=1, versionCode=version,
                   versionName=output(analyzer, "manifest", "version-name", str(args.apk)),
                   packageName=PACKAGE, signerSha256=SIGNER,
                   apkUrl=f"{ORIGIN}/updates/omymoney-{version}-{sha[:16]}.apk",
                   apkSha256=sha, apkBytes=args.apk.stat().st_size,
                   issuedAt=now.isoformat().replace("+00:00", "Z"),
                   expiresAt=(now + timedelta(days=args.days)).isoformat().replace("+00:00", "Z"),
                   notes=args.notes.read_text().strip())
    raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
    signed = subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(args.private_key)],
                            input=raw, capture_output=True, check=True, timeout=5).stdout
    envelope = json.dumps(dict(payload=base64.b64encode(raw).decode(),
                               signature=base64.b64encode(signed).decode()), separators=(",", ":")).encode()
    verify_manifest(envelope, args.public_key, ORIGIN, SIGNER)
    args.output.write_bytes(envelope + b"\n")
    print(f"Verified signed manifest for {version}: {sha}")


if __name__ == "__main__":
    main()
