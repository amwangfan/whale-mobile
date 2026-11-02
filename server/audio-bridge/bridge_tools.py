#!/usr/bin/env python3
"""Allowlisted shell-style actions for Nova phone AI. No arbitrary shell.

Enable with BRIDGE_TOOLS=1. Destructive ops are NOT in the whitelist.
Never logs secrets (DASHSCOPE/AGENTSCOPE keys).
"""
from __future__ import annotations

import json
import os
import re
import socket
import subprocess
import time
from pathlib import Path
from typing import Any, Callable

BRIDGE_DIR = Path(__file__).resolve().parent
WORKSPACE = Path(os.environ.get("BRIDGE_WORKSPACE", str(BRIDGE_DIR.parent))).expanduser()
PIDF = BRIDGE_DIR / "bridge.pid"
LOGF = BRIDGE_DIR / "bridge.log"
DEFAULT_TIMEOUT = float(os.environ.get("BRIDGE_TOOL_TIMEOUT", "10"))

# SSH / ring defaults (Tailscale Nova)
NOVA_SSH_HOST = os.environ.get("NOVA_SSH_HOST", "127.0.0.1").strip()
NOVA_SSH_USER = os.environ.get("NOVA_SSH_USER", "root").strip()
NOVA_SSH_KEY = os.environ.get("NOVA_SSH_KEY", "~/.ssh/android").strip()
NOVA_RING_SCRIPT = os.environ.get(
    "NOVA_RING_SCRIPT", "/data/local/tmp/nova-fake-call/ring.sh"
).strip()
BRIDGE_PUBLIC_HOST = os.environ.get("BRIDGE_PUBLIC_HOST", "127.0.0.1").strip()
BRIDGE_PORT = int(os.environ.get("BRIDGE_PORT", "18799"))


def tools_enabled() -> bool:
    v = os.environ.get("BRIDGE_TOOLS", "").strip().lower()
    return v in ("1", "true", "yes", "on")


def _redact(s: str) -> str:
    out = s
    for env_name in ("DASHSCOPE_API_KEY", "AGENTSCOPE_API_KEY"):
        key = os.environ.get(env_name, "")
        if key and key in out:
            out = out.replace(key, "***")
    # generic dashscope-style key prefixes / long tokens
    out = re.sub(r"(sk" r"-[A-Za-z0-9]{8,})", "***", out)
    out = re.sub(r"(Bearer\s+)[A-Za-z0-9._\-]{8,}", r"\1***", out, flags=re.I)
    return out


def _log(msg: str) -> None:
    print(f"[tools] {msg}", flush=True)


def _run(
    argv: list[str],
    *,
    timeout: float = DEFAULT_TIMEOUT,
    cwd: str | None = None,
    env: dict[str, str] | None = None,
) -> tuple[int, str, str]:
    try:
        p = subprocess.run(
            argv,
            capture_output=True,
            text=True,
            timeout=timeout,
            cwd=cwd,
            env=env,
        )
        return p.returncode, p.stdout or "", p.stderr or ""
    except subprocess.TimeoutExpired:
        return 124, "", f"timeout after {timeout}s"
    except Exception as e:
        return 1, "", str(e)


# ---------- whitelist handlers (no free-form shell) ----------

def tool_what_time(_args: dict[str, Any] | None = None) -> str:
    code, out, err = _run(
        ["date", "+%Y-%m-%d %H:%M:%S %Z"],
        timeout=5,
        env={**os.environ, "TZ": "Asia/Shanghai"},
    )
    if code != 0:
        return f"获取时间失败: {_redact(err or out)[:120]}"
    return f"现在是 Asia/Shanghai {out.strip()}"


def tool_list_workspace(_args: dict[str, Any] | None = None) -> str:
    try:
        names = sorted(
            p.name for p in WORKSPACE.iterdir() if not p.name.startswith(".")
        )
    except Exception as e:
        return f"列出失败: {e}"
    # keep spoken reply short
    preview = "、".join(names[:18])
    extra = f" 等共{len(names)}项" if len(names) > 18 else f" 共{len(names)}项"
    return f"工作区顶层：{preview}{extra}"


def tool_tail_bridge_log(_args: dict[str, Any] | None = None) -> str:
    if not LOGF.is_file():
        return "桥日志文件不存在"
    try:
        # efficient last ~20 lines
        code, out, err = _run(["tail", "-n", "20", str(LOGF)], timeout=5)
        if code != 0:
            return f"读日志失败: {_redact(err or out)[:80]}"
        text = _redact(out).strip()
        if not text:
            return "桥日志为空"
        # compress for voice: last 3 non-empty lines summary
        lines = [ln for ln in text.splitlines() if ln.strip()][-5:]
        joined = " | ".join(lines)[:280]
        return f"桥日志末尾：{joined}"
    except Exception as e:
        return f"读日志失败: {e}"


def _pid_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def _port_listening(port: int) -> bool:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.settimeout(0.5)
            return s.connect_ex(("127.0.0.1", port)) == 0
    except OSError:
        return False


def _read_mode_from_proc(pid: int) -> str:
    try:
        cmdline = Path(f"/proc/{pid}/cmdline").read_bytes().replace(b"\x00", b" ").decode(
            "utf-8", "replace"
        )
        m = re.search(r"--mode[= ](\w+)", cmdline)
        if m:
            return m.group(1)
    except Exception:
        pass
    return os.environ.get("BRIDGE_MODE", "?").strip() or "?"


def _proc_env_flag(pid: int, name: str) -> str | None:
    try:
        raw = Path(f"/proc/{pid}/environ").read_bytes()
        for item in raw.split(b"\x00"):
            if not item.startswith(name.encode() + b"="):
                continue
            return item.decode("utf-8", "replace").split("=", 1)[1]
    except Exception:
        return None
    return None


def tool_bridge_status(_args: dict[str, Any] | None = None) -> str:
    pid = None
    if PIDF.is_file():
        try:
            pid = int(PIDF.read_text().strip())
        except Exception:
            pid = None
    alive = bool(pid and _pid_alive(pid))
    listening = _port_listening(BRIDGE_PORT)
    mode = _read_mode_from_proc(pid) if alive and pid else (
        os.environ.get("BRIDGE_MODE", "?").strip() or "?"
    )
    if alive and pid:
        flag = (_proc_env_flag(pid, "BRIDGE_TOOLS") or "").strip().lower()
        tools_on = flag in ("1", "true", "yes", "on")
    else:
        tools_on = tools_enabled()
    tools = "开" if tools_on else "关"
    bits = [
        f"模式{mode}",
        f"端口{BRIDGE_PORT}{'通' if listening else '不通'}",
        f"进程{'在' if alive else '不在'}" + (f" pid{pid}" if pid else ""),
        f"白名单工具{tools}",
    ]
    return "桥状态：" + "，".join(bits)



def tool_ring_fake_call(args: dict[str, Any] | None = None) -> str:
    args = args or {}
    # Fixed display; never take free-form shell. Number/name default ***
    number = str(args.get("number") or "***")[:32]
    name = str(args.get("name") or "***")[:32]
    # sanitize: digits / * / + / Chinese letters only-ish
    if not re.fullmatch(r"[\d*+#A-Za-z\u4e00-\u9fff ·_-]{1,32}", number):
        number = "***"
    if not re.fullmatch(r"[\d*+#A-Za-z\u4e00-\u9fff ·_-]{1,32}", name):
        name = "***"
    host = BRIDGE_PUBLIC_HOST
    port = str(BRIDGE_PORT)
    remote = (
        f"export PATH=/system/bin:/system/xbin:$PATH; "
        f"sh {NOVA_RING_SCRIPT} {json.dumps(number)} {json.dumps(name)} "
        f"{json.dumps(host)} {json.dumps(port)}"
    )
    # Use argv list only — no bash -c with model text
    argv = [
        "ssh",
        "-i",
        NOVA_SSH_KEY,
        "-o",
        "BatchMode=yes",
        "-o",
        "ConnectTimeout=8",
        "-o",
        "StrictHostKeyChecking=no",
        f"{NOVA_SSH_USER}@{NOVA_SSH_HOST}",
        remote,
    ]
    code, out, err = _run(argv, timeout=max(DEFAULT_TIMEOUT, 12.0))
    if code != 0:
        return f"VoIP 来电失败: {_redact((err or out)[:160])}"
    return f"已向 Nova 发起 VoIP 来电（显示 {name}/{number}）"


def tool_restart_bridge(_args: dict[str, Any] | None = None) -> str:
    """Optional: restart start.sh same mode. May drop the active call."""
    mode = os.environ.get("BRIDGE_MODE", "omni").strip() or "omni"
    stop = BRIDGE_DIR / "stop.sh"
    start = BRIDGE_DIR / "start.sh"
    if not stop.is_file() or not start.is_file():
        return "找不到 start/stop 脚本"
    env = os.environ.copy()
    env["BRIDGE_MODE"] = mode
    env["BRIDGE_TOOLS"] = "1"
    code1, _, err1 = _run(["bash", str(stop)], timeout=8, cwd=str(BRIDGE_DIR), env=env)
    time.sleep(0.5)
    code2, out2, err2 = _run(
        ["bash", str(start)], timeout=10, cwd=str(BRIDGE_DIR), env=env
    )
    if code2 != 0:
        return f"重启失败: {_redact((err2 or out2 or err1)[:160])}"
    return f"桥已按模式 {mode} 重启（通话可能已断开）"


HANDLERS: dict[str, Callable[[dict[str, Any] | None], str]] = {
    "bridge_status": tool_bridge_status,
    "ring_fake_call": tool_ring_fake_call,
    "list_workspace": tool_list_workspace,
    "tail_bridge_log": tool_tail_bridge_log,
    "what_time": tool_what_time,
    "restart_bridge": tool_restart_bridge,
}

# Omni / LLM tool schemas (no free-form args that enable shell)
TOOL_SCHEMAS: list[dict[str, Any]] = [
    {
        "type": "function",
        "function": {
            "name": "bridge_status",
            "description": "查询音频桥状态：模式、进程、端口是否在听。用户说查桥状态/桥还在吗时调用。",
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "ring_fake_call",
            "description": "向 Nova 手机发起 VoIP 来电铃声。用户说打 VoIP 来电/再响一次/ring时调用。",
            "parameters": {
                "type": "object",
                "properties": {
                    "name": {"type": "string", "description": "来电显示名，默认***"},
                    "number": {"type": "string", "description": "来电号码，默认***"},
                },
                "required": [],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "list_workspace",
            "description": "列出工作区（BRIDGE_WORKSPACE）顶层目录名（不递归）。用户说看工作区/列目录时调用。",
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "tail_bridge_log",
            "description": "查看桥日志末尾摘要（已脱敏）。用户说看桥日志时调用。",
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "what_time",
            "description": "查询 Asia/Shanghai 当前时间。用户说现在几点/几点了/what time时调用。",
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "restart_bridge",
            "description": "重启音频桥（会断开当前通话）。仅当用户明确说重启桥时调用。",
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
]

# Chinese / English voice intent → tool name (hybrid keyword router)
_INTENT_RULES: list[tuple[re.Pattern[str], str]] = [
    (re.compile(r"(现在几点|几点了|几点钟|what\s*time|当前时间|报时)", re.I), "what_time"),
    (re.compile(r"(查(一下)?桥(的)?状态|桥(还)?在吗|桥状态|bridge\s*status)", re.I), "bridge_status"),
    # 兼容旧说法「假来电」作为触发词；默认展示文案用「VoIP 来电」
    (re.compile(r"(VoIP\s*来电|打(个)?\s*VoIP\s*来电|再响(一次)?|响铃|ring(\s*fake)?|假来电|打(个)?假(的)?来电)", re.I), "ring_fake_call"),
    (re.compile(r"(列(一下)?(工作区|目录)|看(一下)?工作区|list\s*workspace)", re.I), "list_workspace"),
    (re.compile(r"(桥日志|看(一下)?日志|tail.*log|bridge\s*log)", re.I), "tail_bridge_log"),
    (re.compile(r"(重启桥|重启音频桥|restart\s*bridge)", re.I), "restart_bridge"),
]


def match_intent(text: str) -> str | None:
    """Return whitelist tool name if ASR/transcript matches a voice trigger."""
    if not text or not text.strip():
        return None
    t = text.strip()
    for pat, name in _INTENT_RULES:
        if pat.search(t):
            return name
    return None


def execute(name: str, args: dict[str, Any] | None = None) -> tuple[bool, str]:
    """Run an allowlisted tool. Returns (ok, spoken_result). Logs name+ok/fail only."""
    if not tools_enabled():
        _log(f"{name} skip disabled")
        return False, "工具未启用"
    handler = HANDLERS.get(name)
    if handler is None:
        _log(f"{name} fail unknown")
        return False, f"未知工具 {name}"
    try:
        result = handler(args)
        ok = not result.startswith(("获取时间失败", "列出失败", "读日志失败", "VoIP 来电失败", "重启失败", "找不到"))
        # softer ok: any non-exception result from handler is ok unless explicit fail prefix
        fail_prefixes = (
            "获取时间失败",
            "列出失败",
            "读日志失败",
            "VoIP 来电失败",
            "重启失败",
            "找不到",
            "桥日志文件不存在",
            "未知工具",
            "工具未启用",
        )
        ok = not any(result.startswith(p) for p in fail_prefixes)
        _log(f"{name} {'ok' if ok else 'fail'}")
        return ok, result
    except Exception as e:
        _log(f"{name} fail")
        return False, f"执行失败: {_redact(str(e))[:120]}"


def tools_instructions_addon() -> str:
    return (
        "你可以调用白名单工具帮用户：查桥状态、打 VoIP 来电、看时间、列工作区、看桥日志、重启桥。"
        "需要真实信息时先调用对应工具，再用一两句中文口语把结果告诉用户。"
    )


def speakable_from_tool(result: str) -> str:
    """Trim tool output for phone TTS (keep short)."""
    s = (result or "").strip().replace("\n", " ")
    if len(s) > 80:
        s = s[:78] + "…"
    return s


if __name__ == "__main__":
    # Offline smoke: call each tool by name via text intent
    os.environ.setdefault("BRIDGE_TOOLS", "1")
    samples = [
        "现在几点",
        "查一下桥状态",
        "列一下工作区",
        "看桥日志",
    ]
    for s in samples:
        name = match_intent(s)
        print(f"INTENT {s!r} -> {name}")
        if name:
            ok, out = execute(name)
            print(f"  ok={ok} out={out[:200]}")
