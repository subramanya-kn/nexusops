"""Reasoning-plane FastAPI application.

This service holds NO credentials and has NO write path to infrastructure. It exposes a
diagnosis endpoint that runs a read-only ReAct agent and returns a structured, validated
RemediationPlan for the control plane to gate and (maybe) execute.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.routes import router
from app.checkpointer import build_checkpointer
from app.observability.tracing import setup_observability
from app.service import DiagnosisService


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    app.state.diagnosis_service = DiagnosisService(checkpointer=build_checkpointer())
    yield


def create_app() -> FastAPI:
    app = FastAPI(
        title="NexusOps Reasoning Plane",
        version="0.1.0",
        description="Read-only ReAct diagnostic agent. Proposes; never disposes.",
        lifespan=lifespan,
    )
    setup_observability(app)
    app.include_router(router)
    return app


app = create_app()
