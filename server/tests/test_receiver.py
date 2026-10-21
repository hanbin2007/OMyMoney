import gzip
import asyncio
import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch

from fastapi.testclient import TestClient
from starlette.requests import ClientDisconnect, Request
from receiver.app import create_app
from receiver.store import Settings, Store, StoreError


class ReceiverTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.settings = Settings(Path(self.temporary.name), min_free_bytes=0)
        self.store = Store(self.settings)
        self.client = TestClient(create_app(self.settings, self.store))
        self.code = self.store.create_pair_code("Test phone")
        response = self.client.post("/v1/pair", json={"code": self.code})
        self.assertEqual(200, response.status_code)
        self.identity = response.json()
        self.headers = {"Authorization": "Bearer " + self.identity["token"]}

    def tearDown(self):
        self.client.close()
        self.temporary.cleanup()

    def send(self, data=None, batch_id=None, headers=None):
        data = data if data is not None else gzip.compress(b'{"kind":"capture","amount":"12.30"}\n')
        batch_id = batch_id or str(uuid.uuid4())
        request_headers = {**self.headers, "Content-Type": "application/gzip",
                           "X-Content-SHA256": hashlib.sha256(data).hexdigest()}
        request_headers.update(headers or {})
        return self.client.post("/v1/batches/" + batch_id, content=data, headers=request_headers)

    def test_pairing_is_one_use_and_tokens_are_write_only_and_hashed(self):
        self.assertEqual(401, self.client.post("/v1/pair", json={"code": self.code}).status_code)
        self.assertEqual(401, self.client.post("/v1/pair", json={"code": "bad"}).status_code)
        self.assertEqual(404, self.client.get("/v1/batches", headers=self.headers).status_code)
        self.assertEqual(404, self.client.get("/docs").status_code)
        self.assertEqual(401, self.send(headers={"Authorization": "Bearer invalid"}).status_code)
        with sqlite3.connect(self.settings.database) as connection:
            rows = str(connection.execute("SELECT * FROM devices").fetchall())
        self.assertNotIn(self.identity["token"], rows)

    def test_receipt_means_durable_idempotent_bytes_and_survives_reopen(self):
        batch_id = str(uuid.uuid4())
        data = gzip.compress(b'{"kind":"capture"}\n')
        receipt = self.send(data, batch_id)
        self.assertEqual(200, receipt.status_code)
        self.assertEqual(hashlib.sha256(data).hexdigest(), receipt.json()["sha256"])
        self.assertEqual(batch_id, receipt.json()["batchId"])
        self.assertEqual(data, Store(self.settings).read_batch(self.identity["deviceId"], batch_id))
        self.assertTrue(self.send(data, batch_id).json()["duplicate"])
        self.assertEqual(409, self.send(gzip.compress(b'{"kind":"different"}\n'), batch_id).status_code)

    def test_bad_hash_compression_bombs_invalid_json_and_depth_are_rejected(self):
        self.assertEqual(400, self.send(headers={"X-Content-SHA256": "0" * 64}).status_code)
        self.assertEqual(400, self.send(b"not gzip").status_code)
        self.assertEqual(413, self.send(gzip.compress(b"x" * 300000)).status_code)
        self.assertEqual(400, self.send(gzip.compress(b"not json\n")).status_code)
        self.assertEqual(400, self.send(gzip.compress(("[" * 20 + "0" + "]" * 20 + "\n").encode())).status_code)
        self.assertEqual(400, self.send(gzip.compress(b'{"kind":"x"}\n' * 129)).status_code)
        self.assertEqual(413, self.send(b"x" * 300000).status_code)
        self.assertEqual(400, self.send(gzip.compress(b'{"kind":"x"}')).status_code)
        self.assertEqual(400, self.send(gzip.compress(b'{"kind":"x","value":NaN}\n')).status_code)

    def test_tokens_can_be_revoked_without_deleting_another_device(self):
        self.store.revoke(self.identity["deviceId"])
        self.assertEqual(401, self.send().status_code)
        other = self.store.pair(self.store.create_pair_code("Other phone"))
        self.assertEqual(200, self.send(headers={"Authorization": "Bearer " + other["token"]}).status_code)

    def test_low_disk_rejects_new_data_without_an_acknowledgement(self):
        settings = Settings(Path(self.temporary.name), min_free_bytes=10**18)
        with TestClient(create_app(settings, Store(settings))) as client:
            response = client.post("/v1/batches/" + str(uuid.uuid4()), content=gzip.compress(b'{"kind":"test"}\n'), headers=self.headers)
        self.assertEqual(503, response.status_code)

    def test_retention_bounds_storage_and_preserves_duplicate_receipts(self):
        small = Settings(Path(self.temporary.name), min_free_bytes=0, max_log_bytes=100)
        store = Store(small)
        first = str(uuid.uuid4())
        payload = gzip.compress(b'{"kind":"a","random":"123456789012345678901234567890"}\n')
        store.accept(self.identity["deviceId"], first, payload, hashlib.sha256(payload).hexdigest(), 1)
        store.accept(self.identity["deviceId"], str(uuid.uuid4()), payload, hashlib.sha256(payload).hexdigest(), 1)
        self.assertLessEqual(store.status()["logBytes"], 100)
        self.assertIsNone(store.read_batch(self.identity["deviceId"], first))
        self.assertTrue(store.accept(self.identity["deviceId"], first, payload, hashlib.sha256(payload).hexdigest(), 1)["duplicate"])

    def test_public_updates_support_only_bounded_single_ranges_and_not_logs(self):
        self.settings.releases.mkdir(exist_ok=True)
        (self.settings.releases / "app-2.apk").write_bytes(b"test-apk-content")
        (self.settings.releases / "stable.json").write_text('{"signed":"manifest"}')
        self.assertEqual(200, self.client.get("/updates/stable.json").status_code)
        response = self.client.get("/updates/app-2.apk", headers={"Range": "bytes=5-"})
        self.assertEqual(206, response.status_code)
        self.assertEqual(b"apk-content", response.content)
        self.assertEqual("bytes 5-15/16", response.headers["content-range"])
        self.assertEqual(416, self.client.get("/updates/app-2.apk", headers={"Range": "bytes=100-"}).status_code)
        self.assertEqual(416, self.client.get("/updates/app-2.apk", headers={"Range": "bytes=0-1,5-"}).status_code)
        self.assertEqual(404, self.client.get("/updates/receiver.db").status_code)
        self.assertEqual(404, self.client.get("/updates/missing.apk").status_code)

    def test_expired_pair_code_and_bounded_pair_inventory(self):
        code = self.store.create_pair_code("Expired", expires_in=-1)
        with self.assertRaises(StoreError):
            self.store.pair(code)
        for _ in range(20):
            self.store.create_pair_code("unused")
        with self.assertRaises(StoreError):
            self.store.create_pair_code("too many")

    def test_default_pair_code_lasts_24_hours_but_remains_single_use(self):
        issued = 1800000000
        with patch("receiver.store.time.time", return_value=issued):
            usable = self.store.create_pair_code("24-hour phone")
            expired = self.store.create_pair_code("24-hour expiry")
            self.store.create_pair_code("Explicit short expiry", expires_in=600)
        with self.store.connect() as db:
            rows = dict(db.execute("SELECT label,expires FROM pair_codes").fetchall())
        self.assertEqual(issued + 86400, rows["24-hour phone"])
        self.assertEqual(issued + 600, rows["Explicit short expiry"])
        with patch("receiver.store.time.time", return_value=issued + 86399):
            self.assertIn("deviceId", self.store.pair(usable))
            with self.assertRaises(StoreError) as reused:
                self.store.pair(usable)
            self.assertEqual(401, reused.exception.status)
        with patch("receiver.store.time.time", return_value=issued + 86400):
            with self.assertRaises(StoreError) as timed_out:
                self.store.pair(expired)
            self.assertEqual(401, timed_out.exception.status)

    def test_failed_commit_cannot_produce_a_success_receipt(self):
        with sqlite3.connect(self.settings.database) as db:
            db.execute("CREATE TRIGGER fail_receipt BEFORE INSERT ON receipts BEGIN SELECT RAISE(ABORT, 'disk fault'); END")
        batch_id = str(uuid.uuid4())
        self.assertEqual(503, self.send(batch_id=batch_id).status_code)
        self.assertIsNone(self.store.read_batch(self.identity["deviceId"], batch_id))

    def test_racing_pair_requests_cannot_consume_one_code_twice(self):
        code = self.store.create_pair_code("race")

        def consume():
            try:
                return self.store.pair(code)["deviceId"]
            except StoreError as error:
                return error.status

        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: consume(), range(2)))
        self.assertEqual(1, sum(isinstance(item, str) for item in results))
        self.assertEqual(1, results.count(401))

    def test_pair_attempt_limit_is_enforced(self):
        statuses = [self.client.post("/v1/pair", json={"code": "invalid"}).status_code for _ in range(6)]
        self.assertIn(401, statuses)
        self.assertEqual(429, statuses[-1])

    def test_database_page_cap_applies_to_each_new_writer_connection(self):
        with self.store.connect() as db:
            self.assertLessEqual(db.execute("PRAGMA max_page_count").fetchone()[0], 327680)

    def test_client_disconnect_before_headers_does_not_consume_download_slots(self):
        (self.settings.releases / "app-2.apk").write_bytes(b"test-apk-content")
        app = create_app(self.settings, self.store)
        endpoint = next(route.endpoint for route in app.routes if route.path == "/updates/{name}")

        async def exercise():
            scope = {"type": "http", "method": "GET", "path": "/updates/app-2.apk", "headers": [],
                     "asgi": {"spec_version": "2.4"}}

            async def disconnected(_):
                raise OSError("peer closed before response headers")

            async def receive():
                return {"type": "http.disconnect"}

            for _ in range(3):
                try:
                    response = await endpoint("app-2.apk", Request(scope))
                except StoreError as error:
                    self.fail(f"Disconnected client leaked a download slot: {error.code}")
                try:
                    await response(scope, receive, disconnected)
                except (OSError, ClientDisconnect):
                    pass

        asyncio.run(exercise())


if __name__ == "__main__":
    unittest.main()
