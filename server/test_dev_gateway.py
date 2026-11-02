#!/usr/bin/env python3
"""dev_gateway 集成测试：用 websockets 客户端模拟 whale-mobile 设备。

覆盖：配对 → hello(Ed25519 验签) → 心跳 → sensor.event 上行（含 event_id 去重）
→ query/result 往返 → action/receipt 往返 → 伪造签名被拒 → 撤销后连接被拒
→ 未知 major 版本被拒 → schema 校验失败回 error → 心跳超时标记离线。
Ed25519 密钥对在测试内现生成，不落盘。

运行：python3 -m pytest server/test_dev_gateway.py -v
"""
import asyncio
import hashlib
import json
import logging
import os
import sys
import uuid

import pytest
import websockets
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dev_gateway as dg  # noqa: E402

logging.basicConfig(level=logging.WARNING)


# ---------------------------------------------------------------- helpers

def make_device_keypair():
    priv = Ed25519PrivateKey.generate()
    pub_bytes = priv.public_key().public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw)
    device_id = hashlib.sha256(pub_bytes).digest()[:16].hex()
    return priv, pub_bytes.hex(), device_id


class DeviceSim:
    """模拟手机端：维护上行 seq，构造合法信封。"""

    def __init__(self, priv=None, device_id=None):
        self.priv = priv
        self.device_id = device_id
        self.seq = 0
        self.ws = None

    async def connect(self, port):
        self.ws = await websockets.connect(f"ws://127.0.0.1:{port}")
        frame = json.loads(await self.ws.recv())
        assert frame["type"] == "auth.challenge", frame
        assert frame["dir"] == "downlink"
        assert len(frame["payload"]["challenge"]) == 64
        assert frame["payload"]["expires_in"] > 0
        return frame["payload"]["challenge"]

    def envelope(self, msg_type, payload, v="1.0"):
        self.seq += 1
        return {"v": v, "id": uuid.uuid4().hex, "type": msg_type,
                "ts": dg.now_ms(), "seq": self.seq, "dir": "uplink",
                "payload": payload}

    async def send_hello(self, challenge, priv=None, device_id=None,
                         capabilities=("screen.history.search",
                                       "phone.notification.post"), v="1.0"):
        priv = priv or self.priv
        device_id = device_id or self.device_id
        sig = priv.sign(challenge.encode("ascii")).hex()
        payload = {"protocol_version": v, "device_id": device_id,
                   "capabilities": list(capabilities), "app_version": "0.1.0-test",
                   "signature": sig, "challenge": challenge}
        await self.ws.send(json.dumps(self.envelope("device.hello", payload, v=v)))

    async def recv(self):
        return json.loads(await self.ws.recv())

    async def close(self):
        if self.ws:
            await self.ws.close()


async def start_gateway(db_path, **kw):
    gw = dg.Gateway(host="127.0.0.1", port=0, admin_host="127.0.0.1",
                    admin_port=0, db_path=str(db_path), **kw)
    await gw.start()
    return gw


async def collect_until_close(ws):
    """读帧直到连接关闭，返回帧列表。"""
    frames = []
    try:
        async for raw in ws:
            frames.append(json.loads(raw))
    except websockets.ConnectionClosed:
        pass
    return frames


async def wait_for(pred, timeout=3.0):
    deadline = asyncio.get_running_loop().time() + timeout
    while asyncio.get_running_loop().time() < deadline:
        if pred():
            return True
        await asyncio.sleep(0.05)
    return pred()


def event_payload(event_id=None, kind="screen.memo.created"):
    return {"event_id": event_id or f"evt-{uuid.uuid4().hex}",
            "kind": kind, "occurred_at": dg.now_ms(),
            "privacy_class": "owner_private",
            "summary": "一段不应出现在日志里的敏感摘要内容",
            "schema_version": "1.0"}


# ---------------------------------------------------------------- tests

def test_full_pairing_handshake_heartbeat_event_query_action(tmp_path):
    asyncio.run(_full_flow(tmp_path))


async def _full_flow(tmp_path):
    db = tmp_path / "g.db"
    priv, pub_hex, device_id = make_device_keypair()
    # --- 配对（开发简化流程）---
    code, did = dg.pair_request(str(db), "test-phone", pub_hex)
    assert did == device_id and len(code) == 6 and code.isdigit()
    assert dg.get_device(str(db), device_id)["status"] == "pending"
    assert dg.pair_approve(str(db), code) == device_id
    assert dg.get_device(str(db), device_id)["status"] == "active"

    gw = await start_gateway(db)
    sim = DeviceSim(priv, device_id)
    try:
        # --- hello 验签 ---
        challenge = await sim.connect(gw.port)
        await sim.send_hello(challenge)
        ack = await sim.recv()
        assert ack["type"] == "device.hello_ack"
        assert ack["payload"]["heartbeat_interval"] == 30
        assert ack["payload"]["accepted_capabilities"] == [
            "screen.history.search", "phone.notification.post"]
        assert device_id in gw.online

        # --- 心跳 ---
        await sim.ws.send(json.dumps(sim.envelope("device.heartbeat", {
            "device_id": device_id, "observed_at": dg.now_ms(),
            "status": "online", "battery": 87})))
        assert await wait_for(lambda: gw.online[device_id].status == "online")

        # --- sensor.event 上行 + event_id 幂等去重 ---
        eid = f"evt-{uuid.uuid4().hex}"
        for _ in range(2):  # 同一 event_id 发两次（§6 至少一次送达）
            await sim.ws.send(json.dumps(
                sim.envelope("sensor.event", event_payload(event_id=eid))))
        with dg.db_connect(str(db)) as conn:
            assert await wait_for(lambda: conn.execute(
                "SELECT COUNT(*) c FROM sensor_events"
                " WHERE device_id=? AND event_id=?",
                (device_id, eid)).fetchone()["c"] == 1)
        # §6 幂等闭环：服务端对每次上行都回 sensor.ack（新收与去重命中各一）
        for _ in range(2):
            ack = await sim.recv()
            assert ack["type"] == "sensor.ack"
            assert ack["payload"]["event_ids"] == [eid]

        # --- sensor.query / sensor.result 往返 ---
        qtask = asyncio.create_task(dg.admin_call(
            "127.0.0.1", gw.admin_port,
            {"op": "query", "device": device_id,
             "operation": "screen.history.search",
             "filters": {"text": "whale"}, "max_items": 5, "timeout": 10}))
        query = await sim.recv()
        assert query["type"] == "sensor.query"
        assert query["payload"]["operation"] == "screen.history.search"
        assert query["payload"]["filters"] == {"text": "whale"}
        assert query["payload"]["deadline"] > dg.now_ms()
        await sim.ws.send(json.dumps(sim.envelope("sensor.result", {
            "request_id": query["payload"]["request_id"], "status": "ok",
            "data": [{"title": "whale note"}], "truncated": False,
            "source": "screenmemo.local"})))
        qresp = await qtask
        assert qresp["ok"] and qresp["result"]["status"] == "ok"
        assert qresp["result"]["data"] == [{"title": "whale note"}]

        # --- action.request / action.receipt 往返 ---
        atask = asyncio.create_task(dg.admin_call(
            "127.0.0.1", gw.admin_port,
            {"op": "action", "device": device_id,
             "capability": "phone.notification.post",
             "args": {"title": "hi", "body": "from core"}, "timeout": 10}))
        act = await sim.recv()
        assert act["type"] == "action.request"
        ap = act["payload"]
        assert ap["capability"] == "phone.notification.post"
        assert 8 <= len(ap["idempotency_key"]) <= 128  # §6 幂等键
        assert ap["authorized_scope"] == ["phone.notification.post"]
        await sim.ws.send(json.dumps(sim.envelope("action.receipt", {
            "action_id": ap["action_id"], "state": "executed",
            "executed_at": dg.now_ms()})))
        aresp = await atask
        assert aresp["ok"] and aresp["receipt"]["state"] == "executed"
    finally:
        await sim.close()
        await gw.stop()


def test_forged_signature_rejected(tmp_path):
    asyncio.run(_forged(tmp_path))


async def _forged(tmp_path):
    db = tmp_path / "g.db"
    priv, pub_hex, device_id = make_device_keypair()
    dg.pair_approve(str(db), dg.pair_request(str(db), "victim", pub_hex)[0])
    attacker_priv, _, _ = make_device_keypair()  # 另一把私钥伪造签名
    gw = await start_gateway(db)
    sim = DeviceSim(priv, device_id)
    try:
        challenge = await sim.connect(gw.port)
        await sim.send_hello(challenge, priv=attacker_priv)
        frames = await collect_until_close(sim.ws)
        assert frames[-1]["type"] == "error"
        assert frames[-1]["payload"]["code"] == "auth_failed"
        assert device_id not in gw.online
    finally:
        await gw.stop()


def test_revoked_device_rejected(tmp_path):
    asyncio.run(_revoked(tmp_path))


async def _revoked(tmp_path):
    db = tmp_path / "g.db"
    priv, pub_hex, device_id = make_device_keypair()
    dg.pair_approve(str(db), dg.pair_request(str(db), "old-phone", pub_hex)[0])
    dg.pair_revoke(str(db), device_id)
    gw = await start_gateway(db)
    sim = DeviceSim(priv, device_id)
    try:
        await sim.send_hello(await sim.connect(gw.port))
        frames = await collect_until_close(sim.ws)
        assert frames[-1]["payload"]["code"] == "device_revoked"
    finally:
        await gw.stop()


def test_unpaired_device_rejected(tmp_path):
    asyncio.run(_unpaired(tmp_path))


async def _unpaired(tmp_path):
    gw = await start_gateway(tmp_path / "g.db")
    priv, _, device_id = make_device_keypair()  # 从未配对
    sim = DeviceSim(priv, device_id)
    try:
        await sim.send_hello(await sim.connect(gw.port))
        frames = await collect_until_close(sim.ws)
        assert frames[-1]["payload"]["code"] == "auth_failed"
    finally:
        await gw.stop()


def test_unknown_major_version_rejected(tmp_path):
    asyncio.run(_bad_major(tmp_path))


async def _bad_major(tmp_path):
    db = tmp_path / "g.db"
    priv, pub_hex, device_id = make_device_keypair()
    dg.pair_approve(str(db), dg.pair_request(str(db), "p", pub_hex)[0])
    gw = await start_gateway(db)
    sim = DeviceSim(priv, device_id)
    try:
        challenge = await sim.connect(gw.port)
        await sim.send_hello(challenge, v="2.0")  # §2：未知 major 必须拒绝
        frames = await collect_until_close(sim.ws)
        assert frames[-1]["payload"]["code"] == "protocol_version"
    finally:
        await gw.stop()


def test_non_hello_first_frame_rejected(tmp_path):
    asyncio.run(_non_hello(tmp_path))


async def _non_hello(tmp_path):
    gw = await start_gateway(tmp_path / "g.db")
    sim = DeviceSim()
    try:
        await sim.connect(gw.port)
        await sim.ws.send(json.dumps(sim.envelope(
            "device.heartbeat", {"device_id": "0" * 32,
                                 "observed_at": dg.now_ms(), "status": "online"})))
        frames = await collect_until_close(sim.ws)
        assert frames[-1]["payload"]["code"] == "auth_failed"
    finally:
        await gw.stop()


def test_schema_violation_gets_error_frame(tmp_path):
    asyncio.run(_schema_violation(tmp_path))


async def _schema_violation(tmp_path):
    db = tmp_path / "g.db"
    priv, pub_hex, device_id = make_device_keypair()
    dg.pair_approve(str(db), dg.pair_request(str(db), "p", pub_hex)[0])
    gw = await start_gateway(db)
    sim = DeviceSim(priv, device_id)
    try:
        await sim.send_hello(await sim.connect(gw.port))
        assert (await sim.recv())["type"] == "device.hello_ack"
        bad = sim.envelope("sensor.event", event_payload())
        del bad["payload"]["event_id"]  # 缺幂等键 → schema 必须拒绝
        await sim.ws.send(json.dumps(bad))
        err = await sim.recv()
        assert err["type"] == "error"
        assert err["payload"]["ref_id"] == bad["id"]
        with dg.db_connect(str(db)) as conn:
            assert conn.execute("SELECT COUNT(*) c FROM sensor_events"
                                ).fetchone()["c"] == 0
    finally:
        await sim.close()
        await gw.stop()


def test_heartbeat_timeout_marks_offline(tmp_path):
    asyncio.run(_offline(tmp_path))


async def _offline(tmp_path):
    db = tmp_path / "g.db"
    priv, pub_hex, device_id = make_device_keypair()
    dg.pair_approve(str(db), dg.pair_request(str(db), "p", pub_hex)[0])
    gw = await start_gateway(db, heartbeat_timeout=1)
    sim = DeviceSim(priv, device_id)
    try:
        await sim.send_hello(await sim.connect(gw.port))
        assert (await sim.recv())["type"] == "device.hello_ack"
        st = gw.online[device_id]
        assert await wait_for(lambda: st.offline_marked, timeout=5)  # 1s 无心跳 → 离线
        # 心跳恢复 → 重新在线
        await sim.ws.send(json.dumps(sim.envelope("device.heartbeat", {
            "device_id": device_id, "observed_at": dg.now_ms(), "status": "online"})))
        assert await wait_for(lambda: not st.offline_marked)
    finally:
        await sim.close()
        await gw.stop()


def test_action_idempotency_key_deduplicated(tmp_path):
    asyncio.run(_idem(tmp_path))


async def _idem(tmp_path):
    db = tmp_path / "g.db"
    priv, pub_hex, device_id = make_device_keypair()
    dg.pair_approve(str(db), dg.pair_request(str(db), "p", pub_hex)[0])
    gw = await start_gateway(db)
    sim = DeviceSim(priv, device_id)
    try:
        await sim.send_hello(await sim.connect(gw.port))
        assert (await sim.recv())["type"] == "device.hello_ack"
        key = f"idem-{uuid.uuid4().hex}"

        async def one_action():
            return await dg.admin_call("127.0.0.1", gw.admin_port, {
                "op": "action", "device": device_id,
                "capability": "phone.notification.post",
                "args": {"title": "t"}, "idempotency_key": key, "timeout": 10})

        t1 = asyncio.create_task(one_action())
        act = await sim.recv()
        await sim.ws.send(json.dumps(sim.envelope("action.receipt", {
            "action_id": act["payload"]["action_id"], "state": "executed",
            "executed_at": dg.now_ms()})))
        assert (await t1)["ok"]
        # 同幂等键再次下发 → 服务端按 (device, idempotency_key) 拒绝
        t2 = await one_action()
        assert not t2["ok"] and t2["error"] == "duplicate"
    finally:
        await sim.close()
        await gw.stop()


def test_revoke_kicks_online_connection(tmp_path):
    """§3 撤销即时生效：已在线连接在下一帧被关闭并收到 device_revoked。"""
    asyncio.run(_revoke_kick(tmp_path))


async def _revoke_kick(tmp_path):
    db = tmp_path / "g.db"
    priv, pub_hex, device_id = make_device_keypair()
    dg.pair_approve(str(db), dg.pair_request(str(db), "p", pub_hex)[0])
    gw = await start_gateway(db)
    sim = DeviceSim(priv, device_id)
    try:
        await sim.send_hello(await sim.connect(gw.port))
        assert (await sim.recv())["type"] == "device.hello_ack"
        assert device_id in gw.online
        dg.pair_revoke(str(db), device_id)
        # 撤销后第一帧触发踢线
        await sim.ws.send(json.dumps(sim.envelope("device.heartbeat", {
            "device_id": device_id, "observed_at": dg.now_ms(), "status": "online"})))
        err = await sim.recv()
        assert err["type"] == "error" and err["payload"]["code"] == "device_revoked"
        assert await wait_for(lambda: device_id not in gw.online)
    finally:
        await sim.close()
        await gw.stop()
