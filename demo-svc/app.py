"""Trivial demo target service — an incident subject the platform monitors and remediates.

One parameterised image runs as payment-svc / inventory-svc / gateway / worker. Behaviour is
driven by env + runtime toggles so a scenario can inject a crash loop, an OOM leak, or a log
flood without bespoke images. Read-only /health is what the control-plane registry probes.

Also exposes the read-only introspection endpoints the reasoning plane's HttpInfraClient
expects (/status, /logs, /metrics-json, /deploys, /deps) so `make demo` against a live
compose stack sees real, self-reported facts rather than a 404. The restart counter is
persisted to a local file rather than kept in memory: a crash-on-start under a restart
policy relaunches the *same* container (same writable layer), so the file survives across
restarts even though the Python process does not.
"""

from __future__ import annotations

import collections
import os
import threading
import time
from pathlib import Path

from fastapi import FastAPI, Response

SERVICE_NAME = os.getenv("SERVICE_NAME", "demo-svc")
IMAGE_TAG = os.getenv("IMAGE_TAG", "nexusops/demo-svc:v1")
# When true the process exits shortly after start, producing a crash loop under a restart policy.
CRASH_ON_START = os.getenv("CRASH_ON_START", "false").lower() == "true"
CRASH_AFTER_S = float(os.getenv("CRASH_AFTER_S", "5"))
# Optional: report a recent deploy for the crashloop-with-deploy scenario.
DEPLOY_IMAGE = os.getenv("DEPLOY_IMAGE", "")
DEPLOY_AT = os.getenv("DEPLOY_AT", "")
# Optional: report a dependency edge for the dependency-cascade scenario, e.g. "cache".
DEPENDS_ON = [d for d in os.getenv("DEPENDS_ON", "").split(",") if d]

_RESTART_COUNT_FILE = Path("/tmp/nexusops-restart-count")  # noqa: S108 - container-local, not shared
_MEM_LIMIT_MB = 128

app = FastAPI(title=f"{SERVICE_NAME} (demo target)")

# Mutable runtime state — flipped via toggle endpoints during a scenario.
_state = {"healthy": True, "leak": [], "logflood": False, "last_exit_code": 0}
_log_ring: collections.deque[str] = collections.deque(maxlen=200)


def _read_restart_count() -> int:
    try:
        return int(_RESTART_COUNT_FILE.read_text().strip())
    except (FileNotFoundError, ValueError):
        return 0


def _bump_restart_count() -> int:
    count = _read_restart_count() + 1
    _RESTART_COUNT_FILE.write_text(str(count))
    return count


def _maybe_crash_on_start() -> None:
    if CRASH_ON_START:
        time.sleep(CRASH_AFTER_S)
        count = _bump_restart_count()
        _log_ring.append(f"[{SERVICE_NAME}] startup failed (attempt {count})")
        _state["last_exit_code"] = 1
        os._exit(1)  # hard exit -> container restarts -> crash loop


@app.on_event("startup")
def _startup() -> None:
    threading.Thread(target=_maybe_crash_on_start, daemon=True).start()
    threading.Thread(target=_logflood_loop, daemon=True).start()
    _log_ring.append(f"[{SERVICE_NAME}] started, image={IMAGE_TAG}")


def _logflood_loop() -> None:
    while True:
        if _state["logflood"]:
            _log_ring.append(f"[{SERVICE_NAME}] noisy log line " + "x" * 64)
        time.sleep(0.01)


def _mem_usage_bytes() -> int:
    """Real cgroup memory usage where available (v2, then v1); falls back to the leak
    buffer size in dev environments without a cgroup memory controller (e.g. some
    container runtimes on macOS)."""
    for path in ("/sys/fs/cgroup/memory.current", "/sys/fs/cgroup/memory/memory.usage_in_bytes"):
        try:
            return int(Path(path).read_text().strip())
        except (FileNotFoundError, ValueError, PermissionError):
            continue
    return sum(len(chunk) for chunk in _state["leak"])


@app.get("/health")
def health() -> Response:
    """Liveness/readiness. Returns 503 once tripped unhealthy so the registry sees the incident."""
    return Response(status_code=200 if _state["healthy"] else 503)


@app.get("/")
def root() -> dict[str, str]:
    return {"service": SERVICE_NAME, "status": "ok" if _state["healthy"] else "unhealthy"}


# --- Read-only introspection (consumed by the reasoning plane's HttpInfraClient) ---


@app.get("/status")
def status() -> dict[str, object]:
    restart_count = _read_restart_count()
    mem_pct = (_mem_usage_bytes() / (_MEM_LIMIT_MB * 1024 * 1024)) * 100.0
    oom_killed = mem_pct >= 95.0
    return {
        "running": _state["healthy"] or not CRASH_ON_START,
        "restartCount": restart_count,
        "lastExitCode": _state["last_exit_code"],
        "oomKilled": oom_killed,
        "image": IMAGE_TAG,
    }


@app.get("/logs")
def logs(tail: int = 50) -> dict[str, list[str]]:
    lines = list(_log_ring)[-max(1, tail):]
    return {"lines": lines}


@app.get("/metrics-json")
def metrics_json() -> dict[str, float | int]:
    mem_bytes = _mem_usage_bytes()
    mem_pct = (mem_bytes / (_MEM_LIMIT_MB * 1024 * 1024)) * 100.0
    return {"cpuPct": 0.0, "memPct": round(min(mem_pct, 100.0), 1), "memLimitMb": _MEM_LIMIT_MB}


@app.get("/deploys")
def deploys() -> dict[str, list[dict[str, str]]]:
    if not DEPLOY_IMAGE:
        return {"deploys": []}
    return {"deploys": [{"image": DEPLOY_IMAGE, "at": DEPLOY_AT}]}


@app.get("/deps")
def deps() -> dict[str, list[str]]:
    return {SERVICE_NAME: DEPENDS_ON} if DEPENDS_ON else {}


# --- Chaos toggles (used by eval/chaos and make demo, never by the agent) ---


@app.post("/toggle/unhealthy")
def toggle_unhealthy() -> dict[str, bool]:
    _state["healthy"] = False
    _log_ring.append(f"[{SERVICE_NAME}] toggled unhealthy")
    return {"healthy": _state["healthy"]}


@app.post("/toggle/healthy")
def toggle_healthy() -> dict[str, bool]:
    _state["healthy"] = True
    _log_ring.append(f"[{SERVICE_NAME}] toggled healthy")
    return {"healthy": _state["healthy"]}


@app.post("/toggle/leak")
def toggle_leak() -> dict[str, int]:
    """Allocate ~50MB per call — drives an OOM incident under a container memory limit."""
    _state["leak"].append(bytearray(50 * 1024 * 1024))
    _log_ring.append(f"[{SERVICE_NAME}] memory allocated, chunks={len(_state['leak'])}")
    return {"chunks": len(_state["leak"])}


@app.post("/toggle/logflood")
def toggle_logflood() -> dict[str, bool]:
    _state["logflood"] = not _state["logflood"]
    return {"logflood": _state["logflood"]}


@app.post("/toggle/reset")
def toggle_reset() -> dict[str, bool]:
    """Clear all toggled state and the persisted restart counter -- used between demo runs."""
    _state["healthy"] = True
    _state["leak"] = []
    _state["logflood"] = False
    _state["last_exit_code"] = 0
    _RESTART_COUNT_FILE.unlink(missing_ok=True)
    return {"reset": True}
