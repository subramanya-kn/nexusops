"""Shared fixtures: scenario builders + an offline DiagnosisService."""

from __future__ import annotations

import pytest

from app.schemas.plan import DiagnosisRequest, Environment
from app.service import DiagnosisService
from app.tools.infra_client import (
    ContainerStatus,
    Deploy,
    ResourceMetrics,
    Scenario,
    SimulatedInfraClient,
)


def make_service(scenario: Scenario) -> DiagnosisService:
    return DiagnosisService(infra_client=SimulatedInfraClient(scenario))


def request_for(service_ref: str, signal: str) -> DiagnosisRequest:
    return DiagnosisRequest(
        incident_id="inc-1",
        correlation_id="corr-1",
        service_ref=service_ref,
        environment=Environment.PROD,
        signal=signal,
    )


@pytest.fixture
def oom_scenario() -> Scenario:
    return Scenario(
        service_ref="payment-svc",
        status=ContainerStatus(ref="payment-svc", running=False, restart_count=1,
                               last_exit_code=137, oom_killed=True),
        metrics=ResourceMetrics(ref="payment-svc", cpu_pct=40.0, mem_pct=99.0, mem_limit_mb=128),
        logs=["allocating buffers", "OOMKilled: memory limit exceeded"],
        healthy=False,
    )


@pytest.fixture
def healthy_scenario() -> Scenario:
    return Scenario(
        service_ref="payment-svc",
        status=ContainerStatus(ref="payment-svc", running=True, restart_count=0,
                               last_exit_code=0, oom_killed=False),
        metrics=ResourceMetrics(ref="payment-svc", cpu_pct=10.0, mem_pct=30.0, mem_limit_mb=128),
        logs=["request handled 200", "request handled 200"],
        healthy=True,
    )


@pytest.fixture
def crashloop_with_deploy() -> Scenario:
    return Scenario(
        service_ref="inventory-svc",
        status=ContainerStatus(ref="inventory-svc", running=False, restart_count=8,
                               last_exit_code=1, oom_killed=False),
        metrics=ResourceMetrics(ref="inventory-svc", cpu_pct=5.0, mem_pct=20.0, mem_limit_mb=256),
        logs=["startup failed: bad build"],
        healthy=False,
        deploys=[Deploy(ref="inventory-svc", image="nexusops/demo-svc:bad", at="2026-09-04T10:00")],
    )
