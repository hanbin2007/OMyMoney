import asyncio
import os
from pathlib import Path
import ssl
import subprocess
import tempfile
import unittest

from receiver.serve import Certificates


class CertificateRotationTest(unittest.IsolatedAsyncioTestCase):
    async def test_new_handshakes_use_new_generation_without_restart_and_bad_pair_keeps_old(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in ("first", "second"):
                directory = root / name
                directory.mkdir()
                subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
                                "-subj", "/CN=localhost", "-keyout", str(directory / "privkey.pem"),
                                "-out", str(directory / "fullchain.pem")], check=True, capture_output=True)
            current = root / "current"
            current.symlink_to(root / "first")
            certificates = Certificates(current)
            initial = certificates.context
            initial.sni_callback = certificates.select
            async def respond(reader, writer):
                writer.write(b"ok")
                await writer.drain()
                writer.close()
                await writer.wait_closed()
            server = await asyncio.start_server(respond, "127.0.0.1", 0, ssl=initial)
            port = server.sockets[0].getsockname()[1]
            client = ssl.create_default_context()
            client.check_hostname = False
            client.verify_mode = ssl.CERT_NONE  # Test-only self-signed endpoints; production never uses this.
            async def peer():
                reader, writer = await asyncio.open_connection("127.0.0.1", port, ssl=client, server_hostname="localhost")
                certificate = writer.get_extra_info("ssl_object").getpeercert(binary_form=True)
                self.assertEqual(b"ok", await reader.read())
                writer.close()
                await writer.wait_closed()
                return certificate
            try:
                old = await peer()
                (root / "next").symlink_to(root / "second")
                os.replace(root / "next", current)
                self.assertTrue(certificates.refresh())
                new = await peer()
                self.assertNotEqual(old, new)
                self.assertFalse(certificates.refresh())
                (root / "second" / "privkey.pem").write_text("broken")
                with self.assertRaises(ssl.SSLError):
                    certificates.refresh()
                self.assertEqual(new, await peer())
            finally:
                server.close()
                await server.wait_closed()
