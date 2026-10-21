import argparse
import json
import os
from pathlib import Path

from .store import Settings, Store


def main():
    parser = argparse.ArgumentParser(description="SSH-only diagnostic administration")
    parser.add_argument("--data", type=Path, default=Path(os.environ.get("OMYMONEY_DATA", "/data/omymoney/state")))
    commands = parser.add_subparsers(dest="command", required=True)
    pair = commands.add_parser("pair")
    pair.add_argument("--label", required=True)
    revoke = commands.add_parser("revoke")
    revoke.add_argument("device")
    commands.add_parser("status")
    commands.add_parser("prune")
    batches = commands.add_parser("batches")
    batches.add_argument("--device", required=True)
    export = commands.add_parser("export")
    export.add_argument("--device", required=True)
    export.add_argument("--batch", required=True)
    export.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    store = Store(Settings(args.data))
    if args.command == "pair":
        print(store.create_pair_code(args.label))
    elif args.command == "revoke":
        store.revoke(args.device)
        print("Revoked")
    elif args.command == "status":
        print(json.dumps(store.status()))
    elif args.command == "prune":
        store.maintenance()
        print("Pruned one bounded page")
    elif args.command == "batches":
        with store.connect() as db:
            rows = db.execute("SELECT id,bytes,received FROM batches WHERE device=? ORDER BY received DESC LIMIT 20", (args.device,)).fetchall()
        print(json.dumps(rows))
    elif args.command == "export":
        payload = store.read_batch(args.device, args.batch)
        if payload is None:
            parser.error("batch missing or expired")
        descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "wb") as output:
            output.write(payload)
        print("Exported one gzip batch")


if __name__ == "__main__":
    main()
