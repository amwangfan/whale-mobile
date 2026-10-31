#!/usr/bin/env python3
"""Nova ring hub — stdlib HTTP long-poll + optional WS, sibling-compatible paths.

Phone:   GET /v1/poll?device_id=magic8&wait=50
Trigger: curl -X POST 'http://HOST:18800/v1/ring?device_id=magic8&number=***&name=AI'
Health:  GET /health

Also accepts legacy GET /wait and POST /ring (JSON) used by early mock.
Env: SIGNAL_HOST SIGNAL_PORT SIGNAL_TOKEN BRIDGE_PUBLIC_HOST BRIDGE_PORT
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import queue
import socket
import hmac
import struct
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

HOST = os.environ.get("SIGNAL_HOST") or os.environ.get("RING_HUB_HOST") or "0.0.0.0"
PORT = int(os.environ.get("SIGNAL_PORT") or os.environ.get("RING_HUB_PORT") or "18800")
TOKEN = (os.environ.get("SIGNAL_TOKEN") or os.environ.get("RING_TOKEN") or "").strip()
BRIDGE_HOST = (
    os.environ.get("BRIDGE_PUBLIC_HOST")
    or os.environ.get("RING_BRIDGE_HOST")
    or "127.0.0.1"
).strip() or "127.0.0.1"
BRIDGE_PORT = int(os.environ.get("BRIDGE_PORT") or os.environ.get("RING_BRIDGE_PORT") or "18799")

_lock = threading.Lock()
_pending: dict[str, list[dict[str, Any]]] = {}
_waiters: dict[str, list[queue.Queue]] = {}
_ws: list[tuple[str, socket.socket]] = []
_stats = {"rings": 0, "delivered": 0, "polls": 0, "started": time.time()}


def _auth(params: dict[str, str], headers) -> bool:
    if not TOKEN:
        return False
    auth = headers.get("Authorization") if headers else None
    if auth and auth.lower().startswith("bearer ") and hmac.compare_digest(auth[7:].strip(), TOKEN):
        return True
    tok = headers.get("X-Ring-Token", "") if headers else ""
    return hmac.compare_digest(tok or "", TOKEN)


def _ring_msg(params: dict[str, Any]) -> dict[str, Any]:
    device_id = (str(params.get("device_id") or params.get("device") or "magic8")).strip() or "magic8"
    number = (str(params.get("number") or "***")).strip() or "***"
    name = (str(params.get("name") or "AI 助手")).strip() or "AI 助手"
    host = (str(params.get("bridge_host") or BRIDGE_HOST)).strip() or BRIDGE_HOST
    try:
        port = int(params.get("bridge_port") or BRIDGE_PORT)
    except Exception:
        port = BRIDGE_PORT
    return {
        "action": "ring",
        "cmd": "ring",  # legacy alias
        "device_id": device_id,
        "number": number,
        "name": name,
        "bridge_host": host,
        "bridge_port": port,
        "ts": int(time.time()),
    }


def _deliver(msg: dict[str, Any]) -> dict[str, Any]:
    device_id = msg.get("device_id") or "magic8"
    delivered = False
    with _lock:
        _stats["rings"] += 1
        # WS first
        keep_ws: list[tuple[str, socket.socket]] = []
        payload = json.dumps(msg, ensure_ascii=False).encode("utf-8")
        frame = _ws_frame(payload)
        for did, sock in _ws:
            if did not in (device_id, "*", "default") and device_id not in ("*",):
                if did != device_id:
                    keep_ws.append((did, sock))
                    continue
            try:
                sock.sendall(frame)
                delivered = True
                _stats["delivered"] += 1
                keep_ws.append((did, sock))
            except OSError:
                try:
                    sock.close()
                except OSError:
                    pass
        _ws[:] = keep_ws

        # wake long-poll waiters
        woken = 0
        for q in list(_waiters.get(device_id, [])):
            try:
                q.put_nowait(msg)
                woken += 1
                delivered = True
            except queue.Full:
                pass
        # also wake wildcard waiters
        for q in list(_waiters.get("*", [])):
            try:
                q.put_nowait(msg)
                woken += 1
                delivered = True
            except queue.Full:
                pass

        if not delivered:
            _pending.setdefault(device_id, []).append(msg)
            _pending[device_id] = _pending[device_id][-5:]
        else:
            _stats["delivered"] += max(0, woken)
    return {"ok": True, "delivered": delivered, "queued": not delivered, "device_id": device_id, "msg": msg}


def _take_pending(device_id: str) -> list[dict[str, Any]]:
    with _lock:
        items = _pending.pop(device_id, [])
        if device_id != "*":
            # also drain default alias
            if device_id == "magic8":
                items += _pending.pop("default", [])
        return items


def _ws_accept_key(key: str) -> str:
    dig = hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()
    return base64.b64encode(dig).decode("ascii")


def _ws_frame(payload: bytes, opcode: int = 0x1) -> bytes:
    n = len(payload)
    header = bytearray([0x80 | (opcode & 0x0F)])
    if n < 126:
        header.append(n)
    elif n < 65536:
        header.append(126)
        header.extend(struct.pack("!H", n))
    else:
        header.append(127)
        header.extend(struct.pack("!Q", n))
    return bytes(header) + payload


def _recv_exact(sock: socket.socket, n: int) -> bytes | None:
    buf = bytearray()
    while len(buf) < n:
        try:
            chunk = sock.recv(n - len(buf))
        except OSError:
            return None
        if not chunk:
            return None
        buf.extend(chunk)
    return bytes(buf)


def _ws_read(sock: socket.socket) -> tuple[int, bytes] | None:
    hdr = _recv_exact(sock, 2)
    if not hdr:
        return None
    opcode = hdr[0] & 0x0F
    masked = (hdr[1] & 0x80) != 0
    length = hdr[1] & 0x7F
    if length == 126:
        ext = _recv_exact(sock, 2)
        if not ext:
            return None
        length = struct.unpack("!H", ext)[0]
    elif length == 127:
        ext = _recv_exact(sock, 8)
        if not ext:
            return None
        length = struct.unpack("!Q", ext)[0]
    mask = _recv_exact(sock, 4) if masked else b""
    data = _recv_exact(sock, length) if length else b""
    if data is None:
        return None
    if masked and data and mask:
        data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
    return opcode, data or b""


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "NovaRingHub/1.6"

    def log_message(self, fmt: str, *args: Any) -> None:
        print(f"[ring-hub] {self.address_string()} {fmt % args}", flush=True)

    def _json(self, code: int, obj: Any) -> None:
        raw = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Connection", "close")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(raw)

    def _read_json(self) -> dict[str, Any]:
        n = int(self.headers.get("Content-Length") or "0")
        if n < 0 or n > 65536:
            raise ValueError("invalid request length")
        raw = self.rfile.read(n) if n > 0 else b""
        if not raw:
            return {}
        try:
            data = json.loads(raw.decode("utf-8"))
            return data if isinstance(data, dict) else {}
        except Exception:
            return {}

    def do_OPTIONS(self) -> None:  # noqa: N802
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Ring-Token")
        self.end_headers()

    def do_GET(self) -> None:  # noqa: N802
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path.rstrip("/") or "/"
        qs = {k: (v[0] if v else "") for k, v in urllib.parse.parse_qs(parsed.query).items()}

        if path in ("/", "/health", "/v1/health"):
            with _lock:
                online = sorted({d for d, _ in _ws}) + sorted(_waiters.keys())
            self._json(200, {
                "ok": True,
                "service": "nova-ring-hub",
                "online": online,
                "pending": {k: len(v) for k, v in _pending.items()},
                "bridge": f"{BRIDGE_HOST}:{BRIDGE_PORT}",
                "uptime_s": int(time.time() - _stats["started"]),
            })
            return

        if path == "/v1/devices":
            if not _auth(qs, self.headers):
                self._json(401, {"ok": False, "error": "unauthorized"})
                return
            with _lock:
                self._json(200, {
                    "ok": True,
                    "waiters": {k: len(v) for k, v in _waiters.items()},
                    "ws": [d for d, _ in _ws],
                    "pending": {k: len(v) for k, v in _pending.items()},
                })
            return

        if path == "/v1/ring":
            if not _auth(qs, self.headers):
                self._json(401, {"ok": False, "error": "unauthorized"})
                return
            self._json(200, _deliver(_ring_msg(qs)))
            return

        if path == "/v1/poll":
            # poll itself is unauthenticated for phone simplicity unless TOKEN set
            if TOKEN and not _auth(qs, self.headers):
                self._json(401, {"ok": False, "error": "unauthorized"})
                return
            device_id = (qs.get("device_id") or "magic8").strip() or "magic8"
            try:
                wait_s = float(qs.get("wait") or "50")
            except Exception:
                wait_s = 50.0
            wait_s = max(0.0, min(wait_s, 55.0))
            with _lock:
                _stats["polls"] += 1
            items = _take_pending(device_id)
            if not items and wait_s > 0:
                q: queue.Queue = queue.Queue(maxsize=1)
                with _lock:
                    _waiters.setdefault(device_id, []).append(q)
                try:
                    msg = q.get(timeout=wait_s)
                    items = [msg]
                except queue.Empty:
                    items = []
                finally:
                    with _lock:
                        lst = _waiters.get(device_id) or []
                        if q in lst:
                            lst.remove(q)
                        if not lst:
                            _waiters.pop(device_id, None)
                if not items:
                    items = _take_pending(device_id)
            self._json(200, {"ok": True, "device_id": device_id, "messages": items})
            return

        if path == "/wait":
            # legacy long-poll
            if TOKEN and not _auth(qs, self.headers):
                self._json(401, {"ok": False, "error": "unauthorized"})
                return
            device = (qs.get("device") or qs.get("device_id") or "magic8").strip() or "magic8"
            try:
                timeout = float(qs.get("timeout") or "55")
            except Exception:
                timeout = 55.0
            # reuse poll
            self.path = f"/v1/poll?device_id={urllib.parse.quote(device)}&wait={timeout}"
            if qs.get("token"):
                self.path += f"&token={urllib.parse.quote(qs['token'])}"
            # manually
            items = _take_pending(device)
            if not items:
                q = queue.Queue(maxsize=1)
                with _lock:
                    _waiters.setdefault(device, []).append(q)
                try:
                    msg = q.get(timeout=max(5.0, min(timeout, 120.0)))
                    self._json(200, msg)
                except queue.Empty:
                    self._json(200, {"cmd": "timeout", "action": "timeout", "ts": int(time.time())})
                finally:
                    with _lock:
                        lst = _waiters.get(device) or []
                        if q in lst:
                            lst.remove(q)
                        if not lst:
                            _waiters.pop(device, None)
            else:
                self._json(200, items[0])
            return

        if path == "/ws" or path.startswith("/ws"):
            self._upgrade_ws(qs)
            return

        self._json(404, {
            "ok": False,
            "error": "not_found",
            "endpoints": [
                "GET /health",
                "GET|POST /v1/ring?device_id=&number=&name=",
                "GET /v1/poll?device_id=&wait=50",
                "WS /ws?device_id=",
            ],
        })

    def do_POST(self) -> None:  # noqa: N802
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path.rstrip("/") or "/"
        qs = {k: (v[0] if v else "") for k, v in urllib.parse.parse_qs(parsed.query).items()}
        body = self._read_json()
        merged = dict(qs)
        merged.update({k: body[k] for k in body})

        if path in ("/v1/ring", "/ring"):
            if not _auth(merged, self.headers):
                self._json(401, {"ok": False, "error": "unauthorized"})
                return
            self._json(200, _deliver(_ring_msg(merged)))
            return

        if path in ("/v1/hangup", "/hangup"):
            if not _auth(merged, self.headers):
                self._json(401, {"ok": False, "error": "unauthorized"})
                return
            device_id = (str(merged.get("device_id") or merged.get("device") or "magic8")).strip()
            msg = {"action": "hangup", "cmd": "hangup", "device_id": device_id, "ts": int(time.time())}
            self._json(200, _deliver(msg))
            return

        self._json(404, {"ok": False, "error": "not_found"})

    def _upgrade_ws(self, qs: dict[str, str]) -> None:
        if TOKEN and not _auth(qs, self.headers):
            self.send_error(401)
            return
        key = self.headers.get("Sec-WebSocket-Key")
        if not key:
            self.send_error(400, "Missing Sec-WebSocket-Key")
            return
        device = (qs.get("device_id") or qs.get("device") or "magic8").strip() or "magic8"
        self.send_response(101, "Switching Protocols")
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", _ws_accept_key(key))
        self.end_headers()
        sock = self.connection
        with _lock:
            _ws.append((device, sock))
        try:
            hello = {
                "action": "hello",
                "device_id": device,
                "bridge_host": BRIDGE_HOST,
                "bridge_port": BRIDGE_PORT,
                "ts": int(time.time()),
            }
            sock.sendall(_ws_frame(json.dumps(hello, ensure_ascii=False).encode("utf-8")))
            # flush pending
            for msg in _take_pending(device):
                sock.sendall(_ws_frame(json.dumps(msg, ensure_ascii=False).encode("utf-8")))
            while True:
                got = _ws_read(sock)
                if got is None:
                    break
                opcode, data = got
                if opcode == 0x8:
                    break
                if opcode == 0x9:
                    sock.sendall(_ws_frame(data, opcode=0xA))
                    continue
                if opcode != 0x1:
                    continue
                try:
                    obj = json.loads(data.decode("utf-8"))
                except Exception:
                    continue
                if isinstance(obj, dict) and obj.get("action") in ("ping", "keepalive"):
                    sock.sendall(_ws_frame(json.dumps({"action": "pong", "ts": int(time.time())}).encode()))
        finally:
            with _lock:
                _ws[:] = [(d, s) for d, s in _ws if s is not sock]
            try:
                sock.close()
            except OSError:
                pass


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default=HOST)
    ap.add_argument("--port", type=int, default=PORT)
    args = ap.parse_args()
    # 安全基线：非回环绑定必须设置 SIGNAL_TOKEN（否则任何人可触发响铃/轮询窃听）。
    loopback = args.host in ("127.0.0.1", "::1", "localhost")
    if not loopback and not TOKEN:
        raise SystemExit(
            f"[ring-hub] 拒绝启动：绑定 {args.host} 为非回环地址但未设置 SIGNAL_TOKEN/RING_TOKEN"
        )
    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    httpd.daemon_threads = True
    print(
        f"[ring-hub] listen={args.host}:{args.port} bridge={BRIDGE_HOST}:{BRIDGE_PORT} "
        f"token={'set' if TOKEN else 'off'}",
        flush=True,
    )
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()
