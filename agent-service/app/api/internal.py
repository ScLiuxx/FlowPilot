"""Internal admin / debug endpoints.

These endpoints are meant for the operations team and for debugging a running
agent service. They are protected by an optional bearer token
(``INTERNAL_API_TOKEN``); when the token is not configured the endpoints are
open, which is only acceptable inside a trusted network.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request

from app.config.settings import Settings, get_settings
from app.persistence.checkpoint import list_checkpoints
from app.runtime.registry import registry as default_registry

router = APIRouter(prefix="/internal", tags=["internal"])


async def require_internal_token(request: Request) -> None:
    settings: Settings = getattr(request.app.state, "settings", None) or get_settings()
    expected = settings.internal_api_token
    if not expected:
        return
    header = request.headers.get("authorization", "")
    if header != f"Bearer {expected}":
        raise HTTPException(status_code=401, detail="invalid internal api token")


@router.get("/agents", dependencies=[Depends(require_internal_token)])
async def list_agents(request: Request) -> dict[str, Any]:
    """The agent types this service is able to execute."""
    registry = getattr(request.app.state, "registry", None) or default_registry
    return {"agents": registry.describe()}


@router.get("/invocations", dependencies=[Depends(require_internal_token)])
async def list_invocations(request: Request, limit: int = 50) -> dict[str, Any]:
    """The most recent agent invocations."""
    store = getattr(request.app.state, "store", None)
    if store is None:
        return {"records": []}
    records = await store.list_records(limit=limit)
    return {"records": [_record_to_dict(record) for record in records]}


@router.get("/invocations/{invocation_id}", dependencies=[Depends(require_internal_token)])
async def get_invocation(request: Request, invocation_id: int) -> dict[str, Any]:
    """The durable record of one agent invocation."""
    store = getattr(request.app.state, "store", None)
    if store is None:
        raise HTTPException(status_code=503, detail="no execution store configured")
    record = await store.get(invocation_id)
    if record is None:
        raise HTTPException(status_code=404, detail="invocation not found")
    return _record_to_dict(record)


@router.get(
    "/invocations/{invocation_id}/checkpoints",
    dependencies=[Depends(require_internal_token)],
)
async def get_checkpoints(request: Request, invocation_id: int, limit: int = 20) -> dict[str, Any]:
    """The LangGraph checkpoints of the thread of an invocation."""
    checkpointer = getattr(request.app.state, "checkpointer", None)
    store = getattr(request.app.state, "store", None)
    if checkpointer is None:
        raise HTTPException(status_code=503, detail="no checkpointer configured")
    thread_id = f"flowpilot-{invocation_id}"
    if store is not None:
        record = await store.get(invocation_id)
        if record is not None:
            thread_id = record.thread_id
    checkpoints = await list_checkpoints(checkpointer, thread_id, limit=limit)
    return {"invocationId": invocation_id, "threadId": thread_id, "checkpoints": checkpoints}


def _record_to_dict(record: Any) -> dict[str, Any]:
    return {
        "invocationId": record.invocation_id,
        "threadId": record.thread_id,
        "agentType": record.agent_type,
        "status": record.status,
        "attemptNo": record.attempt_no,
        "checkpointId": record.checkpoint_id,
        "resumeRequestId": record.resume_request_id,
        "messageId": record.message_id,
        "usage": record.usage,
        "error": record.error,
        "createdAt": record.created_at.isoformat() if record.created_at else None,
        "updatedAt": record.updated_at.isoformat() if record.updated_at else None,
    }
