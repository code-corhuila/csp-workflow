"""Recording proxy between csp-workflow and the real csp-booking-api (end-to-end test only).

Booking does not log the Idempotency-Key of a confirm or a release, and the workflow does not log what it sends, so
this proxy sits on the workflow's BOOKING_API_URL and writes one line per call: method, route, status, the
Idempotency-Key, the X-Correlation-Id and the SHA-256 of the bearer token (never the token). Every call is forwarded
to Booking untouched, except for the two switches below, both off unless the test turns them on:

  confirm_delay_ms  holds a confirm for that long before forwarding it, so the test can act on Booking in between.
  fill_snapshot     adds movieTitle and roomName to a hold that lacks them. Booking develop validates both as required
                    although booking-service.yaml says Catalog supplies them; this is the only way to get past that
                    defect without editing Booking, and the line is marked patched=1.

Control: POST /__tap/config (JSON), POST /__tap/reset, GET /__tap/calls (the recorded lines).
"""
import hashlib
import json
import threading
import time
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

UPSTREAM = ("booking-api", 8083)
config = {"confirm_delay_ms": 0, "fill_snapshot": False}
calls = []
lock = threading.Lock()


class Tap(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass

    def _reply(self, status, body, content_type="application/json", extra=()):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        for name, value in extra:
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def _body(self):
        return self.rfile.read(int(self.headers.get("Content-Length") or 0))

    def do_GET(self):
        if self.path == "/__tap/calls":
            with lock:
                return self._reply(200, "\n".join(calls).encode(), "text/plain")
        self._forward(b"")

    def do_POST(self):
        body = self._body()
        if self.path == "/__tap/config":
            config.update(json.loads(body or b"{}"))
            return self._reply(200, json.dumps(config).encode())
        if self.path == "/__tap/reset":
            with lock:
                calls.clear()
            return self._reply(200, b"{}")
        self._forward(body)

    def _forward(self, body):
        patched = 0
        if self.path.endswith("/internal/workflow/holds") and config["fill_snapshot"]:
            payload = json.loads(body)
            if "movieTitle" not in payload or "roomName" not in payload:
                payload.setdefault("movieTitle", "snapshot")
                payload.setdefault("roomName", "snapshot")
                body, patched = json.dumps(payload).encode(), 1
        if self.path.endswith("/confirm") and config["confirm_delay_ms"]:
            time.sleep(config["confirm_delay_ms"] / 1000)
        headers = {k: v for k, v in self.headers.items() if k.lower() not in ("host", "content-length")}
        headers["Content-Length"] = str(len(body))
        connection = HTTPConnection(*UPSTREAM, timeout=30)
        connection.request(self.command, self.path, body=body or None, headers=headers)
        upstream = connection.getresponse()
        data = upstream.read()
        token = (self.headers.get("Authorization") or "").removeprefix("Bearer ").strip()
        line = "{} {} {} status={} idem={} corr={} tokensha={} patched={}".format(
            time.strftime("%H:%M:%S"), self.command, self.path.split("/api/v1/booking")[-1], upstream.status,
            self.headers.get("Idempotency-Key", "-"), self.headers.get("X-Correlation-Id", "-"),
            hashlib.sha256(token.encode()).hexdigest() if token else "-", patched)
        with lock:
            calls.append(line)
        print(line, flush=True)
        self._reply(upstream.status, data, upstream.getheader("Content-Type", "application/json"))


ThreadingHTTPServer(("0.0.0.0", 8083), Tap).serve_forever()
