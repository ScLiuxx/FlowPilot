"""Registry mapping an ``agentType`` to the graph that implements it."""

from __future__ import annotations

from typing import Callable

from langgraph.graph import StateGraph

from app.runtime.context import AgentExecutionContext
from app.runtime.errors import ValidationError

GraphBuilder = Callable[[AgentExecutionContext], StateGraph]


class AgentGraphRegistry:
    """Allow list of the agent types this service is able to execute."""

    def __init__(self) -> None:
        self._builders: dict[str, GraphBuilder] = {}

    def register(self, agent_type: str, builder: GraphBuilder) -> None:
        self._builders[agent_type] = builder

    def build(self, agent_type: str, context: AgentExecutionContext) -> StateGraph:
        builder = self._builders.get(agent_type)
        if builder is None:
            raise ValidationError(
                f"unknown agent type '{agent_type}'",
                error_code="AG-UNKNOWN-TYPE-001",
                diagnostics={"agentType": agent_type, "known": sorted(self._builders)},
            )
        return builder(context)

    def agent_types(self) -> list[str]:
        return sorted(self._builders)

    def describe(self) -> list[dict[str, str]]:
        return [{"agentType": agent_type} for agent_type in self.agent_types()]


registry = AgentGraphRegistry()


def register_default_graphs(target: AgentGraphRegistry | None = None) -> AgentGraphRegistry:
    """Register the graphs shipped with the service."""
    from app.graphs.analysis_graph import AGENT_TYPE_DEPENDENCY_ANALYST, build_analysis_graph
    from app.graphs.llm_graph import AGENT_TYPE_LLM, build_llm_graph
    from app.graphs.research_graph import AGENT_TYPE_REPOSITORY_ANALYST, build_research_graph
    from app.graphs.review_graph import AGENT_TYPE_REPORT_REVIEWER, build_review_graph

    target = target or registry
    target.register(AGENT_TYPE_REPOSITORY_ANALYST, build_research_graph)
    target.register(AGENT_TYPE_DEPENDENCY_ANALYST, build_analysis_graph)
    target.register(AGENT_TYPE_REPORT_REVIEWER, build_review_graph)
    target.register(AGENT_TYPE_LLM, build_llm_graph)
    return target


register_default_graphs()
