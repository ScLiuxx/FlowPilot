"""Dependency / security risk analysis of a repository.

The tool is purely offline: it analyses dependency manifests that were already
retrieved through the allow listed GitHub operations. It never resolves,
downloads or executes a package, therefore it cannot be abused into an SSRF
or a remote code execution.
"""

from __future__ import annotations

import re
from typing import Any

from app.tools.untrusted import sanitize_untrusted

_REQUIREMENT_RE = re.compile(
    r"^(?P<name>[A-Za-z0-9][A-Za-z0-9._-]*)\s*(?P<extras>\[[^\]]*\])?\s*(?P<spec>.*)$"
)
_UNSAFE_SOURCE_RE = re.compile(r"(?P<scheme>git\+)?(?P<url>https?://[^\s;#]+)")
_EXACT_NPM_VERSION_RE = re.compile(r"^\d+(?:\.\d+)*$")

_SEVERITY_ORDER = {"INFO": 0, "LOW": 1, "MEDIUM": 2, "HIGH": 3}


def _iter_requirements(text: str) -> list[tuple[str, str, bool]]:
    """Return ``(name, spec, exact_pin)`` of a pip style requirements file."""
    requirements: list[tuple[str, str, bool]] = []
    for raw_line in (text or "").splitlines():
        line = raw_line.split("#", 1)[0].strip()
        if not line or line.startswith("-"):
            continue
        match = _REQUIREMENT_RE.match(line)
        if not match:
            continue
        name = match.group("name")
        spec = (match.group("spec") or "").strip()
        # pip resolves to an exact version only with "==".
        requirements.append((name, spec, "==" in spec))
    return requirements


def _iter_json_dependencies(text: str) -> list[tuple[str, str, bool]]:
    """Return ``(name, spec, exact_pin)`` of a package.json."""
    import json

    try:
        data = json.loads(text or "{}")
    except json.JSONDecodeError:
        return []
    dependencies: list[tuple[str, str, bool]] = []
    for section in ("dependencies", "devDependencies", "peerDependencies"):
        block = data.get(section) or {}
        if isinstance(block, dict):
            for key, value in block.items():
                spec = str(value)
                # npm resolves to an exact version only without a range prefix.
                exact = bool(_EXACT_NPM_VERSION_RE.match(spec))
                dependencies.append((str(key), spec, exact))
    return dependencies


def analyze_dependencies(manifests: dict[str, str]) -> dict[str, Any]:
    """Analyse dependency manifests and return a risk report.

    ``manifests`` maps a file name (``requirements.txt``, ``package.json`` …)
    to its raw content.
    """
    findings: list[dict[str, Any]] = []
    total = 0
    unpinned = 0
    insecure = 0

    for filename, content in (manifests or {}).items():
        text = sanitize_untrusted(content, max_chars=20000)
        if filename.endswith(".json"):
            entries = _iter_json_dependencies(text)
        else:
            entries = _iter_requirements(text)
        for name, spec, exact in entries:
            total += 1
            spec = spec.strip()
            unsafe = _UNSAFE_SOURCE_RE.search(spec)
            if unsafe or spec.startswith(("git+", "http://", "ftp://")):
                # The version comes from the URL itself, so the dependency is
                # not "unpinned" — but the source is outside the registry and
                # may even be plain http.
                if unsafe and unsafe.group("url").startswith("http://"):
                    insecure += 1
                    findings.append(
                        {
                            "type": "insecure_source",
                            "severity": "HIGH",
                            "dependency": name,
                            "file": filename,
                            "detail": f"'{name}' is fetched over an insecure http:// source.",
                        }
                    )
                else:
                    findings.append(
                        {
                            "type": "unpinned_source",
                            "severity": "MEDIUM",
                            "dependency": name,
                            "file": filename,
                            "detail": f"'{name}' is resolved from a non registry source.",
                        }
                    )
                continue
            if not exact or spec in {"*", "latest"}:
                # Only an exact pin ("pkg==1.2.3" / "pkg": "1.2.3") is
                # reproducible; a range or a missing version resolves to
                # whatever is newest at install time, which is a supply chain
                # risk.
                unpinned += 1
                findings.append(
                    {
                        "type": "unpinned_version",
                        "severity": "MEDIUM",
                        "dependency": name,
                        "file": filename,
                        "detail": f"'{name}' is not pinned to an exact version.",
                    }
                )
                continue

    if total > 50:
        findings.append(
            {
                "type": "dependency_count",
                "severity": "LOW",
                "dependency": None,
                "file": None,
                "detail": f"The project declares {total} dependencies.",
            }
        )

    score = min(100, unpinned * 5 + insecure * 20)
    if score >= 60:
        level = "HIGH"
    elif score >= 20:
        level = "MEDIUM"
    else:
        level = "LOW"

    findings.sort(key=lambda item: (-_SEVERITY_ORDER.get(item["severity"], 0), item["type"]))
    return {
        "totalDependencies": total,
        "unpinnedDependencies": unpinned,
        "highRiskDependencies": insecure,
        "riskScore": score,
        "riskLevel": level,
        "findings": findings,
    }
