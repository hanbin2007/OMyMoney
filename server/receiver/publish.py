"""SSH-only publication. Only the manifest public key is needed on the server."""
import argparse
import base64
from datetime import datetime, timedelta, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from urllib.parse import urlsplit

MAX_APK = 100 * 1024**2
PACKAGE = "app.omymoney.android"
SIGNER = "91275b1e64da9ff62a5da9444bcbb6e4ef42f51fb5fd9dd83e14820a10129a37"
ORIGIN = "https://omymoney.origenclub.cn:58952"


def verify_manifest(raw, public_key, origin, signer, allow_expired=False):
    if len(raw) > 32768:
        raise ValueError("manifest_size")
    envelope = json.loads(raw)
    if set(envelope) != {"payload", "signature"}:
        raise ValueError("envelope")
    payload = base64.b64decode(envelope["payload"], validate=True)
    signature = base64.b64decode(envelope["signature"], validate=True)
    if len(payload) > 16384 or len(signature) > 1024:
        raise ValueError("payload_size")
    with tempfile.TemporaryDirectory() as directory:
        signature_file = Path(directory) / "signature"
        signature_file.write_bytes(signature)
        result = subprocess.run(["openssl", "dgst", "-sha256", "-verify", str(public_key),
                                 "-signature", str(signature_file)], input=payload,
                                capture_output=True, timeout=5)
    if result.returncode:
        raise ValueError("manifest_signature")
    release = json.loads(payload)
    fields = {"schema", "versionCode", "versionName", "packageName", "signerSha256", "apkUrl",
              "apkSha256", "apkBytes", "issuedAt", "expiresAt", "notes"}
    if set(release) != fields or release["schema"] != 1:
        raise ValueError("release_fields")
    if (type(release["versionCode"]) is not int or not 1 <= release["versionCode"] <= 2100000000 or
            type(release["apkBytes"]) is not int or not 1 <= release["apkBytes"] <= MAX_APK):
        raise ValueError("release_numbers")
    if release["packageName"] != PACKAGE or release["signerSha256"] != signer:
        raise ValueError("release_identity")
    if not 1 <= len(release["versionName"]) <= 64 or len(release["notes"]) > 4096:
        raise ValueError("release_text")
    if not re.fullmatch("[0-9a-f]{64}", release["apkSha256"]):
        raise ValueError("apk_digest")
    url, expected = urlsplit(release["apkUrl"]), urlsplit(origin)
    name = f"omymoney-{release['versionCode']}-{release['apkSha256'][:16]}.apk"
    if (url.scheme != "https" or expected.scheme != "https" or url.netloc != expected.netloc or
            url.username or url.password or url.query or url.fragment or url.path != f"/updates/{name}"):
        raise ValueError("apk_origin")
    issued = datetime.fromisoformat(release["issuedAt"])
    expires = datetime.fromisoformat(release["expiresAt"])
    now = datetime.now(timezone.utc)
    if (issued.tzinfo is None or expires.tzinfo is None or issued > now + timedelta(minutes=5) or
            expires <= issued or expires > issued + timedelta(days=90) or (not allow_expired and expires <= now)):
        raise ValueError("release_time")
    return release


def file_digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def sync_directory(directory):
    descriptor = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def atomic_copy(source, destination):
    descriptor, temporary = tempfile.mkstemp(prefix=".publish-", dir=destination.parent)
    try:
        with os.fdopen(descriptor, "wb") as output, source.open("rb") as input_file:
            shutil.copyfileobj(input_file, output, 65536)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, 0o644)
        os.replace(temporary, destination)
        sync_directory(destination.parent)
    finally:
        Path(temporary).unlink(missing_ok=True)


def publish(directory, manifest, apk, public_key, origin=ORIGIN, signer=SIGNER, min_free_bytes=2 * 1024**3):
    if manifest.stat().st_size > 32768 or apk.stat().st_size > MAX_APK:
        raise ValueError("input_size")
    raw = manifest.read_bytes()
    release = verify_manifest(raw, public_key, origin, signer)
    if apk.stat().st_size != release["apkBytes"] or file_digest(apk) != release["apkSha256"]:
        raise ValueError("apk_integrity")
    directory.mkdir(parents=True, exist_ok=True, mode=0o755)
    with (directory / ".publish.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        stable = directory / "stable.json"
        current_name = None
        if stable.exists():
            previous = verify_manifest(stable.read_bytes(), public_key, origin, signer, allow_expired=True)
            current_name = urlsplit(previous["apkUrl"]).path.rsplit("/", 1)[1]
            if release["versionCode"] < previous["versionCode"]:
                raise ValueError("version_rollback")
            if release["versionCode"] == previous["versionCode"] and any(
                    release[key] != previous[key] for key in ("apkSha256", "apkBytes", "apkUrl", "versionName")):
                raise ValueError("immutable_version")
        if shutil.disk_usage(directory).free < min_free_bytes + release["apkBytes"] + 32768:
            raise OSError("disk_floor")
        # Interrupted copies have no public route. Clean only this publisher's temporary files.
        for stale in directory.glob(".publish-*"):
            stale.unlink()
        # Bound orphaned APKs even after repeated crashes before manifest replacement.
        old_files = sorted((path for path in directory.glob("omymoney-*.apk") if path.name != current_name),
                           key=lambda path: path.stat().st_mtime, reverse=True)
        for old in old_files[3 if current_name else 4:]:
            old.unlink()
        destination = directory / urlsplit(release["apkUrl"]).path.rsplit("/", 1)[1]
        if destination.exists():
            if destination.is_symlink() or file_digest(destination) != release["apkSha256"]:
                raise ValueError("immutable_apk")
        else:
            atomic_copy(apk, destination)
            if file_digest(destination) != release["apkSha256"]:
                destination.unlink()
                raise ValueError("copy_integrity")
        # Write the already-verified bytes, not a mutable source file reopened after validation.
        with tempfile.TemporaryDirectory(dir=directory) as temporary:
            verified = Path(temporary) / "verified.json"
            verified.write_bytes(raw)
            atomic_copy(verified, stable)
        others = sorted((path for path in directory.glob("omymoney-*.apk") if path != destination),
                        key=lambda path: path.stat().st_mtime, reverse=True)
        for old in others[3:]:
            old.unlink()
        sync_directory(directory)
    return release


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--releases", type=Path, default=Path("/data/omymoney/state/releases"))
    parser.add_argument("--public-key", type=Path, default=Path("/data/omymoney/update-manifest-public.pem"))
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--apk", type=Path, required=True)
    args = parser.parse_args()
    release = publish(args.releases, args.manifest, args.apk, args.public_key)
    print(json.dumps({"versionCode": release["versionCode"], "apkSha256": release["apkSha256"]}))


if __name__ == "__main__":
    main()
