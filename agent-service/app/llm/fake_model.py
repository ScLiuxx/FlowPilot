"""Deterministic offline chat model.

``FakeChatModel`` replaces a real LLM whenever no API key is configured. It
returns structured JSON that matches the schema the graphs expect, so the
whole service (and its test suite) runs offline, deterministically and without
any credential.

The response is derived from a stable digest of the prompt: the same prompt
always produces the same answer, different prompts produce different answers.
"""

from __future__ import annotations

import hashlib
import json
import re
from typing import Any, Sequence

from app.llm.provider import (
    TASK_DEPENDENCY_ANALYSIS,
    TASK_REPORT_REVIEW,
    TASK_REPOSITORY_ANALYSIS,
    ChatMessage,
    ChatResult,
    estimate_tokens,
)

_TASK_KEY_RE = re.compile(r"TASK_KEY=([a-z_]+)")
_REPO_RE = re.compile(r"Repository:\s*([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)")
_INT_RE = re.compile(r"([A-Za-z][A-Za-z ]{2,20}):\s*(-?\d+)")


class FakeChatModel:
    """Deterministic stand-in for a real chat model."""

    model_name = "flowpilot-fake-chat-model"

    async def ainvoke(
        self, messages: Sequence[ChatMessage], *, temperature: float = 0.0
    ) -> ChatResult:
        system = next((m.content for m in messages if m.role == "system"), "")
        user = "\n".join(m.content for m in messages if m.role == "user")
        task = self._task_key(system)
        payload = self._build_payload(task, user)
        content = json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True)
        return ChatResult(
            content=content,
            input_tokens=estimate_tokens(system) + estimate_tokens(user),
            output_tokens=estimate_tokens(content),
        )

    # -- internals -------------------------------------------------------
    @staticmethod
    def _task_key(system_message: str) -> str:
        match = _TASK_KEY_RE.search(system_message or "")
        return match.group(1) if match else TASK_REPOSITORY_ANALYSIS

    @staticmethod
    def _digest(text: str) -> str:
        return hashlib.sha256((text or "").encode("utf-8")).hexdigest()

    @staticmethod
    def _facts(user_content: str) -> dict[str, Any]:
        repo_match = _REPO_RE.search(user_content or "")
        facts: dict[str, Any] = {
            "repository": repo_match.group(1) if repo_match else "unknown/unknown",
            "metrics": {},
        }
        for name, value in _INT_RE.findall(user_content or ""):
            key = name.strip().lower().replace(" ", "")
            try:
                facts["metrics"][key] = int(value)
            except ValueError:  # pragma: no cover - defensive
                continue
        return facts

    def _build_payload(self, task: str, user_content: str) -> dict[str, Any]:
        digest = self._digest(user_content)
        facts = self._facts(user_content)
        if task == TASK_DEPENDENCY_ANALYSIS:
            return self._dependency_payload(facts, digest)
        if task == TASK_REPORT_REVIEW:
            return self._review_payload(facts, digest)
        return self._repository_payload(facts, digest)

    @staticmethod
    def _repository_payload(facts: dict[str, Any], digest: str) -> dict[str, Any]:
        repository = facts["repository"]
        metrics = facts["metrics"]
        open_issues = metrics.get("openissues", 0)
        recent_commits = metrics.get("recentcommits", 0)
        stars = metrics.get("stars", 0)
        risk = "LOW"
        if open_issues > 100 or recent_commits == 0:
            risk = "MEDIUM"
        if open_issues > 500 and recent_commits == 0:
            risk = "HIGH"
        return {
            "architectureAnalysis": (
                f"Repository {repository} exposes a public GitHub surface with "
                f"{stars} stars. The observed module layout is consistent with a "
                "single repository build (no submodule fan-out detected)."
            ),
            "maintenanceAnalysis": (
                f"The project shows {recent_commits} recent commits and "
                f"{open_issues} open issues, which indicates an "
                f"{'actively' if recent_commits else 'rarely'} maintained codebase."
            ),
            "riskAnalysis": (
                f"Risk level {risk}. No critical security or compliance blocker was "
                f"detected in the inspected public metadata (analysis {digest[:8]})."
            ),
            "recommendation": (
                f"Continue the technical evaluation of {repository} and request the "
                "missing internal documentation before signing off."
            ),
            "riskLevel": risk,
        }

    @staticmethod
    def _dependency_payload(facts: dict[str, Any], digest: str) -> dict[str, Any]:
        metrics = facts["metrics"]
        total = metrics.get("totaldependencies", 0)
        unpinned = metrics.get("unpinneddependencies", 0)
        high = metrics.get("highriskdependencies", 0)
        risk = "LOW"
        if high > 0:
            risk = "HIGH"
        elif unpinned > 0:
            risk = "MEDIUM"
        findings = [
            {
                "type": "dependency_count",
                "severity": "INFO",
                "detail": f"{total} third party dependencies were inspected.",
            },
            {
                "type": "unpinned_version",
                "severity": "MEDIUM" if unpinned else "INFO",
                "detail": f"{unpinned} dependencies are not pinned to an exact version.",
            },
            {
                "type": "insecure_source",
                "severity": "HIGH" if high else "INFO",
                "detail": f"{high} dependencies are fetched over an insecure channel.",
            },
        ]
        return {
            "summary": (
                f"{total} dependencies were analysed for repository "
                f"{facts['repository']} (analysis {digest[:8]})."
            ),
            "findings": findings,
            "riskLevel": risk,
        }

    @staticmethod
    def _review_payload(facts: dict[str, Any], digest: str) -> dict[str, Any]:
        return {
            "decision": "APPROVED",
            "comments": (
                f"The report for {facts['repository']} was reviewed automatically "
                f"(review {digest[:8]})."
            ),
        }
