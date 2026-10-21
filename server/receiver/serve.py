"""Run Uvicorn with certificate rotation that never restarts the application."""
import asyncio
import logging
import os
from pathlib import Path
import ssl

import uvicorn


class Certificates:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.identity = None
        self.context = None
        self.refresh()

    def refresh(self):
        # Resolve one generation so an atomic current-symlink swap cannot mix a key and certificate.
        directory = self.directory.resolve(strict=True)
        certificate, key = directory / "fullchain.pem", directory / "privkey.pem"
        identity = (directory, certificate.stat().st_mtime_ns, key.stat().st_mtime_ns)
        if identity == self.identity:
            return False
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(certificate, key)
        self.context, self.identity = context, identity
        return True

    def select(self, connection, server_name, initial_context):
        connection.context = self.context


async def main():
    certificates = Certificates(os.environ.get("OMYMONEY_TLS", "/data/omymoney/tls/current"))
    certificates.context.sni_callback = certificates.select
    config = uvicorn.Config("receiver.app:from_environment", factory=True, host="0.0.0.0", port=18443,
                            workers=1, limit_concurrency=16, backlog=32, timeout_keep_alive=5,
                            h11_max_incomplete_event_size=16384, access_log=False, proxy_headers=False,
                            ssl_context_factory=lambda *_: certificates.context)
    server = uvicorn.Server(config)

    async def refresh():
        while True:
            await asyncio.sleep(30)
            try:
                if certificates.refresh():
                    logging.getLogger("uvicorn.error").info("TLS certificate generation reloaded without restart")
            except (OSError, ssl.SSLError):
                logging.getLogger("uvicorn.error").error("TLS certificate reload rejected; keeping previous generation")

    task = asyncio.create_task(refresh())
    try:
        await server.serve()
    finally:
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass


if __name__ == "__main__":
    asyncio.run(main())
