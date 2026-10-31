#!/usr/bin/env python3
"""whale-mobile V1 开发参考网关（dev_gateway）。

⚠️ 这是**开发用参考实现**，仅用于 whale-mobile 手机端传输层联调。
生产环境的设备面由 whale-core 的 devices/ 组件实现并取代本服务（见 server/README.md）。

协议事实源：contracts/v1/PROTOCOL.md 与 contracts/v1/messages/*.schema.json。
本进程只做明文 ws（TLS 由外层反代终结，§1），并提供回环管理通道（JSON lines）
供 CLI 的 query/action 子命令触达在线设备。
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import logging
import os
import secrets
import sqlite3
import sys
import time
import uuid

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MESSAGES_DIR = os.path.join(ROOT, "contracts", "v1", "messages")
VENDOR_DIR = os.path.join(ROOT, "tests", "_vendor")
if os.path.isdir(VENDOR_DIR):
    sys.path.insert(0, VENDOR_DIR)  # 复用 tests/_vendor 的 jsonschema

import websockets
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from jsonschema import Draft202012Validator

log = logging.getLogger("dev_gateway")

PROTOCOL_VERSION = "1.0"
SUPPORTED_MAJOR = 1
MAX_FRAME_BYTES = 256 * 1024  # §2：单帧 ≤ 256 KiB
DEFAULT_DB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "dev_gateway.db")
PAIR_CODE_TTL_S = 600
REF_ID_FALLBACK = "0" * 16

CAPABILITIES = [
    "screen.context.read", "screen.history.search", "screen.evidence.read",
    "screen.live.subscribe", "phone.notification.post", "phone.notification.read",
    "phone.call.ring", "phone.call.audio", "phone.device.safe_action",
]


def now_ms() -> int:
    return int(time.time() * 1000)


# ---------------------------------------------------------------- schemas

def load_validators() -> dict[str, Draft202012Validator]:
    """加载 contracts/v1/messages 下逐消息 schema（envelope 除外），按 type 索引。"""
    validators: dict[str, Draft202012Validator] = {}
    for fn in sorted(os.listdir(MESSAGES_DIR)):
        if not fn.endswith(".schema.json") or fn == "envelope.schema.json":
            continue
        with open(os.path.join(MESSAGES_DIR, fn), encoding="utf-8") as f:
            schema = json.load(f)
        msg_type = schema.get("properties", {}).get("type", {}).get("const")
        if msg_type:
            validators[msg_type] = Draft202012Validator(schema)
    return validators


def parse_major(v) -> int | None:
    if isinstance(v, str):
        head, _, _ = v.partition(".")
        if head.isdigit():
            return int(head)
    return None


# ---------------------------------------------------------------- database

SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS devices (
  device_id       TEXT PRIMARY KEY,   -- 公钥指纹 SHA-256 前16字节 hex
  pubkey_hex      TEXT NOT NULL,      -- Ed25519 公钥（32字节 hex）
  label           TEXT NOT NULL,
  scopes          TEXT NOT NULL,      -- JSON array，能力上限
  status          TEXT NOT NULL,      -- pending / active / revoked
  pairing_code    TEXT UNIQUE,        -- 6 位配对码（pending 时存在）
  code_expires_at INTEGER,            -- 配对码过期时间（ms）
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS sensor_events (
  device_id      TEXT NOT NULL,
  event_id       TEXT NOT NULL,       -- 幂等键（§4/§6）
  kind           TEXT NOT NULL,
  occurred_at    INTEGER NOT NULL,
  privacy_class  TEXT NOT NULL,
  summary        TEXT NOT NULL,
  evidence_ref   TEXT,
  schema_version TEXT NOT NULL,
  received_at    INTEGER NOT NULL,
  PRIMARY KEY (device_id, event_id)
);
CREATE TABLE IF NOT EXISTS action_requests (
  device_id       TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,      -- 端侧去重键（§6），服务端也按它防重复下发
  action_id       TEXT NOT NULL,
  capability      TEXT NOT NULL,
  created_at      INTEGER NOT NULL,
  PRIMARY KEY (device_id, idempotency_key)
);
CREATE TABLE IF NOT EXISTS action_receipts (
  action_id      TEXT NOT NULL,
  device_id       TEXT NOT NULL,
  state           TEXT NOT NULL,       -- received/accepted/executed/failed/unknown
  ts              INTEGER NOT NULL
);
"""


def db_connect(path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(path, timeout=10)
    conn.row_factory = sqlite3.Row
    return conn


def init_db(path: str) -> None:
    with db_connect(path) as conn:
        conn.executescript(SCHEMA_SQL)


def device_id_from_pubkey_hex(pubkey_hex: str) -> str:
    pub = bytes.fromhex(pubkey_hex)
    if len(pub) != 32:
        raise ValueError("Ed25519 公钥必须为 32 字节（64 hex 字符）")
    return hashlib.sha256(pub).digest()[:16].hex()


def pair_request(db_path: str, label: str, pubkey_hex: str,
                 scopes: list[str] | None = None, code_ttl_s: int = PAIR_CODE_TTL_S):
    """登记待配对设备并生成 6 位配对码。返回 (code, device_id)。

    开发简化：真实流程里公钥由手机端在配对通道上带过来；这里经 CLI 显式传入。
    """
    pubkey_hex = pubkey_hex.lower()
    device_id = device_id_from_pubkey_hex(pubkey_hex)
    scopes = scopes or list(CAPABILITIES)
    unknown = [s for s in scopes if s not in CAPABILITIES]
    if unknown:
        raise ValueError(f"未知 scope: {unknown}")
    init_db(db_path)
    now = now_ms()
    code = f"{secrets.randbelow(1_000_000):06d}"
    with db_connect(db_path) as conn:
        row = conn.execute("SELECT status FROM devices WHERE device_id=?",
                           (device_id,)).fetchone()
        if row and row["status"] == "active":
            raise ValueError(f"设备 {device_id} 已是 active，无需重复配对")
        if row and row["status"] == "revoked":
            raise ValueError(f"设备 {device_id} 已 revoked；如需重配对请先删除记录")
        conn.execute(
            "INSERT INTO devices(device_id,pubkey_hex,label,scopes,status,"
            " pairing_code,code_expires_at,created_at,updated_at)"
            " VALUES(?,?,?,?, 'pending', ?,?,?,?)"
            " ON CONFLICT(device_id) DO UPDATE SET"
            " label=excluded.label, scopes=excluded.scopes,"
            " pairing_code=excluded.pairing_code,"
            " code_expires_at=excluded.code_expires_at, updated_at=excluded.updated_at",
            (device_id, pubkey_hex, label, json.dumps(scopes),
             code, now + code_ttl_s * 1000, now, now))
    return code, device_id


def pair_approve(db_path: str, code: str) -> str:
    """批准配对码 → 设备转 active。返回 device_id。"""
    init_db(db_path)
    with db_connect(db_path) as conn:
        row = conn.execute("SELECT * FROM devices WHERE pairing_code=?",
                           (code,)).fetchone()
        if not row or row["status"] != "pending":
            raise ValueError("配对码不存在或已被处理")
        if row["code_expires_at"] is not None and row["code_expires_at"] < now_ms():
            raise ValueError("配对码已过期，请重新 pair-request")
        conn.execute(
            "UPDATE devices SET status='active', pairing_code=NULL,"
            " code_expires_at=NULL, updated_at=? WHERE device_id=?",
            (now_ms(), row["device_id"]))
        return row["device_id"]


def pair_revoke(db_path: str, device_id: str) -> None:
    """撤销设备（即时生效；在线连接由 Gateway 周期性状态决定，这里仅改库）。"""
    init_db(db_path)
    with db_connect(db_path) as conn:
        cur = conn.execute("UPDATE devices SET status='revoked', updated_at=?"
                           " WHERE device_id=?", (now_ms(), device_id))
        if cur.rowcount == 0:
            raise ValueError(f"未知设备 {device_id}")


def get_device(db_path: str, device_id: str):
    init_db(db_path)
    with db_connect(db_path) as conn:
        return conn.execute("SELECT * FROM devices WHERE device_id=?",
                            (device_id,)).fetchone()


# ---------------------------------------------------------------- gateway

class ClientState:
    """单条 WS 连接的运行时状态。"""

    def __init__(self, ws):
        self.ws = ws
        self.device_id: str | None = None
        self.authed = False
        self.challenge: str | None = None
        self.challenge_deadline = 0.0
        self.session_id: str | None = None
        self.down_seq = 0
        self.expected_up_seq: int | None = None
        self.last_heartbeat = time.monotonic()
        self.offline_marked = False
        self.status = "connecting"


class Gateway:
    def __init__(self, host="127.0.0.1", port=8765,
                 admin_host="127.0.0.1", admin_port=8766,
                 db_path=DEFAULT_DB,
                 heartbeat_interval=30, heartbeat_timeout=90, challenge_ttl=30):
        self.host, self.port = host, port
        self.admin_host, self.admin_port = admin_host, admin_port
        self.db_path = db_path
        self.heartbeat_interval = heartbeat_interval
        self.heartbeat_timeout = heartbeat_timeout
        self.challenge_ttl = challenge_ttl
        self.validators = load_validators()
        self.online: dict[str, ClientState] = {}       # device_id -> state（已认证）
        self.pending: dict[str, asyncio.Future] = {}   # request_id/action_id -> future
        self._ws_server = None
        self._admin_server = None
        self._sweep_task: asyncio.Task | None = None

    # ---- frame 构造与校验（收发双向都过 contracts schema） ----

    def build_message(self, msg_type: str, dir_: str, payload: dict, seq: int) -> dict:
        msg = {"v": PROTOCOL_VERSION, "id": uuid.uuid4().hex, "type": msg_type,
               "ts": now_ms(), "seq": seq, "dir": dir_, "payload": payload}
        errs = list(self.validators[msg_type].iter_errors(msg))
        if errs:  # 服务端自身构造的帧不应失败；失败即实现 bug
            raise ValueError(f"出站帧未通过 {msg_type} schema: {errs[0].message}")
        return msg

    async def send_msg(self, st: ClientState, msg_type: str, payload: dict) -> dict:
        st.down_seq += 1
        msg = self.build_message(msg_type, "downlink", payload, st.down_seq)
        await st.ws.send(json.dumps(msg, ensure_ascii=False))
        return msg

    async def send_error(self, st: ClientState, ref_id, code: str, message: str):
        if not (isinstance(ref_id, str) and 16 <= len(ref_id) <= 64):
            ref_id = REF_ID_FALLBACK
        try:
            await self.send_msg(st, "error",
                                {"ref_id": ref_id, "code": code, "message": message})
        except Exception as e:  # noqa: BLE001 - 对端可能已断开
            log.debug("发送 error 帧失败: %s", e)

    # ---- 主连接处理 ----

    async def _handle_conn(self, ws):
        st = ClientState(ws)
        peer = getattr(ws, "remote_address", None)
        # 1. 首帧下发挑战（§3，auth.challenge）
        st.challenge = secrets.token_hex(32)
        st.challenge_deadline = time.monotonic() + self.challenge_ttl
        await self.send_msg(st, "auth.challenge",
                            {"challenge": st.challenge, "expires_in": self.challenge_ttl})
        try:
            raw = await asyncio.wait_for(ws.recv(), timeout=self.challenge_ttl + 5)
        except (asyncio.TimeoutError, websockets.ConnectionClosed):
            log.info("连接 %s 未在 %ds 内完成 hello，关闭", peer, self.challenge_ttl)
            await self._close(ws)
            return
        if not await self._handle_hello(st, raw):
            await self._close(ws)
            return
        log.info("设备 %s 认证通过（peer=%s session=%s）",
                 st.device_id, peer, st.session_id)
        # 2. 主循环
        try:
            async for raw in ws:
                if not await self._handle_frame(st, raw):
                    break
        except websockets.ConnectionClosed:
            pass
        finally:
            if st.device_id and self.online.get(st.device_id) is st:
                del self.online[st.device_id]
                log.info("设备 %s 断开，标记离线", st.device_id)
            await self._close(ws)

    @staticmethod
    async def _close(ws):
        try:
            await ws.close(code=1008)
        except websockets.ConnectionClosed:
            pass

    async def _handle_hello(self, st: ClientState, raw) -> bool:
        """验签握手。失败时发送 error 并返回 False（调用方负责关闭）。"""
        ref_id = REF_ID_FALLBACK

        async def fail(code, message):
            await self.send_error(st, ref_id, code, message)
            log.warning("握手失败(%s): %s", code, message)
            return False

        try:
            msg = json.loads(raw)
        except (json.JSONDecodeError, TypeError):
            return await fail("auth_failed", "first frame is not valid JSON")
        if not isinstance(msg, dict):
            return await fail("auth_failed", "first frame is not an object")
        if isinstance(msg.get("id"), str):
            ref_id = msg["id"]
        if parse_major(msg.get("v")) not in (None, SUPPORTED_MAJOR):
            return await fail("protocol_version", f"unsupported major version: {msg.get('v')}")
        if msg.get("type") != "device.hello":
            return await fail("auth_failed", "first frame must be device.hello")
        errs = list(self.validators["device.hello"].iter_errors(msg))
        if errs:
            return await fail("auth_failed", f"device.hello schema invalid: {errs[0].message}")
        p = msg["payload"]
        if time.monotonic() > st.challenge_deadline:
            return await fail("auth_failed", "challenge expired")
        if p["challenge"] != st.challenge:
            return await fail("auth_failed", "challenge mismatch")
        row = get_device(self.db_path, p["device_id"])
        if row is None:
            return await fail("auth_failed", "device not paired")
        if row["status"] == "revoked":
            return await fail("device_revoked", "device has been revoked")
        if row["status"] != "active":
            return await fail("auth_failed", "device pairing not approved")
        try:
            Ed25519PublicKey.from_public_bytes(bytes.fromhex(row["pubkey_hex"])).verify(
                bytes.fromhex(p["signature"]), st.challenge.encode("ascii"))
        except (InvalidSignature, ValueError):
            return await fail("auth_failed", "signature verification failed")
        # 通过 → hello_ack（按设备 scopes 收窄能力，§4）
        scopes = set(json.loads(row["scopes"]))
        accepted = [c for c in p["capabilities"] if c in scopes]
        st.device_id = p["device_id"]
        st.authed = True
        st.session_id = uuid.uuid4().hex
        st.status = "online"
        st.last_heartbeat = time.monotonic()
        st.expected_up_seq = msg["seq"] + 1
        self.online[st.device_id] = st
        await self.send_msg(st, "device.hello_ack", {
            "session_id": st.session_id,
            "server_time": now_ms(),
            "heartbeat_interval": self.heartbeat_interval,
            "accepted_capabilities": accepted,
        })
        return True

    async def _handle_frame(self, st: ClientState, raw) -> bool:
        """认证后的常规帧。返回 False 表示应关闭连接。"""
        ref_id = REF_ID_FALLBACK
        try:
            msg = json.loads(raw)
        except (json.JSONDecodeError, TypeError):
            await self.send_error(st, ref_id, "invalid_message", "frame is not valid JSON")
            return True
        if not isinstance(msg, dict):
            await self.send_error(st, ref_id, "invalid_message", "frame is not an object")
            return True
        if isinstance(msg.get("id"), str):
            ref_id = msg["id"]
        major = parse_major(msg.get("v"))
        if major is not None and major != SUPPORTED_MAJOR:
            # §2：未知 major 拒绝并回 error.protocol_version
            await self.send_error(st, ref_id, "protocol_version",
                                  f"unsupported major version: {msg.get('v')}")
            return False
        # §3 撤销即时生效：已认证设备每帧复查状态，revoked 立即断线
        if st.device_id:
            row = get_device(self.db_path, st.device_id)
            if row is None or row["status"] == "revoked":
                await self.send_error(st, ref_id, "device_revoked",
                                      "device has been revoked")
                return False
        msg_type = msg.get("type")
        validator = self.validators.get(msg_type)
        if validator is None:  # 未知类型：前向兼容，忽略并记日志
            log.warning("忽略未知消息类型: %r", msg_type)
            return True
        errs = list(validator.iter_errors(msg))
        if errs:
            await self.send_error(st, ref_id, "invalid_message",
                                  f"schema validation failed: {errs[0].message}")
            return True
        if isinstance(msg.get("seq"), int):
            if st.expected_up_seq is not None and msg["seq"] != st.expected_up_seq:
                log.warning("设备 %s 上行 seq 跳变: 期望 %s 实到 %s（仅记录不拒绝）",
                            st.device_id, st.expected_up_seq, msg["seq"])
            st.expected_up_seq = msg["seq"] + 1
        handler = getattr(self, f"_on_{msg_type.replace('.', '_')}", None)
        if handler is None:
            log.warning("类型 %s 无处理逻辑，忽略", msg_type)
            return True
        await handler(st, msg)
        return True

    # ---- 各消息处理 ----

    async def _on_device_heartbeat(self, st: ClientState, msg: dict):
        p = msg["payload"]
        if p["device_id"] != st.device_id:
            log.warning("心跳 device_id 与连接身份不符（%s != %s），忽略",
                        p["device_id"], st.device_id)
            return
        st.last_heartbeat = time.monotonic()
        st.status = p["status"]
        if st.offline_marked:
            st.offline_marked = False
            log.info("设备 %s 心跳恢复，重新标记在线", st.device_id)
        log.debug("心跳 device=%s status=%s", st.device_id, p["status"])

    async def _on_sensor_event(self, st: ClientState, msg: dict):
        p = msg["payload"]
        init_db(self.db_path)
        with db_connect(self.db_path) as conn:
            cur = conn.execute(
                "INSERT OR IGNORE INTO sensor_events"
                " (device_id,event_id,kind,occurred_at,privacy_class,summary,"
                "  evidence_ref,schema_version,received_at)"
                " VALUES(?,?,?,?,?,?,?,?,?)",
                (st.device_id, p["event_id"], p["kind"], p["occurred_at"],
                 p["privacy_class"], p["summary"], p.get("evidence_ref"),
                 p["schema_version"], now_ms()))
        if cur.rowcount == 0:
            # §6：至少一次送达 → 重复 event_id 属正常，幂等吸收
            log.info("sensor.event 重复 event_id=%s（device=%s），幂等去重跳过",
                     p["event_id"], st.device_id)
        else:
            # 脱敏日志：不打印 summary 全文
            log.info("sensor.event device=%s kind=%s event_id=%s privacy_class=%s"
                     " summary_len=%d", st.device_id, p["kind"], p["event_id"],
                     p["privacy_class"], len(p["summary"]))
        # §6 幂等闭环：无论新收还是去重命中都 ack，端侧据此停止重发
        await self.send_msg(st, "sensor.ack", {"event_ids": [p["event_id"]]})

    async def _on_sensor_result(self, st: ClientState, msg: dict):
        fut = self.pending.pop(msg["payload"]["request_id"], None)
        if fut and not fut.done():
            fut.set_result(msg["payload"])
        else:
            log.warning("收到无对应请求的 sensor.result request_id=%s，忽略",
                        msg["payload"]["request_id"])

    async def _on_action_receipt(self, st: ClientState, msg: dict):
        p = msg["payload"]
        action_id = p["action_id"]
        state = p.get("state")
        # 回执状态机：received/accepted 为中间态，记录落库；executed/failed/unknown 才 resolve 等待方。
        with db_connect(self.db_path) as conn:
            conn.execute(
                "INSERT INTO action_receipts(action_id, device_id, state, ts) VALUES(?,?,?,?)",
                (action_id, st.device_id, str(state), now_ms()))
        if state in ("executed", "failed", "unknown"):
            fut = self.pending.pop(action_id, None)
            if fut and not fut.done():
                fut.set_result(p)
        else:
            log.info("action %s 中间态回执 %s（device=%s）", action_id, state, st.device_id)

    async def _on_error(self, st: ClientState, msg: dict):
        p = msg["payload"]
        log.warning("设备 %s 回报 error ref_id=%s code=%s message=%s",
                    st.device_id, p["ref_id"], p["code"], p["message"])

    # ---- 心跳离线扫描（内存状态） ----

    async def _sweeper(self):
        interval = max(0.2, min(self.heartbeat_timeout / 3, 10.0))
        while True:
            await asyncio.sleep(interval)
            now = time.monotonic()
            for dev_id, st in list(self.online.items()):
                if not st.offline_marked and now - st.last_heartbeat > self.heartbeat_timeout:
                    st.offline_marked = True
                    st.status = "offline"
                    log.warning("设备 %s 超过 %ss 无心跳，标记离线",
                                dev_id, self.heartbeat_timeout)

    # ---- 回环管理通道（JSON lines，供 CLI query/action 使用） ----

    async def _handle_admin(self, reader: asyncio.StreamReader,
                            writer: asyncio.StreamWriter):
        try:
            line = await asyncio.wait_for(reader.readline(), timeout=10)
            req = json.loads(line.decode("utf-8"))
            resp = await self._admin_dispatch(req)
        except Exception as e:  # noqa: BLE001
            resp = {"ok": False, "error": "internal", "message": str(e)}
        writer.write((json.dumps(resp, ensure_ascii=False) + "\n").encode("utf-8"))
        await writer.drain()
        writer.close()

    async def _admin_dispatch(self, req: dict) -> dict:
        op = req.get("op")
        if op == "health":
            return {"ok": True, "online": sorted(self.online)}
        if op == "query":
            return await self._admin_query(req)
        if op == "action":
            return await self._admin_action(req)
        return {"ok": False, "error": "internal", "message": f"unknown op {op!r}"}

    def _online_state(self, device_id):
        st = self.online.get(device_id)
        if st is None:
            return None, {"ok": False, "error": "device_offline",
                          "message": f"device {device_id} 不在线"}
        return st, None

    def _check_scope(self, st, capability):
        row = get_device(self.db_path, st.device_id)
        scopes = set(json.loads(row["scopes"])) if row else set()
        if capability not in scopes:
            return {"ok": False, "error": "scope_denied",
                    "message": f"设备未授予 {capability}"}
        return None

    async def _admin_query(self, req: dict) -> dict:
        st, err = self._online_state(req.get("device", ""))
        if err:
            return err
        operation = req["operation"]
        err = self._check_scope(st, operation)
        if err:
            return err
        timeout_s = float(req.get("timeout", 15))
        request_id = uuid.uuid4().hex
        payload = {
            "request_id": request_id,
            "operation": operation,
            "filters": req.get("filters") or {},
            "max_items": int(req.get("max_items", 10)),
            "deadline": now_ms() + int(timeout_s * 1000),  # §2：请求必须带超时
        }
        fut = asyncio.get_running_loop().create_future()
        self.pending[request_id] = fut
        try:
            await self.send_msg(st, "sensor.query", payload)
            result = await asyncio.wait_for(fut, timeout=timeout_s + 5)
            return {"ok": True, "result": result}
        except asyncio.TimeoutError:
            return {"ok": False, "error": "expired",
                    "message": f"等待 sensor.result 超时（{timeout_s}s deadline）"}
        finally:
            self.pending.pop(request_id, None)

    async def _admin_action(self, req: dict) -> dict:
        st, err = self._online_state(req.get("device", ""))
        if err:
            return err
        capability = req["capability"]
        err = self._check_scope(st, capability)
        if err:
            return err
        timeout_s = float(req.get("timeout", 15))
        action_id = uuid.uuid4().hex
        idem_key = req.get("idempotency_key") or f"idem-{uuid.uuid4().hex}"
        if not (8 <= len(idem_key) <= 128):  # §6 幂等键约束
            return {"ok": False, "error": "internal",
                    "message": "idempotency_key 长度须为 8..128"}
        init_db(self.db_path)
        with db_connect(self.db_path) as conn:
            cur = conn.execute(
                "INSERT OR IGNORE INTO action_requests"
                " (device_id,idempotency_key,action_id,capability,created_at)"
                " VALUES(?,?,?,?,?)",
                (st.device_id, idem_key, action_id, capability, now_ms()))
        if cur.rowcount == 0:
            return {"ok": False, "error": "duplicate",
                    "message": f"幂等键 {idem_key} 已对该设备下发过"}
        payload = {
            "action_id": action_id,
            "capability": capability,
            "args": req.get("args") or {},
            "authorized_scope": req.get("authorized_scope") or [capability],
            "expires_at": now_ms() + int(timeout_s * 1000),
            "idempotency_key": idem_key,
        }
        fut = asyncio.get_running_loop().create_future()
        self.pending[action_id] = fut
        try:
            await self.send_msg(st, "action.request", payload)
            receipt = await asyncio.wait_for(fut, timeout=timeout_s + 5)
            return {"ok": True, "receipt": receipt, "idempotency_key": idem_key}
        except asyncio.TimeoutError:
            return {"ok": False, "error": "expired",
                    "message": f"等待 action.receipt 超时（{timeout_s}s）"}
        finally:
            self.pending.pop(action_id, None)

    # ---- 生命周期 ----

    async def start(self):
        init_db(self.db_path)
        self._ws_server = await websockets.serve(
            self._handle_conn, self.host, self.port, max_size=MAX_FRAME_BYTES)
        self._admin_server = await asyncio.start_server(
            self._handle_admin, self.admin_host, self.admin_port)
        self._sweep_task = asyncio.create_task(self._sweeper())
        self.port = self._ws_server.sockets[0].getsockname()[1]
        self.admin_port = self._admin_server.sockets[0].getsockname()[1]

    async def stop(self):
        if self._sweep_task:
            self._sweep_task.cancel()
        if self._ws_server:
            self._ws_server.close()
            await self._ws_server.wait_closed()
        if self._admin_server:
            self._admin_server.close()
            await self._admin_server.wait_closed()

    async def serve_forever(self):
        await self.start()
        log.info("dev_gateway 已启动: ws=%s:%s admin=%s:%s db=%s",
                 self.host, self.port, self.admin_host, self.admin_port, self.db_path)
        log.info("⚠️ 开发参考实现，仅供联调；生产设备面由 whale-core devices/ 取代")
        await asyncio.Future()  # run until cancelled


async def admin_call(host: str, port: int, req: dict) -> dict:
    reader, writer = await asyncio.open_connection(host, port, limit=8 * 1024 * 1024)
    writer.write((json.dumps(req, ensure_ascii=False) + "\n").encode("utf-8"))
    await writer.drain()
    line = await reader.readline()
    writer.close()
    return json.loads(line.decode("utf-8"))


# ---------------------------------------------------------------- CLI

def _parse_json_obj(s: str, what: str) -> dict:
    try:
        obj = json.loads(s)
    except json.JSONDecodeError as e:
        raise SystemExit(f"{what} 不是合法 JSON: {e}")
    if not isinstance(obj, dict):
        raise SystemExit(f"{what} 必须是 JSON object")
    return obj


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        prog="dev_gateway",
        description="whale-mobile V1 开发参考网关（非生产组件；生产由 whale-core devices/ 取代）")
    ap.add_argument("--db", default=DEFAULT_DB, help=f"SQLite 路径（默认 {DEFAULT_DB}）")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("serve", help="启动 WS 服务（默认 127.0.0.1:8765，明文 ws）")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8765)
    p.add_argument("--admin-host", default="127.0.0.1")
    p.add_argument("--admin-port", type=int, default=8766)
    p.add_argument("--heartbeat-interval", type=int, default=30)
    p.add_argument("--heartbeat-timeout", type=int, default=90,
                   help="无心跳多少秒标记离线（§2 基线 90）")
    p.add_argument("--challenge-ttl", type=int, default=30)

    p = sub.add_parser("pair-request", help="生成 6 位配对码（登记待配对设备）")
    p.add_argument("--label", required=True)
    p.add_argument("--pubkey", required=True,
                   help="设备 Ed25519 公钥（64 hex；开发简化：真实流程由手机经配对通道上报）")
    p.add_argument("--scopes", default=None, help="逗号分隔的能力上限，默认全部 V1 能力")

    p = sub.add_parser("pair-approve", help="批准配对码 → 设备转 active")
    p.add_argument("code")

    p = sub.add_parser("pair-revoke", help="撤销设备")
    p.add_argument("device_id")

    sub.add_parser("pair-list", help="列出设备")

    p = sub.add_parser("query", help="向在线设备下发 sensor.query 并等待 sensor.result")
    p.add_argument("--device", required=True)
    p.add_argument("--operation", required=True, choices=CAPABILITIES)
    p.add_argument("--filters", default="{}")
    p.add_argument("--max-items", type=int, default=10)
    p.add_argument("--timeout", type=float, default=15)
    p.add_argument("--admin-host", default="127.0.0.1")
    p.add_argument("--admin-port", type=int, default=8766)

    p = sub.add_parser("action", help="向在线设备下发 action.request 并等待 action.receipt")
    p.add_argument("--device", required=True)
    p.add_argument("--capability", required=True, choices=CAPABILITIES)
    p.add_argument("--args", default="{}")
    p.add_argument("--idempotency-key", default=None)
    p.add_argument("--timeout", type=float, default=15)
    p.add_argument("--admin-host", default="127.0.0.1")
    p.add_argument("--admin-port", type=int, default=8766)

    args = ap.parse_args(argv)

    if args.cmd == "serve":
        logging.basicConfig(level=logging.INFO,
                            format="%(asctime)s %(levelname)s %(name)s %(message)s")
        gw = Gateway(host=args.host, port=args.port,
                     admin_host=args.admin_host, admin_port=args.admin_port,
                     db_path=args.db,
                     heartbeat_interval=args.heartbeat_interval,
                     heartbeat_timeout=args.heartbeat_timeout,
                     challenge_ttl=args.challenge_ttl)
        try:
            asyncio.run(gw.serve_forever())
        except KeyboardInterrupt:
            pass
        return 0

    if args.cmd == "pair-request":
        scopes = args.scopes.split(",") if args.scopes else None
        try:
            code, device_id = pair_request(args.db, args.label, args.pubkey, scopes)
        except ValueError as e:
            raise SystemExit(f"pair-request 失败: {e}")
        print(f"配对码: {code}（{PAIR_CODE_TTL_S}s 内有效）")
        print(f"device_id: {device_id}")
        print(f"请在有效期内执行: python3 server/dev_gateway.py pair-approve {code}")
        return 0

    if args.cmd == "pair-approve":
        try:
            device_id = pair_approve(args.db, args.code)
        except ValueError as e:
            raise SystemExit(f"pair-approve 失败: {e}")
        print(f"已批准，device active: {device_id}")
        return 0

    if args.cmd == "pair-revoke":
        try:
            pair_revoke(args.db, args.device_id)
        except ValueError as e:
            raise SystemExit(f"pair-revoke 失败: {e}")
        print(f"已撤销: {args.device_id}")
        return 0

    if args.cmd == "pair-list":
        init_db(args.db)
        with db_connect(args.db) as conn:
            rows = conn.execute(
                "SELECT device_id,label,status,scopes,created_at FROM devices"
                " ORDER BY created_at").fetchall()
        for r in rows:
            print(f"{r['device_id']}  {r['status']:<8}  {r['label']}  {r['scopes']}")
        if not rows:
            print("（无设备）")
        return 0

    if args.cmd in ("query", "action"):
        if args.cmd == "query":
            req = {"op": "query", "device": args.device, "operation": args.operation,
                   "filters": _parse_json_obj(args.filters, "--filters"),
                   "max_items": args.max_items, "timeout": args.timeout}
        else:
            req = {"op": "action", "device": args.device, "capability": args.capability,
                   "args": _parse_json_obj(args.args, "--args"),
                   "idempotency_key": args.idempotency_key, "timeout": args.timeout}
        resp = asyncio.run(admin_call(args.admin_host, args.admin_port, req))
        print(json.dumps(resp, ensure_ascii=False, indent=2))
        return 0 if resp.get("ok") else 1

    return 2


if __name__ == "__main__":
    sys.exit(main())
