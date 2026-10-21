import base64
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from receiver.publish import publish, verify_manifest


class PublicationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.keys = tempfile.TemporaryDirectory()
        cls.private = Path(cls.keys.name) / "key.pem"
        cls.public = Path(cls.keys.name) / "public.pem"
        subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048",
                        "-out", str(cls.private)], check=True, capture_output=True)
        subprocess.run(["openssl", "pkey", "-in", str(cls.private), "-pubout", "-out", str(cls.public)],
                       check=True, capture_output=True)

    @classmethod
    def tearDownClass(cls):
        cls.keys.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.releases = self.root / "releases"

    def fixture(self, version, content=None, **changes):
        content = content or f"APK-{version}".encode()
        apk = self.root / "input.apk"
        apk.write_bytes(content)
        sha = hashlib.sha256(content).hexdigest()
        now = datetime.now(timezone.utc)
        payload = dict(schema=1, versionCode=version, versionName=f"0.{version}",
                       packageName="app.omymoney.android", signerSha256="a" * 64,
                       apkUrl=f"https://example.test:58952/updates/omymoney-{version}-{sha[:16]}.apk",
                       apkSha256=sha, apkBytes=len(content), issuedAt=now.isoformat(),
                       expiresAt=(now + timedelta(days=30)).isoformat(), notes="Release")
        payload.update(changes)
        raw = json.dumps(payload).encode()
        signature = subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(self.private)],
                                   input=raw, capture_output=True, check=True).stdout
        manifest = self.root / "input.json"
        manifest.write_text(json.dumps(dict(payload=base64.b64encode(raw).decode(),
                                            signature=base64.b64encode(signature).decode())))
        return manifest, apk

    def publish(self, version, **changes):
        manifest, apk = self.fixture(version, **changes)
        return publish(self.releases, manifest, apk, self.public, "https://example.test:58952",
                       "a" * 64, min_free_bytes=0)

    def test_atomic_publication_monotonic_versions_and_four_release_retention(self):
        for version in range(1, 7):
            self.publish(version)
        self.assertEqual(4, len(list(self.releases.glob("*.apk"))))
        self.assertEqual(6, verify_manifest((self.releases / "stable.json").read_bytes(), self.public,
                                          "https://example.test:58952", "a" * 64)["versionCode"])
        before = (self.releases / "stable.json").read_bytes()
        with self.assertRaises(ValueError):
            self.publish(5)
        self.assertEqual(before, (self.releases / "stable.json").read_bytes())

    def test_tamper_wrong_hash_origin_identity_expiry_and_disk_floor_are_rejected(self):
        manifest, apk = self.fixture(1)
        envelope = json.loads(manifest.read_bytes())
        envelope["signature"] = base64.b64encode(b"bad").decode()
        with self.assertRaises(ValueError):
            verify_manifest(json.dumps(envelope).encode(), self.public, "https://example.test:58952", "a" * 64)
        for changes in ({"apkSha256": "b" * 64}, {"apkUrl": "https://evil.test/updates/other.apk"},
                        {"packageName": "evil.app"}, {"signerSha256": "b" * 64},
                        {"expiresAt": "2000-01-01T00:00:00Z"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.publish(1, **changes)
        manifest, apk = self.fixture(1)
        with self.assertRaises(OSError):
            publish(self.releases, manifest, apk, self.public, "https://example.test:58952", "a" * 64,
                    min_free_bytes=2**62)
        self.assertFalse((self.releases / "stable.json").exists())

    def test_same_version_can_refresh_signature_but_cannot_replace_binary(self):
        self.publish(1)
        self.publish(1, notes="Refreshed expiry")
        with self.assertRaises(ValueError):
            self.publish(1, content=b"different")
        self.assertEqual(1, len(list(self.releases.glob("*.apk"))))

    def test_existing_immutable_file_is_verified_not_silently_overwritten(self):
        self.publish(1)
        published = next(self.releases.glob("*.apk"))
        published.write_bytes(b"corrupt")
        with self.assertRaises(ValueError):
            self.publish(1)
        self.assertEqual(b"corrupt", published.read_bytes())


if __name__ == "__main__":
    unittest.main()
