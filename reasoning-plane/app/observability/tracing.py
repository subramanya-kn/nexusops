"""OpenTelemetry setup. Trace context is propagated from the Java control plane over the
HTTP boundary (traceparent header), so one trace spans both planes."""

from __future__ import annotations

import typing

import structlog
from fastapi import FastAPI

from app.config import settings

_log = structlog.get_logger()


def setup_observability(app: FastAPI) -> None:
    """Wire structured logging + OTel tracing. Degrades gracefully with no collector."""
    structlog.configure(
        processors=[
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso"),
            structlog.processors.JSONRenderer(),
        ]
    )

    if not settings.otel_endpoint:
        _log.info("otel.disabled", reason="no NEXUS_OTEL_ENDPOINT configured")
        return

    try:
        from opentelemetry import trace
        from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
        from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
        from opentelemetry.sdk.resources import Resource
        from opentelemetry.sdk.trace import TracerProvider
        from opentelemetry.sdk.trace.export import BatchSpanProcessor

        resource = Resource.create({"service.name": settings.service_name})
        provider = TracerProvider(resource=resource)
        provider.add_span_processor(
            BatchSpanProcessor(OTLPSpanExporter(endpoint=settings.otel_endpoint))
        )
        trace.set_tracer_provider(provider)
        FastAPIInstrumentor.instrument_app(app)
        _log.info("otel.enabled", endpoint=settings.otel_endpoint)
    except Exception as exc:  # pragma: no cover - optional dependency / collector absent
        _log.warning("otel.setup_failed", error=str(exc))


def get_logger() -> structlog.BoundLogger:
    return typing.cast(structlog.BoundLogger, structlog.get_logger())
