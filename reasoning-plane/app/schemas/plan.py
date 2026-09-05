"""The RemediationPlan contract — must mirror the Java control plane exactly.

This is the inter-plane wire format. The reasoning plane may only ever produce actions
drawn from :class:`ActionType`; the control plane re-validates against its own copy of this
enum before anything executes. There is deliberately no free-form / RUN_COMMAND variant.

The Java side (Jackson) uses camelCase field names, so every model here serializes with a
camelCase alias generator and is dumped ``by_alias=True`` on the wire.
"""

from __future__ import annotations

from enum import Enum

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class _Wire(BaseModel):
    """Base for wire contracts: camelCase aliases, reject unknown fields."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )


class ActionType(str, Enum):
    """Closed set of remediation actions. Mirrors io.nexusops.remediation.ActionType."""

    RESTART_CONTAINER = "RESTART_CONTAINER"
    SCALE_SERVICE = "SCALE_SERVICE"
    ROLLBACK_IMAGE = "ROLLBACK_IMAGE"
    CLEAR_CACHE = "CLEAR_CACHE"
    ROTATE_LOG = "ROTATE_LOG"
    NO_OP = "NO_OP"


class BlastRadius(str, Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


class Environment(str, Enum):
    STAGING = "STAGING"
    PROD = "PROD"


class PlanAction(_Wire):
    """A single typed action. Extra fields are forbidden — no smuggling a command in."""

    type: ActionType
    target_ref: str = Field(min_length=1)
    parameters: dict[str, object] = Field(default_factory=dict)
    rationale: str = ""


class Evidence(_Wire):
    tool_name: str
    observation_ref: str


class RemediationPlan(_Wire):
    """Structured output of a diagnosis. Serialized to the control plane as-is."""

    incident_id: str
    correlation_id: str
    hypothesis: str = ""
    confidence: float = Field(ge=0.0, le=1.0, default=0.0)
    actions: list[PlanAction] = Field(min_length=1)
    evidence: list[Evidence] = Field(default_factory=list)
    estimated_blast_radius: BlastRadius = BlastRadius.LOW
    escalate: bool = False

    def is_noop(self) -> bool:
        return len(self.actions) == 1 and self.actions[0].type == ActionType.NO_OP


class DiagnosisRequest(_Wire):
    """Incoming diagnosis request from the control plane. No credentials — ever."""

    incident_id: str
    correlation_id: str
    service_ref: str
    environment: Environment = Environment.PROD
    signal: str = ""


def noop_plan(
    incident_id: str,
    correlation_id: str,
    service_ref: str,
    reason: str,
    escalate: bool = True,
) -> RemediationPlan:
    """A safe fallback plan: do nothing, optionally escalate."""
    return RemediationPlan(
        incident_id=incident_id,
        correlation_id=correlation_id,
        hypothesis=reason,
        confidence=0.0,
        actions=[PlanAction(type=ActionType.NO_OP, target_ref=service_ref, rationale=reason)],
        evidence=[],
        estimated_blast_radius=BlastRadius.LOW,
        escalate=escalate,
    )
