"""Test setup for audio-bridge: import server.py offline (no Omni network / keys)."""
from __future__ import annotations

import sys
import types
from pathlib import Path

BRIDGE_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = BRIDGE_DIR.parents[2]
for p in (BRIDGE_DIR, REPO_ROOT / "whale-core" / "src"):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

# omni_realtime imports websocket-client at module level; tests never open a socket to Omni.
try:  # pragma: no cover - depends on env
    import websocket  # noqa: F401
except ImportError:  # pragma: no cover
    sys.modules["websocket"] = types.ModuleType("websocket")
