"""Report assembly.

Turns the structured analysis produced by the graphs into the report payload
that is carried by ``AgentCompleted.result`` and rendered as Markdown for the
Java backend.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

_SECTION_KEYS = (
    ("architectureAnalysis", "architecture", "Architecture Analysis"),
    ("maintenanceAnalysis", "maintenance", "Maintenance Analysis"),
    ("riskAnalysis", "risk", "Risk Analysis"),
    ("recommendation", "recommendation", "Recommendation"),
)


def build_report(
    *,
    repository: str,
    analysis: dict[str, Any],
    facts: dict[str, Any] | None = None,
    review: dict[str, Any] | None = None,
    report_type: str = "GITHUB_DUE_DILIGENCE",
    generated_at: datetime | None = None,
) -> dict[str, Any]:
    """Assemble the structured report."""
    facts = facts or {}
    analysis = analysis or {}
    generated = generated_at or datetime.now(tz=timezone.utc)

    sections: list[dict[str, str]] = []
    for key, section_key, title in _SECTION_KEYS:
        value = analysis.get(key)
        if value:
            sections.append({"key": section_key, "title": title, "content": str(value)})

    summary = str(
        analysis.get("summary")
        or analysis.get("recommendation")
        or f"Technical due diligence report for {repository}."
    )

    metadata: dict[str, Any] = {
        "repository": repository,
        "reportType": report_type,
        "generatedAt": generated.astimezone(timezone.utc)
        .isoformat(timespec="milliseconds")
        .replace("+00:00", "Z"),
    }
    repository_facts = facts.get("repository") or {}
    if isinstance(repository_facts, dict):
        for key in ("stars", "forks", "openIssues", "language", "license", "defaultBranch"):
            if repository_facts.get(key) is not None:
                metadata[key] = repository_facts[key]

    report: dict[str, Any] = {
        "title": f"{repository} 技术尽调报告",
        "reportType": report_type,
        "repository": repository,
        "summary": summary,
        "riskLevel": str(analysis.get("riskLevel") or "UNKNOWN"),
        "sections": sections,
        "metadata": metadata,
    }
    if review:
        report["review"] = review
    report["markdown"] = render_markdown(report)
    return report


def render_markdown(report: dict[str, Any]) -> str:
    """Render the report payload as Markdown."""
    lines: list[str] = []
    title = report.get("title") or report.get("repository") or "Report"
    lines.append(f"# {title}")
    lines.append("")
    summary = report.get("summary")
    if summary:
        lines.append("## Summary")
        lines.append("")
        lines.append(str(summary))
        lines.append("")
    for section in report.get("sections") or []:
        lines.append(f"## {section.get('title')}")
        lines.append("")
        lines.append(str(section.get("content")))
        lines.append("")
    review = report.get("review")
    if review:
        lines.append("## Human Review")
        lines.append("")
        lines.append(f"- decision: {review.get('decision', 'UNKNOWN')}")
        if review.get("comment"):
            lines.append(f"- comment: {review['comment']}")
        lines.append("")
    metadata = report.get("metadata") or {}
    if metadata:
        lines.append("## Metadata")
        lines.append("")
        for key, value in metadata.items():
            lines.append(f"- {key}: {value}")
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"
