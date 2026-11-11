"""LLM provider abstraction.

The agent service must be runnable without any credential (local development,
CI, offline demo). Therefore the provider is chosen at runtime:

* ``LLM_API_KEY`` or ``OPENAI_API_KEY`` present -> the real, OpenAI compatible
  chat completion endpoint is used.
* otherwise -> the deterministic :class:`~app.llm.fake_model.FakeChatModel`
  which returns structured JSON matching the analysis schema.

Both providers implement the same tiny :class:`ChatModel` protocol so the
graphs never talk to a vendor SDK directly.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, Protocol, Sequence, runtime_checkable

from app.config.settings import Settings
from app.runtime.errors import BusinessError, TransientError

#: Task keys understood by the deterministic fake model.
TASK_REPOSITORY_ANALYSIS = "repository_analysis"
TASK_DEPENDENCY_ANALYSIS = "dependency_analysis"
TASK_REPORT_REVIEW = "report_review"

_SYSTEM_PROMPT_PREFIX = "TASK_KEY="


@dataclass(frozen=True)
class ChatMessage:
    role: str
    content: str


@dataclass(frozen=True)
class ChatResult:
    content: str
    input_tokens: int = 0
    output_tokens: int = 0

    def as_json(self) -> dict[str, Any]:
        return parse_json_object(self.content)


@runtime_checkable
class ChatModel(Protocol):
    async def ainvoke(
        self, messages: Sequence[ChatMessage], *, temperature: float = 0.0
    ) -> ChatResult: ...


def build_messages(
    task_key: str, instructions: str, content: str
) -> list[ChatMessage]:
    """Build the chat message list for a task.

    The task key is embedded in the system message so that the deterministic
    fake model knows which structured schema it has to produce.
    """
    return [
        ChatMessage(role="system", content=f"{_SYSTEM_PROMPT_PREFIX}{task_key}\n{instructions}"),
        ChatMessage(role="user", content=content),
    ]


def parse_json_object(content: str) -> dict[str, Any]:
    """Parse a JSON object returned by the model."""
    text = (content or "").strip()
    if text.startswith("```"):
        # tolerate markdown fences
        text = text.strip("`")
        if text.lower().startswith("json"):
            text = text[4:]
        text = text.strip()
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        raise BusinessError(
            "model response is not valid JSON",
            error_code="AG-LLM-INVALID-RESPONSE-001",
            diagnostics={"error": str(exc), "preview": text[:200]},
        ) from exc
    if not isinstance(data, dict):
        raise BusinessError(
            "model response is not a JSON object",
            error_code="AG-LLM-INVALID-RESPONSE-002",
        )
    return data


def estimate_tokens(text: str) -> int:
    """Deterministic token estimation (4 characters per token)."""
    if not text:
        return 0
    return max(1, len(text) // 4)


class OpenAICompatibleChatModel:
    """Minimal OpenAI compatible chat completion client (httpx only)."""

    def __init__(
        self,
        *,
        api_key: str,
        base_url: str,
        model: str,
        timeout: float = 60.0,
        client: Any = None,
    ) -> None:
        self.api_key = api_key
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.timeout = timeout
        self._client = client
        self._owns_client = client is None

    async def _get_client(self):
        if self._client is None:
            import httpx

            self._client = httpx.AsyncClient(timeout=self.timeout)
        return self._client

    async def aclose(self) -> None:
        if self._client is not None and self._owns_client:
            await self._client.aclose()
            self._client = None

    async def ainvoke(
        self, messages: Sequence[ChatMessage], *, temperature: float = 0.0
    ) -> ChatResult:
        client = await self._get_client()
        payload = {
            "model": self.model,
            "temperature": temperature,
            "messages": [{"role": m.role, "content": m.content} for m in messages],
        }
        try:
            response = await client.post(
                f"{self.base_url}/chat/completions",
                json=payload,
                headers={
                    "Authorization": f"Bearer {self.api_key}",
                    "Content-Type": "application/json",
                },
            )
        except Exception as exc:  # network level failure
            raise TransientError(
                f"model request failed: {type(exc).__name__}",
                error_code="LLM_REQUEST_FAILED",
                diagnostics={"error": str(exc)},
            ) from exc
        if response.status_code == 429:
            raise TransientError(
                "model provider rate limited the request",
                error_code="LLM_RATE_LIMITED",
            )
        if response.status_code >= 400:
            raise TransientError(
                f"model provider returned HTTP {response.status_code}",
                error_code="LLM_REQUEST_FAILED",
                diagnostics={"status": response.status_code},
            )
        data = response.json()
        choices = data.get("choices") or []
        content = ""
        if choices:
            content = (choices[0].get("message") or {}).get("content") or ""
        usage = data.get("usage") or {}
        return ChatResult(
            content=content,
            input_tokens=int(usage.get("prompt_tokens") or 0),
            output_tokens=int(usage.get("completion_tokens") or 0),
        )


def build_chat_model(settings: Settings) -> ChatModel:
    """Return the configured chat model (real provider or deterministic fake)."""
    api_key = settings.llm_api_key or settings.openai_api_key
    if api_key:
        return OpenAICompatibleChatModel(
            api_key=api_key,
            base_url=settings.llm_base_url,
            model=settings.llm_model,
            timeout=settings.llm_timeout_seconds,
        )
    from app.llm.fake_model import FakeChatModel

    return FakeChatModel()
