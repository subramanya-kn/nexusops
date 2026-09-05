"""Routers: /health (liveness), /diagnose (the agent), /internal/info (metadata).

/diagnose is fully async and rate-limited per client. The heavy graph run is offloaded to a
worker thread so the event loop is never blocked."""

from __future__ import annotations

import asyncio

import structlog
from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel

from app.api.ratelimit import RateLimiter
from app.config import settings
from app.schemas.plan import DiagnosisRequest, RemediationPlan
from app.service import DiagnosisService

log = structlog.get_logger()

router = APIRouter()
_rate_limiter = RateLimiter(settings.rate_limit_per_minute)


def get_service(request: Request) -> DiagnosisService:
    service = request.app.state.diagnosis_service
    assert isinstance(service, DiagnosisService)
    return service


class DiagnoseResponse(BaseModel):
    plan: RemediationPlan
    cost: dict[str, object]


@router.get("/health")
async def health() -> dict[str, str]:
    return {"status": "UP", "service": settings.service_name, "provider": settings.llm_provider}


@router.get("/internal/info")
async def info() -> dict[str, object]:
    return {
        "service": settings.service_name,
        "provider": settings.llm_provider,
        "max_hops": settings.max_hops,
        "read_only": True,
        "holds_credentials": False,
    }


@router.post("/diagnose", response_model=DiagnoseResponse)
async def diagnose(
    request: Request,
    body: DiagnosisRequest,
    service: DiagnosisService = Depends(get_service),  # noqa: B008 — standard FastAPI DI idiom
) -> DiagnoseResponse:
    client = request.client.host if request.client else "unknown"
    if not _rate_limiter.allow(client):
        raise HTTPException(status_code=429, detail="rate limit exceeded")

    # Offload the (sync) graph run to a thread so the event loop stays responsive.
    plan, cost = await asyncio.to_thread(service.diagnose, body)
    log.info(
        "diagnosis.complete",
        incident_id=body.incident_id,
        correlation_id=body.correlation_id,
        actions=[a.type.value for a in plan.actions],
        escalate=plan.escalate,
        **{f"cost_{k}": v for k, v in cost.items() if not isinstance(v, dict)},
    )
    return DiagnoseResponse(plan=plan, cost=cost)
