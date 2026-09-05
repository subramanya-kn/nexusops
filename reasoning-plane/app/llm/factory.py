"""Select the LLM provider from settings. Defaults to the offline mock."""

from __future__ import annotations

from app.config import settings
from app.llm.base import LLMProvider
from app.llm.mock import MockProvider
from app.observability.cost import CostMeter


def build_provider(cost: CostMeter) -> LLMProvider:
    if settings.llm_provider == "anthropic" and settings.anthropic_api_key:
        from app.llm.anthropic_provider import AnthropicProvider

        return AnthropicProvider(
            cost=cost,
            api_key=settings.anthropic_api_key,
            model=settings.anthropic_model,
            temperature=settings.llm_temperature,
        )
    return MockProvider(cost=cost)
