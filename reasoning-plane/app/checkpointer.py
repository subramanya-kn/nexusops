"""Checkpointer factory. Postgres-backed when configured (diagnoses survive restart and can
be resumed/inspected), else an in-memory MemorySaver so the stack runs with no database."""

from __future__ import annotations

from typing import Any

import structlog

from app.config import settings

log = structlog.get_logger()


def build_checkpointer() -> Any:
    if settings.checkpointer == "postgres" and settings.postgres_dsn:
        try:
            from langgraph.checkpoint.postgres import PostgresSaver

            saver = PostgresSaver.from_conn_string(settings.postgres_dsn)
            saver.setup()
            log.info("checkpointer.postgres", dsn="configured")
            return saver
        except Exception as exc:  # pragma: no cover - optional dependency / DB absent
            log.warning("checkpointer.postgres_failed", error=str(exc))

    from langgraph.checkpoint.memory import MemorySaver

    log.info("checkpointer.memory")
    return MemorySaver()
