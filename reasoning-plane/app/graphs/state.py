"""Typed graph state with reducers. ``observations`` uses an append reducer so each
investigate hop adds to the trace rather than overwriting it."""

from __future__ import annotations

from operator import add
from typing import Annotated, TypedDict


class Observation(TypedDict):
    tool: str
    id: str
    raw: str
    content: str
    injection_flags: list[str]


class DiagnosisState(TypedDict, total=False):
    incident_id: str
    correlation_id: str
    service_ref: str
    environment: str
    signal: str
    dependencies: list[str]
    observations: Annotated[list[Observation], add]
    hops: int
    plan: dict[str, object] | None
    validation_errors: list[str]
    retried: bool
    escalate: bool
    stop: bool
