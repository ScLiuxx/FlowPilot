"""The dependency risk tool, the report assembly and the analysis graph."""

from __future__ import annotations

from app.messaging.events import AgentExecutePayload
from app.tools.dependency_tool import analyze_dependencies
from app.tools.report_tool import build_report, render_markdown
from tests.conftest import make_execute_envelope


def test_unpinned_and_insecure_dependencies_are_reported():
    report = analyze_dependencies(
        {
            "requirements.txt": (
                "requests==2.31.0\n"
                "flask\n"
                "urllib3>=2.0\n"
                "legacy @ http://example.com/legacy-1.0.tar.gz\n"
                "# a comment\n"
                "--index-url https://pypi.org/simple\n"
            )
        }
    )

    assert report["totalDependencies"] == 4
    assert report["unpinnedDependencies"] == 2  # flask and urllib3 (a range)
    assert report["highRiskDependencies"] == 1  # the http:// source
    assert report["riskLevel"] in ("MEDIUM", "HIGH")
    types = {finding["type"] for finding in report["findings"]}
    assert "unpinned_version" in types
    assert "insecure_source" in types


def test_package_json_dependencies_are_analysed():
    report = analyze_dependencies(
        {"package.json": '{"dependencies": {"left-pad": "1.3.0", "lodash": "^4.17.0"}}'}
    )
    assert report["totalDependencies"] == 2
    assert report["unpinnedDependencies"] == 1  # lodash uses a range


def test_a_clean_manifest_has_a_low_risk():
    report = analyze_dependencies({"requirements.txt": "requests==2.31.0\nflask==3.0.0\n"})
    assert report["riskLevel"] == "LOW"
    assert report["riskScore"] == 0


def test_the_report_contains_the_sections_and_the_metadata():
    report = build_report(
        repository="acme/widget",
        analysis={
            "architectureAnalysis": "layered",
            "maintenanceAnalysis": "active",
            "riskAnalysis": "low",
            "recommendation": "continue",
            "riskLevel": "LOW",
        },
        facts={"repository": {"stars": 12, "license": "MIT"}},
    )

    assert report["repository"] == "acme/widget"
    assert report["riskLevel"] == "LOW"
    assert [section["key"] for section in report["sections"]] == [
        "architecture",
        "maintenance",
        "risk",
        "recommendation",
    ]
    assert report["metadata"]["stars"] == 12
    assert report["metadata"]["license"] == "MIT"

    markdown = render_markdown(report)
    assert "# acme/widget" in markdown
    assert "## Risk Analysis" in markdown


async def test_the_dependency_analyst_agent_runs(executor):
    envelope = make_execute_envelope(agent_type="DEPENDENCY_ANALYST")
    payload = {
        **envelope.payload,
        "input": {
            "repository": "acme/widget",
            "manifests": {"requirements.txt": "requests==2.31.0\nflask\n"},
        },
    }
    envelope = envelope.model_copy(update={"payload": payload})

    outcome = await executor.execute(
        envelope, AgentExecutePayload.model_validate(envelope.payload)
    )

    assert outcome.status == "COMPLETED"
    report = outcome.payload["result"]["report"]
    assert report["reportType"] == "DEPENDENCY_RISK"
    assert outcome.payload["usage"]["outputTokens"] > 0
