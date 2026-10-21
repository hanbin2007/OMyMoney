from contextlib import contextmanager
from dataclasses import dataclass
import hashlib
from pathlib import Path
import secrets
import shutil
import sqlite3
import time
import uuid


@dataclass(frozen=True)
class Settings:
    directory: Path
    min_free_bytes: int = 2 * 1024**3
    max_log_bytes: int = 1024**3
    retention_seconds: int = 7 * 86400

    @property
    def database(self):
        return self.directory / "receiver.db"

    @property
    def releases(self):
        return self.directory / "releases"


class StoreError(Exception):
    def __init__(self, status: int, code: str):
        self.status = status
        self.code = code
        super().__init__(code)


def digest(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


class Store:
    def __init__(self, settings: Settings):
        self.settings = settings
        settings.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        settings.releases.mkdir(mode=0o700, exist_ok=True)
        with self.connect() as db:
            db.execute("PRAGMA auto_vacuum=INCREMENTAL")
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript("""
                CREATE TABLE IF NOT EXISTS devices (
                    id TEXT PRIMARY KEY, token_hash TEXT UNIQUE NOT NULL,
                    label TEXT NOT NULL, created REAL NOT NULL, revoked INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS pair_codes (
                    code_hash TEXT PRIMARY KEY, label TEXT NOT NULL, expires REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS receipts (
                    device TEXT NOT NULL, id TEXT NOT NULL, sha256 TEXT NOT NULL,
                    records INTEGER NOT NULL, received REAL NOT NULL,
                    PRIMARY KEY(device, id));
                CREATE INDEX IF NOT EXISTS receipt_age ON receipts(received);
                CREATE TABLE IF NOT EXISTS batches (
                    device TEXT NOT NULL, id TEXT NOT NULL, payload BLOB NOT NULL,
                    bytes INTEGER NOT NULL, received REAL NOT NULL, PRIMARY KEY(device, id));
                CREATE INDEX IF NOT EXISTS batch_age ON batches(received);
                CREATE TABLE IF NOT EXISTS counters (id INTEGER PRIMARY KEY, bytes INTEGER NOT NULL,
                    evicted INTEGER NOT NULL DEFAULT 0);
                INSERT OR IGNORE INTO counters(id, bytes) VALUES(1, 0);
            """)

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.settings.database, timeout=2)
        try:
            # max_page_count belongs to the connection, not the database file.
            page_size = db.execute("PRAGMA page_size").fetchone()[0]
            cap = (1280 * 1024**2) // page_size
            if db.execute(f"PRAGMA max_page_count={cap}").fetchone()[0] > cap:
                raise StoreError(503, "database_cap")
            db.execute("PRAGMA synchronous=FULL")
            db.execute("PRAGMA wal_autocheckpoint=64")
            db.execute("PRAGMA journal_size_limit=1048576")
            db.execute("PRAGMA secure_delete=ON")
            with db:
                yield db
        finally:
            db.close()

    def require_disk(self):
        if shutil.disk_usage(self.settings.directory).free < self.settings.min_free_bytes:
            raise StoreError(503, "disk_floor")

    def create_pair_code(self, label: str, expires_in: int = 24 * 60 * 60):
        self.require_disk()
        code = secrets.token_hex(8)
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            db.execute("DELETE FROM pair_codes WHERE expires < ?", (time.time(),))
            if db.execute("SELECT count(*) FROM pair_codes").fetchone()[0] >= 20:
                raise StoreError(429, "pair_inventory")
            db.execute("INSERT INTO pair_codes VALUES(?,?,?)", (digest(code), label[:80], time.time() + expires_in))
        return code

    def pair(self, code: str):
        self.require_disk()
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT label FROM pair_codes WHERE code_hash=? AND expires>?", (digest(code), time.time())).fetchone()
            if row is None:
                raise StoreError(401, "pair_invalid")
            if db.execute("SELECT count(*) FROM devices").fetchone()[0] >= 128:
                raise StoreError(429, "device_limit")
            token = secrets.token_urlsafe(32)
            device_id = str(uuid.uuid4())
            db.execute("INSERT INTO devices(id,token_hash,label,created) VALUES(?,?,?,?)", (device_id, digest(token), row[0], time.time()))
            db.execute("DELETE FROM pair_codes WHERE code_hash=?", (digest(code),))
        return {"deviceId": device_id, "token": token}

    def authenticate(self, token: str):
        with self.connect() as db:
            row = db.execute("SELECT id FROM devices WHERE token_hash=? AND revoked=0", (digest(token),)).fetchone()
        if row is None:
            raise StoreError(401, "unauthorized")
        return row[0]

    def revoke(self, device_id: str):
        with self.connect() as db:
            db.execute("UPDATE devices SET revoked=1 WHERE id=?", (device_id,))

    @staticmethod
    def _delete_batches(db, rows):
        for device, batch_id, size in rows:
            db.execute("DELETE FROM batches WHERE device=? AND id=?", (device, batch_id))
            db.execute("UPDATE counters SET bytes=bytes-?, evicted=evicted+1 WHERE id=1", (size,))

    def maintenance(self):
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            expired = db.execute("SELECT device,id,bytes FROM batches WHERE received<? ORDER BY received LIMIT 128",
                                 (time.time() - self.settings.retention_seconds,)).fetchall()
            self._delete_batches(db, expired)
            db.execute("DELETE FROM receipts WHERE rowid IN (SELECT rowid FROM receipts WHERE received<? LIMIT 128)", (time.time() - 90 * 86400,))
            db.execute("DELETE FROM pair_codes WHERE expires<?", (time.time(),))
        with self.connect() as db:
            db.execute("PRAGMA incremental_vacuum(256)")
            db.execute("PRAGMA wal_checkpoint(TRUNCATE)")

    def accept(self, device: str, batch_id: str, payload: bytes, sha256: str, records: int):
        self.require_disk()
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            previous = db.execute("SELECT sha256 FROM receipts WHERE device=? AND id=?", (device, batch_id)).fetchone()
            if previous:
                if previous[0] != sha256:
                    raise StoreError(409, "batch_conflict")
                return {"batchId": batch_id, "sha256": sha256, "duplicate": True}
            if db.execute("SELECT count(*) FROM receipts").fetchone()[0] >= 100000:
                raise StoreError(503, "receipt_limit")
            if len(payload) > self.settings.max_log_bytes:
                raise StoreError(413, "store_limit")
            size = db.execute("SELECT bytes FROM counters WHERE id=1").fetchone()[0]
            while size + len(payload) > self.settings.max_log_bytes:
                rows = db.execute("SELECT device,id,bytes FROM batches ORDER BY received LIMIT 128").fetchall()
                if not rows:
                    raise StoreError(503, "store_full")
                self._delete_batches(db, rows)
                size -= sum(row[2] for row in rows)
            now = time.time()
            db.execute("INSERT INTO receipts VALUES(?,?,?,?,?)", (device, batch_id, sha256, records, now))
            db.execute("INSERT INTO batches VALUES(?,?,?,?,?)", (device, batch_id, payload, len(payload), now))
            db.execute("UPDATE counters SET bytes=bytes+? WHERE id=1", (len(payload),))
        # Returning outside the transaction is intentional: a receipt requires a successful FULL commit.
        return {"batchId": batch_id, "sha256": sha256, "duplicate": False}

    def read_batch(self, device: str, batch_id: str):
        with self.connect() as db:
            row = db.execute("SELECT payload FROM batches WHERE device=? AND id=?", (device, batch_id)).fetchone()
        return row[0] if row else None

    def status(self):
        with self.connect() as db:
            size, evicted = db.execute("SELECT bytes,evicted FROM counters WHERE id=1").fetchone()
            devices = db.execute("SELECT id,label,created,revoked FROM devices ORDER BY created").fetchall()
        return {"logBytes": size, "evictedBatches": evicted, "devices": devices}
