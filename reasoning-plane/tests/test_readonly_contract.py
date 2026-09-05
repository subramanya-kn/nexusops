"""Invariant #1: the reasoning plane has no write surface. The toolbox and infra clients
expose reads only — this test fails if a mutating method is ever added."""

from __future__ import annotations

from app.tools.infra_client import HttpInfraClient, SimulatedInfraClient
from app.tools.registry import Toolbox

_FORBIDDEN = ("restart", "scale", "rollback", "delete", "exec", "run", "write", "mutate",
              "stop", "start", "remove", "kill", "create")


def _public_methods(cls: type) -> list[str]:
    return [name for name in dir(cls) if not name.startswith("_") and callable(getattr(cls, name))]


def test_toolbox_has_no_mutating_methods() -> None:
    for name in _public_methods(Toolbox):
        assert not any(name.startswith(verb) for verb in _FORBIDDEN), f"mutating method: {name}"


def test_infra_clients_have_no_mutating_methods() -> None:
    for cls in (SimulatedInfraClient, HttpInfraClient):
        for name in _public_methods(cls):
            assert name.startswith("get_"), f"non-read method on {cls.__name__}: {name}"
