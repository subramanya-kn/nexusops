"""Trivial demo target service — an incident subject the platform monitors and remediates.

One parameterised image runs as payment-svc / inventory-svc / gateway / worker. Behaviour is
driven by env + runtime toggles so a scenario can inject a crash loop, an OOM leak, or a log
flood without bespoke images. Read-only /health is what the control-plane registry probes.
"""

from __future__ import annotations

import os
import threading
import time

from fastapi import FastAPI, Response

SERVICE_NAME = os.getenv("SERVICE_NAME", "demo-svc")
# When true the process exits shortly after start, producing a crash loop under a restart policy.
CRASH_ON_START = os.getenv("CRASH_ON_START", "false").lower() == "true"
CRASH_AFTER_S = float(os.getenv("CRASH_AFTER_S", "5"))

app = FastAPI(title=f"{SERVICE_NAME} (demo target)")

# Mutable runtime state — flipped via toggle endpoints during a scenario.
_state = {"healthy": True, "leak": [], "logflood": False}


def _maybe_crash_on_start() -> None:
    if CRASH_ON_START:
        time.sleep(CRASH_AFTER_S)
        os._exit(1)  # hard exit → container restarts → crash loop


@app.on_event("startup")
def _startup() -> None:
    threading.Thread(target=_maybe_crash_on_start, daemon=True).start()
    threading.Thread(target=_logflood_loop, daemon=True).start()


def _logflood_loop() -> None:
    while True:
        if _state["logflood"]:
            print(f"[{SERVICE_NAME}] noisy log line " + "x" * 512, flush=True)
        time.sleep(0.01)


@app.get("/health")
def health() -> Response:
    """Liveness/readiness. Returns 503 once tripped unhealthy so the registry sees the incident."""
    return Response(status_code=200 if _state["healthy"] else 503)


@app.get("/")
def root() -> dict[str, str]:
    return {"service": SERVICE_NAME, "status": "ok" if _state["healthy"] else "unhealthy"}


# --- Chaos toggles (used by eval/chaos, never by the agent) ---


@app.post("/toggle/unhealthy")
def toggle_unhealthy() -> dict[str, bool]:
    _state["healthy"] = False
    return {"healthy": _state["healthy"]}


@app.post("/toggle/healthy")
def toggle_healthy() -> dict[str, bool]:
    _state["healthy"] = True
    return {"healthy": _state["healthy"]}


@app.post("/toggle/leak")
def toggle_leak() -> dict[str, int]:
    """Allocate ~50MB per call — drives an OOM incident under a container memory limit."""
    _state["leak"].append(bytearray(50 * 1024 * 1024))
    return {"chunks": len(_state["leak"])}


@app.post("/toggle/logflood")
def toggle_logflood() -> dict[str, bool]:
    _state["logflood"] = not _state["logflood"]
    return {"logflood": _state["logflood"]}
