"""The known service set the reasoning plane may target. Mirrors the control-plane
registry — a plan targeting anything outside this set is rejected server-side (both here
and again in the control plane)."""

from __future__ import annotations

KNOWN_SERVICES: tuple[str, ...] = (
    "payment-svc",
    "inventory-svc",
    "gateway",
    "worker",
    "cache",
)
