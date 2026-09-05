"""Pydantic settings loaded from environment. No hardcoded config, no secrets in code."""

from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="NEXUS_", env_file=".env", extra="ignore")

    # LLM provider: "mock" (default, deterministic, offline) or "anthropic".
    llm_provider: str = "mock"
    anthropic_api_key: str | None = None
    anthropic_model: str = "claude-sonnet-5"
    llm_temperature: float = 0.0
    llm_max_retries: int = 3

    # ReAct loop bounds. Headroom above the 6-tool investigation so reaching the cap is a
    # genuine safety trip (runaway), not normal completion.
    max_hops: int = 8
    max_log_lines: int = 100  # bound tool output — protect context window + cost

    # Where the read-only tools read their (simulated) infrastructure state from.
    # In compose this points at the control plane's read API / demo services.
    infra_source: str = "simulated"  # "simulated" | "control-plane"
    control_plane_base_url: str = "http://control-plane:8080"

    # Optional Postgres checkpointer.
    checkpointer: str = "memory"  # "memory" | "postgres"
    postgres_dsn: str | None = None

    # Observability.
    otel_endpoint: str | None = None
    service_name: str = "reasoning-plane"

    # Rate limiting for /diagnose.
    rate_limit_per_minute: int = 30


settings = Settings()
