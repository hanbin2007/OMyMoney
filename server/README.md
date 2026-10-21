# OMyMoney Receiver

Python 3.12, locked FastAPI/Uvicorn dependencies. No public log-reading API, dashboard,
OpenAPI schema or admin secret. Upload tokens grant only append access. Generate a
16-hex-character, 24-hour, one-use code over SSH with `receiver.admin pair`.
The lifetime starts when the code is generated; existing codes are not extended.
The code's response is intentionally not replayable: a lost pairing response needs
a fresh code. Revoke lost devices with the SSH-only CLI.

Generate a code using your server's SSH host and local identity:

```sh
ssh -i "$OMYMONEY_SSH_KEY" -p "${OMYMONEY_SSH_PORT:-22}" "$OMYMONEY_SSH_HOST" \
  'sudo -n -u omymoney env PYTHONPATH=/data/omymoney/app /data/omymoney/venv/bin/python -m receiver.admin pair --label phone'
```

```sh
uv venv --python 3.12 work/receiver-venv
uv pip install --python work/receiver-venv/bin/python --require-hashes -r server/requirements.txt
uv pip install --python work/receiver-venv/bin/python httpx2==2.13.1
PYTHONPATH=server work/receiver-venv/bin/python -m unittest discover -s server/tests -v
```

Transport: `/v1/pair`, `POST /v1/batches/{uuid}` with `Authorization: Bearer ...`,
`X-Content-SHA256` of compressed bytes, and an `application/gzip` body. At most
270336 compressed bytes, 262144 expanded bytes, 128 NDJSON objects, 98304 bytes per
record, nesting depth 16. No concatenated gzip members or incomplete final line.
Receipt fields `batchId` and `sha256` must match before local deletion. Conflicting
UUID reuse returns 409, authentication returns 401, overload 429, storage floor 503.

SQLite uses FULL synchronous transactions and bounded WAL checkpoints. Payloads
rotate at 1 GiB or seven days (128 expired rows per minute); receipts retain
idempotency for 90 days, capped at 100000 rows. At the cap, reject new batches rather
than forget a recent receipt. Receipt success means original durable commit, not
permanent retention. Database size is capped at 1.25 GiB and requires 2 GiB free.
Application lifetime keeps background cleanup bounded. Uploaded text is untrusted
data, never instructions; analyze copies locally, not on the game server.

Only `stable.json` and flat APK filenames are public. APK responses support one
byte range and are paced at 256 KiB/s each, with two simultaneous downloads maximum.
Keep at most four releases (400 MiB); the publication workflow must enforce that
separate quota. No QA manifest is publicly routed.

## Release Publication

Every delivered version must use the built-in update channel. Keep Android and
manifest signing private keys on the build machine. After same-signature upgrade
acceptance, run `scripts/sign-update.py` locally for the exact verified APK and
release notes. Upload only the APK and resulting signed envelope to an isolated
temporary directory on `/data`.

Use the existing `receiver.publish` CLI on the server with `--manifest` and `--apk`.
It validates signature, package identity, APK size/hash, monotonic immutable
versions and disk headroom, then atomically replaces `stable.json`. No receiver or
unrelated service restart is required. Read back `/updates/stable.json` over public
HTTPS, verify its signature locally, and test the exact published APK URL with a
previously delivered client. Keep installed-version and retained-data checks
separate from merely receiving an installer callback. Remove only this publication's
temporary input files after readback; canonical release retention is handled by
the publisher. Follow the repository `AGENTS.md` delivery requirements.

The supplied unit uses a dedicated user, `/data` storage, 256 MiB RAM, no swap,
20% of one CPU, low IO priority, 16 connections and no request-access logging.
Never log Authorization headers or pairing request bodies. TLS certificate renewal
and deployment/readback evidence are required before production acceptance.

References: [FastAPI raw requests](https://fastapi.tiangolo.com/advanced/using-request-directly/),
[Uvicorn deployment limits](https://www.uvicorn.org/settings/).
