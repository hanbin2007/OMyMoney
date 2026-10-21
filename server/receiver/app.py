import asyncio
from contextlib import asynccontextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import time
import uuid
import zlib

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response, StreamingResponse
from starlette.concurrency import run_in_threadpool

from .store import Settings, Store, StoreError

MAX_BODY = 270336
MAX_RAW = 262144


def bounded_json(raw: bytes):
    def reject_constant(_):
        raise ValueError("non_finite")

    # Bound nesting before invoking the recursive parser, respecting escaped quotes.
    depth = 0
    in_string = False
    escaped = False
    for char in raw.decode("utf-8"):
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
        elif char == '"':
            in_string = True
        elif char in "[{":
            depth += 1
            if depth > 16:
                raise ValueError("depth")
        elif char in "}]":
            depth -= 1
    return json.loads(raw, parse_constant=reject_constant)


def validate_batch(data: bytes, sha256: str):
    if not re.fullmatch("[0-9a-f]{64}", sha256) or hashlib.sha256(data).hexdigest() != sha256:
        raise StoreError(400, "hash_mismatch")
    try:
        inflater = zlib.decompressobj(16 + zlib.MAX_WBITS)
        raw = inflater.decompress(data, MAX_RAW + 1)
        if len(raw) > MAX_RAW or inflater.unconsumed_tail:
            raise StoreError(413, "decompressed_size")
        if not inflater.eof or inflater.unused_data or not raw.endswith(b"\n"):
            raise ValueError("incomplete_or_trailing")
        lines = raw.splitlines()
        if not 1 <= len(lines) <= 128:
            raise ValueError("record_count")
        for line in lines:
            if len(line) > 98304:
                raise ValueError("record_size")
            record = bounded_json(line)
            if not isinstance(record, dict) or not isinstance(record.get("kind"), str) or len(record["kind"]) > 80:
                raise ValueError("record_shape")
        return len(lines)
    except (ValueError, zlib.error, UnicodeDecodeError, RecursionError) as error:
        raise StoreError(400, "invalid_batch") from error


class RateLimit:
    def __init__(self):
        self.buckets = {}

    def check(self, key: str, limit: int, seconds: int):
        now = time.monotonic()
        if len(self.buckets) >= 1024:
            self.buckets = {key: value for key, value in self.buckets.items() if value[1] > now}
            if len(self.buckets) >= 1024:
                raise StoreError(429, "rate_limit")
        count, end = self.buckets.get(key, (0, now + seconds))
        if end <= now:
            count, end = 0, now + seconds
        if count >= limit:
            raise StoreError(429, "rate_limit")
        self.buckets[key] = (count + 1, end)


class DownloadResponse(StreamingResponse):
    def __init__(self, content, source, slots, **kwargs):
        super().__init__(content, **kwargs)
        self.source = source
        self.slots = slots

    async def __call__(self, scope, receive, send):
        try:
            await super().__call__(scope, receive, send)
        finally:
            self.source.close()
            self.slots.release()


async def read_body(request: Request, limit: int):
    try:
        length = request.headers.get("content-length")
        if length is not None and (int(length) < 0 or int(length) > limit):
            raise StoreError(413, "body_size")
        result = bytearray()
        async with asyncio.timeout(15):
            async for chunk in request.stream():
                if len(result) + len(chunk) > limit:
                    raise StoreError(413, "body_size")
                result.extend(chunk)
        return bytes(result)
    except ValueError as error:
        raise StoreError(400, "invalid_length") from error
    except TimeoutError as error:
        raise StoreError(408, "body_timeout") from error


def create_app(settings: Settings, store: Store | None = None):
    store = store or Store(settings)
    rates = RateLimit()
    downloads = asyncio.Semaphore(2)

    @asynccontextmanager
    async def lifespan(_):
        async def maintain():
            while True:
                try:
                    await run_in_threadpool(store.maintenance)
                except (OSError, sqlite3.Error):
                    pass  # The request path still refuses receipts if storage fails.
                await asyncio.sleep(60)
        task = asyncio.create_task(maintain())
        yield
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass

    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)

    @app.exception_handler(StoreError)
    async def store_error(_, error):
        return JSONResponse({"error": error.code}, status_code=error.status,
                            headers={"Cache-Control": "no-store", "Retry-After": "60"} if error.status in (429, 503) else {"Cache-Control": "no-store"})

    @app.exception_handler(sqlite3.Error)
    @app.exception_handler(OSError)
    async def storage_error(_, error):
        return JSONResponse({"error": "storage_unavailable"}, status_code=503, headers={"Retry-After": "60"})

    @app.middleware("http")
    async def request_limit(request, call_next):
        try:
            rates.check("global", 30, 1)
        except StoreError:
            return JSONResponse({"error": "rate_limit"}, status_code=429, headers={"Retry-After": "60"})
        response = await call_next(request)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Cache-Control"] = "no-store"
        return response

    @app.get("/healthz")
    async def health():
        await run_in_threadpool(store.require_disk)
        return {"service": "omymoney", "protocol": 1}

    @app.post("/v1/pair")
    async def pair(request: Request):
        rates.check("pair:" + request.client.host, 5, 60)
        try:
            data = bounded_json(await read_body(request, 1024))
            if not isinstance(data, dict) or not isinstance(data.get("code"), str) or len(data["code"]) > 64:
                raise ValueError("code")
        except (ValueError, UnicodeDecodeError) as error:
            raise StoreError(400, "pair_format") from error
        return await run_in_threadpool(store.pair, data["code"])

    @app.post("/v1/batches/{batch_id}")
    async def batch(batch_id: str, request: Request):
        authorization = request.headers.get("authorization", "")
        if not authorization.startswith("Bearer ") or len(authorization) > 128:
            raise StoreError(401, "unauthorized")
        device = await run_in_threadpool(store.authenticate, authorization[7:])
        rates.check("device:" + device, 120, 60)
        await run_in_threadpool(store.require_disk)
        try:
            if str(uuid.UUID(batch_id)) != batch_id:
                raise ValueError("id")
        except ValueError as error:
            raise StoreError(400, "batch_id") from error
        data = await read_body(request, MAX_BODY)
        sha256 = request.headers.get("x-content-sha256", "")
        records = await run_in_threadpool(validate_batch, data, sha256)
        return await run_in_threadpool(store.accept, device, batch_id, data, sha256, records)

    @app.get("/updates/{name}")
    async def update(name: str, request: Request):
        if name != "stable.json" and not re.fullmatch(r"[A-Za-z0-9_-]+\.apk", name):
            raise StoreError(404, "not_found")
        file = settings.releases / name
        if file.is_symlink() or not file.is_file():
            raise StoreError(404, "not_found")
        size = file.stat().st_size
        if name.endswith(".json"):
            if size > 32768:
                raise StoreError(503, "manifest_size")
            return Response(file.read_bytes(), media_type="application/json")
        if size <= 0 or size > 100 * 1024**2:
            raise StoreError(503, "apk_size")
        start, end, status = 0, size - 1, 200
        byte_range = request.headers.get("range")
        if byte_range:
            match = re.fullmatch(r"bytes=(\d+)-(\d*)", byte_range)
            if not match:
                raise StoreError(416, "range")
            start = int(match[1])
            end = int(match[2]) if match[2] else size - 1
            if not 0 <= start <= end < size:
                raise StoreError(416, "range")
            status = 206
        if downloads.locked():
            raise StoreError(429, "download_busy")
        try:
            source = file.open("rb")
        except OSError as error:
            raise StoreError(404, "not_found") from error
        await downloads.acquire()

        async def chunks():
            remaining = end - start + 1
            source.seek(start)
            while remaining:
                chunk = source.read(min(32768, remaining))
                if not chunk:
                    break
                yield chunk
                remaining -= len(chunk)
                await asyncio.sleep(len(chunk) / 262144)
        headers = {"Accept-Ranges": "bytes", "Content-Length": str(end - start + 1)}
        if status == 206:
            headers["Content-Range"] = f"bytes {start}-{end}/{size}"
        return DownloadResponse(chunks(), source, downloads, status_code=status, media_type="application/vnd.android.package-archive", headers=headers)

    return app


def from_environment():
    return create_app(Settings(Path(os.environ.get("OMYMONEY_DATA", "/data/omymoney/state"))))
