"""Runtime configuration for the FlowPilot Agent Service.

All settings are environment driven (12-factor). Field names map to upper-case
environment variables, e.g. ``LLM_API_KEY``, ``OPENAI_API_KEY``,
``GITHUB_TOKEN``, ``RABBITMQ_URL``, ``POSTGRES_DSN``.
"""

from __future__ import annotations

from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # --- service -----------------------------------------------------
    service_name: str = "flowpilot-agent-service"
    environment: str = "local"
    version: str = "1.0.0"
    log_level: str = "INFO"
    log_json: bool = True

    # --- messaging (RabbitMQ) ----------------------------------------
    messaging_enabled: bool = True
    rabbitmq_url: str = "amqp://guest:guest@localhost:5672/"
    rabbitmq_exchange: str = "flowpilot.events"
    rabbitmq_dead_letter_exchange: str = "flowpilot.dead-letter"
    rabbitmq_dead_letter_queue: str = "flowpilot.dead-letter.queue"
    rabbitmq_dead_letter_routing_key: str = "flowpilot.dead-letter"
    rabbitmq_execute_queue: str = "agent.execute.queue"
    rabbitmq_control_queue: str = "agent.control.queue"
    rabbitmq_events_queue: str = "backend.agent-events.queue"
    rabbitmq_prefetch: int = 10
    rabbitmq_max_redeliveries: int = 3
    rabbitmq_reconnect_delay_seconds: float = 3.0

    # --- persistence (PostgreSQL / LangGraph checkpoints) ------------
    persistence_enabled: bool = True
    # When PostgreSQL is unreachable the service refuses to start instead of
    # silently degrading to the in memory implementations: a degraded agent
    # loses checkpoints and idempotency, which is worse than a crash loop.
    persistence_required: bool = True
    postgres_dsn: str = "postgresql://flowpilot:flowpilot@127.0.0.1:5432/flowpilot_agent"
    postgres_pool_min_size: int = 1
    postgres_pool_max_size: int = 5
    postgres_connect_timeout: int = 5

    # --- outbox (transactional result events) -------------------------
    outbox_poll_interval_seconds: float = 1.0
    outbox_batch_size: int = 20
    outbox_max_retry_seconds: int = 60
    # A row that stayed in PUBLISHING longer than this is considered lost
    # (the publisher crashed) and is returned to PENDING.
    outbox_publishing_timeout_seconds: int = 300

    # --- idempotency journal (Redis) -----------------------------------
    # An empty value disables Redis and falls back to the in memory journal.
    redis_url: str = "redis://localhost:6379/0"
    idempotency_journal_ttl_seconds: int = 7 * 24 * 3600

    # --- LLM provider ------------------------------------------------
    # When neither LLM_API_KEY nor OPENAI_API_KEY is present the service
    # falls back to the deterministic offline FakeChatModel so that local
    # development, CI and the demo all work without any credential.
    llm_api_key: str | None = None
    openai_api_key: str | None = None
    llm_base_url: str = "https://api.openai.com/v1"
    llm_model: str = "gpt-4o-mini"
    llm_temperature: float = 0.0
    llm_timeout_seconds: float = 60.0

    # --- GitHub tool -------------------------------------------------
    github_token: str | None = None
    github_timeout_seconds: float = 20.0
    github_max_content_chars: int = 4000
    github_max_items: int = 20

    # --- protocol schema files ---------------------------------------
    protocol_schema_dir: str | None = None

    # --- default execution budgets ------------------------------------
    default_max_iterations: int = 12
    default_max_tool_calls: int = 20
    default_max_tokens: int = 12000
    default_timeout_seconds: int = 300

    # --- internal API --------------------------------------------------
    internal_api_token: str | None = Field(
        default=None,
        description="Optional bearer token protecting /internal endpoints.",
    )

    # --- OpenTelemetry --------------------------------------------------
    # Without an endpoint the spans are only created locally (no exporter).
    otel_enabled: bool = True
    otel_exporter_otlp_endpoint: str = ""
    otel_service_name: str = "flowpilot-agent-service"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Process wide settings singleton."""
    return Settings()
