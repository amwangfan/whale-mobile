#!/usr/bin/env python3
"""Peek-proxy: public :18799 → HTTP to ring hub, else TCP to audio bridge.

Phone long-poll / curl use HTTP on the same public port as PCM audio.
Aliyun SG already allows 18799; 18800 is closed.
"""
from __future__ import annotations

import argparse
import os
import select
import socket
import threading

LISTEN_HOST = os.environ.get("MUX_HOST", "0.0.0.0")
LISTEN_PORT = int(os.environ.get("MUX_PORT", "18799"))
HTTP_UPSTREAM = (
    os.environ.get("MUX_HTTP_HOST", "127.0.0.1"),
    int(os.environ.get("MUX_HTTP_PORT", "18800")),
)
TCP_UPSTREAM = (
    os.environ.get("MUX_TCP_HOST", "127.0.0.1"),
    int(os.environ.get("MUX_TCP_PORT", "18798")),
)

HTTP_PREFIXES = (
    b"GET ", b"POST", b"PUT ", b"HEAD", b"OPTI", b"DELE", b"PATC", b"CONN"
)


def _pipe(a: socket.socket, b: socket.socket) -> None:
    try:
        while True:
            r, _, _ = select.select([a, b], [], [], 300)
            if not r:
                break
            for src in r:
                dst = b if src is a else a
                data = src.recv(65536)
                if not data:
                    return
                dst.sendall(data)
    except OSError:
        pass
    finally:
        for s in (a, b):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                s.close()
            except OSError:
                pass


def _handle(client: socket.socket, addr) -> None:
    upstream = None
    try:
        client.settimeout(10)
        first = client.recv(8, socket.MSG_PEEK)
        if not first:
            client.close()
            return
        if any(first.startswith(p) for p in HTTP_PREFIXES):
            target = HTTP_UPSTREAM
            kind = "http"
        else:
            target = TCP_UPSTREAM
            kind = "tcp"
        print(f"[mux] {addr} -> {target} ({kind}) peek={first[:8]!r}", flush=True)
        upstream = socket.create_connection(target, timeout=10)
        client.settimeout(None)
        upstream.settimeout(None)
        t = threading.Thread(target=_pipe, args=(client, upstream), daemon=True)
        t.start()
        t.join()
    except Exception as e:
        print(f"[mux] err {addr}: {e}", flush=True)
        try:
            client.close()
        except OSError:
            pass
        if upstream:
            try:
                upstream.close()
            except OSError:
                pass


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default=LISTEN_HOST)
    ap.add_argument("--port", type=int, default=LISTEN_PORT)
    args = ap.parse_args()
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.host, args.port))
    srv.listen(128)
    print(
        f"[mux] listen={args.host}:{args.port} "
        f"http->{HTTP_UPSTREAM[0]}:{HTTP_UPSTREAM[1]} "
        f"tcp->{TCP_UPSTREAM[0]}:{TCP_UPSTREAM[1]}",
        flush=True,
    )
    while True:
        c, addr = srv.accept()
        threading.Thread(target=_handle, args=(c, addr), daemon=True).start()


if __name__ == "__main__":
    main()
