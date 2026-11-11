# FlowPilot Protocol

Shared message protocol between the Java backend and the Python agent service.

Both sides load the same JSON Schema files, so the contract cannot drift silently.

## Envelope

Every AMQP message uses the same envelope (`common/envelope.schema.json`):

```json
{
  "schemaVersion": "1.0",
  "messageId": "550e8400-e29b-41d4-a716-446655440000",
  "messageType": "AgentExecute",
  "correlationId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "causationId": null,
  "workflowExecutionId": 10001,
  "nodeExecutionId": 20001,
  "attemptNo": 1,
  "invocationId": 30001,
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "timestamp": "2026-11-11T10:00:00.000Z",
  "producer": "flowpilot-backend",
  "payload": {}
}
```

## Identifier semantics

| Identifier | Purpose | Stability rule |
|---|---|---|
| `messageId` | Message level dedup | New UUID per published message |
| `correlationId` | Groups messages of one business operation | Propagated to all derived messages |
| `causationId` | Direct upstream message | Set to the upstream `messageId` |
| `traceId` | Distributed tracing | 32 hex chars, propagated across services |
| `invocationId` | One agent invocation | Stable across retries and resumes |
| `threadId` | LangGraph checkpoint thread | `flowpilot-{invocationId}`, never regenerated on redelivery |
| `attemptNo` | Physical execution attempt | Increments per retry of the same node |

## Routing keys

| Routing key | Queue | Producer |
|---|---|---|
| `workflow.started` | `workflow.scheduler.queue` | Backend |
| `node.execute` | `node.execute.queue` | Scheduler |
| `node.completed` | `workflow.scheduler.queue` | Worker |
| `node.failed` | `workflow.scheduler.queue` | Worker |
| `agent.execute` | `agent.execute.queue` | Backend |
| `agent.resume` | `agent.control.queue` | Backend |
| `agent.cancel` | `agent.control.queue` | Backend |
| `agent.completed` | `backend.agent-events.queue` | Agent service |
| `agent.failed` | `backend.agent-events.queue` | Agent service |
| `agent.interrupted` | `backend.agent-events.queue` | Agent service |

All exchanges and queues are durable, messages are persistent, publishing uses
publisher confirms and consuming uses manual acknowledgement.

## Topology names

Both services declare the same topology, so the names must match exactly. A queue can only be
declared once with a given set of arguments, and the broker rejects a conflicting declaration
with `PRECONDITION_FAILED`.

| Name | Value |
|---|---|
| Main exchange | `flowpilot.events` (topic, durable) |
| Dead letter exchange | `flowpilot.dead-letter` (topic, durable) |
| Dead letter routing key | `flowpilot.dead-letter` |
| Dead letter queue | `flowpilot.dead-letter.queue` |

Every work queue is declared with `x-dead-letter-exchange = flowpilot.dead-letter` and
`x-dead-letter-routing-key = flowpilot.dead-letter`.

## Business idempotency keys

| Message | Idempotency basis |
|---|---|
| `WorkflowStarted` | `workflowExecutionId` |
| `NodeExecute` | `nodeExecutionId` + `attemptNo` |
| `NodeCompleted` | `nodeExecutionId` + `attemptNo` |
| `NodeFailed` | `nodeExecutionId` + `attemptNo` |
| `AgentExecute` | `invocationId` |
| `AgentCompleted` | `invocationId` + terminal state check |
| `AgentFailed` | `invocationId` + terminal state check |
| `AgentInterrupted` | `invocationId` + checkpoint id |
| `AgentResume` | `invocationId` + `resumeRequestId` |
| `AgentCancel` | `invocationId` + cancel request id |

## Compatibility rules

1. New optional fields must stay backward compatible within the same `schemaVersion`.
2. Removing a field or changing its meaning requires a `schemaVersion` bump.
3. Consumers must explicitly reject unsupported major versions instead of silently ignoring fields.
4. Producers upgrade before consumers when introducing new required behaviour.
