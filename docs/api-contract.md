# FlowPilot REST API Contract

Base URL: `/api/v1`
Content type: `application/json; charset=utf-8`

## Unified response envelope

Every response (success or error) uses the same body:

```json
{
  "code": 0,
  "message": "success",
  "data": {},
  "requestId": "req-001",
  "timestamp": "2026-11-11T10:00:00.000Z"
}
```

| Field | Type | Notes |
|---|---|---|
| `code` | integer | `0` = success, non-zero = business error |
| `message` | string | Human readable message |
| `data` | object/null | Payload, `null` on error |
| `requestId` | string | Request correlation id |
| `timestamp` | string | ISO-8601 UTC |

HTTP status codes still convey the HTTP-layer outcome (400 validation, 401 unauthenticated,
403 forbidden, 404 not found, 409 conflict, 500 internal).

## Identifiers

Every identifier is a **string**. The identifiers are 64 bit snowflake ids, and a JavaScript
number holds 53 bits exactly. An id that is sent as a number is rounded by the browser
(`378902953627938816` becomes `378902953627938800`), and the detail pages then address a record
that does not exist. The string form is the only representation a browser can use without losing
precision.

The message protocol is not affected: the identifiers inside the AMQP envelopes stay integers,
because the protocol schema defines them as `integer/int64`.

## Authentication

All endpoints except `POST /api/v1/auth/register`, `POST /api/v1/auth/login` and
`GET /api/v1/actuator/**` require a bearer token:

```
Authorization: Bearer <jwt>
```

### POST /api/v1/auth/register

```json
{ "username": "alice", "password": "secret123", "email": "a@b.c", "nickname": "Alice" }
```

Response `data`: `{ "id": "1001", "username": "alice", "role": "USER", "nickname": "Alice" }`

### POST /api/v1/auth/login

```json
{ "username": "alice", "password": "secret123" }
```

Response `data`: `{ "token": "<jwt>", "tokenType": "Bearer", "expiresIn": 7200, "user": {...} }`

### GET /api/v1/auth/me

Response `data`: `{ "id": "1001", "username": "alice", "role": "USER", "nickname": "Alice", "email": "a@b.c" }`

## Workflows

### GET /api/v1/workflows

Query: `page` (default 1), `size` (default 20), `status`, `keyword`

Response `data`: `{ "total": 10, "page": 1, "size": 20, "records": [...] }`

### POST /api/v1/workflows

```json
{
  "name": "GitHub 技术尽调",
  "description": "开源项目技术尽调",
  "category": "GITHUB_DUE_DILIGENCE",
  "definition": { "nodes": [ ... ] }
}
```

Response `data`: workflow definition object.

### GET /api/v1/workflows/{workflowId}

### PUT /api/v1/workflows/{workflowId}

Updates name/description/category only. Node changes require a new version.

### DELETE /api/v1/workflows/{workflowId}

### POST /api/v1/workflows/{workflowId}/versions

Creates a new draft version from a new definition. Body: `{ "definition": {...} }`

Response `data`: `{ "id": "200", "workflowId": "100", "version": 2, "status": "DRAFT", "definitionHash": "..." }`

### POST /api/v1/workflows/{workflowId}/publish

Body: `{ "versionId": "200" }` — validates the DAG then marks the version PUBLISHED.

### GET /api/v1/workflows/{workflowId}/versions

### GET /api/v1/workflows/{workflowId}/versions/{versionId}

### POST /api/v1/workflows/validate

Validates a definition without saving. Body: `{ "definition": {...} }`

Response `data`: `{ "valid": true, "topologicalOrder": ["a","b","c"], "errors": [], "warnings": [] }`

### Workflow definition JSON shape

```json
{
  "nodes": [
    {
      "id": "collect_repository",
      "name": "采集仓库信息",
      "type": "HTTP",
      "dependencies": [],
      "config": { "operation": "get_repository" },
      "retryPolicy": { "maxAttempts": 3, "initialDelaySeconds": 2, "maxDelaySeconds": 60, "multiplier": 2.0 },
      "timeoutPolicy": { "timeoutSeconds": 30 }
    },
    {
      "id": "analyze_repository",
      "type": "AGENT",
      "dependencies": ["collect_repository"],
      "config": { "agentType": "REPOSITORY_ANALYST", "timeoutSeconds": 300, "maxToolCalls": 20 }
    }
  ]
}
```

Node `type` is one of `HTTP`, `CODE`, `CONDITION`, `LLM`, `AGENT`, `HUMAN`, `MERGE`.

## Executions

### POST /api/v1/workflows/{workflowId}/executions

```json
{ "workflowVersionId": "101", "input": { "repositoryUrl": "https://github.com/example/project" }, "idempotencyKey": "key-001" }
```

Response `data`:

```json
{ "executionId": "10001", "executionNo": "FP-20261111-00001", "status": "QUEUED" }
```

Returns immediately; the workflow runs asynchronously.

### GET /api/v1/executions

Query: `page`, `size`, `status`, `workflowId`

### GET /api/v1/executions/{executionId}

Response `data`:

```json
{
  "executionId": "10001",
  "executionNo": "FP-20261111-00001",
  "workflowId": "100",
  "workflowVersionId": "101",
  "status": "RUNNING",
  "totalNodes": 6,
  "completedNodes": 2,
  "failedNodes": 0,
  "input": {...},
  "output": {...},
  "errorCode": null,
  "errorMessage": null,
  "startedAt": "2026-11-11T10:00:00.000Z",
  "finishedAt": null,
  "createdAt": "2026-11-11T10:00:00.000Z"
}
```

Status is one of `CREATED`, `QUEUED`, `RUNNING`, `WAITING_HUMAN`, `RETRY_WAIT`, `SUCCEEDED`,
`FAILED`, `CANCELED`.

### GET /api/v1/executions/{executionId}/nodes

```json
[
  {
    "nodeExecutionId": "20001",
    "nodeKey": "collect_repository",
    "nodeType": "HTTP",
    "status": "SUCCEEDED",
    "attempt": 1,
    "maxAttempts": 3,
    "startedAt": "...",
    "finishedAt": "...",
    "durationMs": 812,
    "errorCode": null,
    "errorMessage": null
  }
]
```

Node status is one of `PENDING`, `READY`, `RUNNING`, `RETRY_WAIT`, `WAITING_HUMAN`, `SUCCEEDED`,
`FAILED`, `SKIPPED`, `CANCELED`.

### GET /api/v1/executions/{executionId}/nodes/{nodeExecutionId}/attempts

```json
[
  { "attemptNo": 1, "status": "FAILED", "workerId": "worker-01", "errorCode": "GITHUB_API_TIMEOUT",
    "errorMessage": "...", "startedAt": "...", "finishedAt": "...", "durationMs": 30000 }
]
```

### GET /api/v1/executions/{executionId}/events

Query: `afterSequence` (default 0), `limit` (default 100)

```json
[
  { "sequenceNo": 1, "eventType": "WORKFLOW_CREATED", "nodeKey": null,
    "eventData": {...}, "createdAt": "2026-11-11T10:00:00.000Z" }
]
```

### GET /api/v1/executions/{executionId}/events/stream  (SSE)

`text/event-stream`. Each message:

```
id: 12
event: NODE_SUCCEEDED
data: {"sequenceNo":12,"eventType":"NODE_SUCCEEDED","nodeKey":"collect_repository","status":"SUCCEEDED","eventData":{},"createdAt":"..."}
```

Supports the `Last-Event-ID` request header (or `?lastEventId=`) to resume from a sequence number.

### POST /api/v1/executions/{executionId}/cancel

Body: `{ "reason": "USER_REQUEST" }`

Response `data`: `{ "executionId": "10001", "status": "CANCEL_REQUESTED" }`

### POST /api/v1/executions/{executionId}/report

Generates the technical due diligence report of an execution. The report is created once, a
repeated call returns the existing report.

Response `data`:

```json
{ "reportId": "50001", "reportNo": "RPT-20261111-00001", "status": "PENDING_REVIEW", "title": "..." }
```

## Approvals

### GET /api/v1/approvals

Query: `page`, `size`, `status` (default PENDING)

### GET /api/v1/approvals/{approvalId}

```json
{
  "approvalId": "40001",
  "workflowExecutionId": 10001,
  "nodeExecutionId": "20002",
  "approvalType": "REPORT_REVIEW",
  "status": "PENDING",
  "title": "请审核技术尽调报告",
  "description": "...",
  "requestData": {},
  "decisionData": null,
  "requestedAt": "...",
  "decidedAt": null,
  "approverId": null,
  "comment": null
}
```

### POST /api/v1/approvals/{approvalId}/decision

Body: `{ "decision": "APPROVED", "comment": "内容符合要求" }`

`decision` is `APPROVED` or `REJECTED`. Submitting twice does not change the recorded decision.

Response `data`: `{ "approvalId": "40001", "status": "APPROVED" }`

## Reports

### GET /api/v1/reports

Query: `page`, `size`, `status`

### GET /api/v1/reports/{reportId}

```json
{
  "reportId": "50001",
  "reportNo": "RPT-20261111-00001",
  "workflowExecutionId": 10001,
  "title": "example/project 技术尽调报告",
  "repositoryUrl": "https://github.com/example/project",
  "reportType": "GITHUB_DUE_DILIGENCE",
  "status": "APPROVED",
  "summary": "...",
  "content": { ...structured sections... },
  "markdownContent": "# ...",
  "contentHash": "...",
  "generatedAt": "...",
  "createdAt": "..."
}
```

### GET /api/v1/reports/{reportId}/download

`text/markdown` attachment named `<reportNo>.md`.

## Monitoring

### GET /api/v1/monitoring/overview

```json
{
  "totalExecutions": 42,
  "runningExecutions": 3,
  "failedExecutions": 2,
  "waitingHumanExecutions": 1,
  "succeededExecutions": 36,
  "pendingApprovals": 1,
  "totalNodes": 260,
  "totalTokenInput": 120000,
  "totalTokenOutput": 45000
}
```

### GET /api/v1/monitoring/nodes

The node execution counts grouped by status.

```json
{ "SUCCEEDED": 18, "FAILED": 2, "PENDING": 3, "RUNNING": 1 }
```
