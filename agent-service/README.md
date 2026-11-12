# FlowPilot Agent Service

Python agent runtime of FlowPilot. It executes LangGraph agents on behalf of the
Java Spring Boot backend and reports the result back over RabbitMQ.

* **Consumes** `agent.execute.queue` (`agent.execute`) and `agent.control.queue`
  (`agent.resume`, `agent.cancel`).
* **Publishes** `agent.completed`, `agent.failed` and `agent.interrupted` to the
  durable topic exchange `flowpilot.events`.
* **Persists** the LangGraph checkpoints and the durable execution records in
  PostgreSQL (`flowpilot_agent`).
* **Serves** `GET /health`, `GET /ready` and a few internal debug endpoints.

The message contract is *not* re-implemented here: every envelope is a 1:1
mirror of the JSON Schema files in `../flowpilot-protocol`, and outgoing
messages are validated against those files at runtime.

---

## 1. Layout

```text
agent-service/
├── app/
│   ├── main.py                  # FastAPI app + lifespan (broker, db, consumer)
│   ├── api/
│   │   ├── health.py            # GET /health, GET /ready
│   │   └── internal.py          # /internal/agents, /internal/invocations/**
│   ├── config/
│   │   ├── settings.py          # pydantic-settings (env driven)
│   │   └── logging.py           # structured JSON logs + correlation context
│   ├── runtime/
│   │   ├── executor.py          # runs / resumes an invocation, idempotency
│   │   ├── registry.py          # agentType -> graph builder
│   │   ├── context.py           # AgentExecutionContext, limits, journal
│   │   ├── errors.py            # error taxonomy -> protocol errorCategory
│   │   └── state.py             # the (checkpointed) graph state
│   ├── graphs/
│   │   ├── research_graph.py    # REPOSITORY_ANALYST
│   │   ├── analysis_graph.py    # DEPENDENCY_ANALYST
│   │   ├── review_graph.py      # REPORT_REVIEWER (human approval interrupt)
│   │   └── llm_graph.py         # LLM (a single model call)
│   ├── llm/
│   │   ├── provider.py          # provider abstraction (real / fake)
│   │   └── fake_model.py        # deterministic offline chat model
│   ├── telemetry/
│   │   ├── tracing.py           # tracer provider + global tracer
│   │   └── trace_context.py     # parent context out of traceId / messageId
│   ├── tools/
│   │   ├── github_tool.py       # GitHub REST API (allow list, SSRF defence)
│   │   ├── dependency_tool.py   # dependency / supply chain risk analysis
│   │   ├── report_tool.py       # report assembly
│   │   └── untrusted.py         # sanitisation of untrusted content
│   ├── persistence/
│   │   ├── checkpoint.py        # LangGraph Postgres checkpointer (+ memory saver)
│   │   ├── repository.py        # durable execution records + outbox (Postgres / memory)
│   │   └── schema.sql           # DDL of the agent tables
│   └── messaging/
│       ├── consumer.py          # aio-pika consumer (manual ack, DLQ)
│       ├── producer.py          # aio-pika producer (publisher confirms)
│       ├── outbox_publisher.py  # delivers the transactional outbox
│       └── events.py            # envelope + payload models, schema validation
├── tests/
├── Dockerfile
├── requirements.txt
├── pyproject.toml
└── README.md
```

---

## 2. Running it

```bash
cd agent-service
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt

# local development without any infrastructure
export MESSAGING_ENABLED=false PERSISTENCE_ENABLED=false
uvicorn app.main:app --host 0.0.0.0 --port 8000

# production like
export RABBITMQ_URL=amqp://guest:guest@localhost:5672/
export POSTGRES_DSN=postgresql://flowpilot:flowpilot@127.0.0.1:5432/flowpilot_agent
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Docker:

```bash
docker build -t flowpilot-agent-service .
docker run --rm -p 8000:8000 \
  -e RABBITMQ_URL=amqp://guest:guest@rabbitmq:5672/ \
  -e POSTGRES_DSN=postgresql://flowpilot:flowpilot@postgres:5432/flowpilot_agent \
  -v ../flowpilot-protocol:/app/flowpilot-protocol:ro \
  flowpilot-agent-service
```

### Configuration

| Variable | Default | Meaning |
|---|---|---|
| `RABBITMQ_URL` | `amqp://guest:guest@localhost:5672/` | broker connection |
| `RABBITMQ_EXCHANGE` | `flowpilot.events` | topic exchange (durable) |
| `RABBITMQ_EXECUTE_QUEUE` | `agent.execute.queue` | command queue |
| `RABBITMQ_CONTROL_QUEUE` | `agent.control.queue` | resume/cancel queue |
| `RABBITMQ_DEAD_LETTER_QUEUE` | `flowpilot.dead-letter.queue` | dead letter queue |
| `RABBITMQ_MAX_REDELIVERIES` | `3` | bounded requeue attempts |
| `POSTGRES_DSN` | `postgresql://flowpilot:flowpilot@127.0.0.1:5432/flowpilot_agent` | checkpoints + execution records |
| `PERSISTENCE_ENABLED` | `true` | `false` → in memory store/checkpointer |
| `PERSISTENCE_REQUIRED` | `true` | `true` → the service refuses to start without PostgreSQL |
| `MESSAGING_ENABLED` | `true` | `false` → no broker connection |
| `REDIS_URL` | `redis://localhost:6379/0` | shared idempotency journal; empty → in memory journal |
| `IDEMPOTENCY_JOURNAL_TTL_SECONDS` | `604800` | TTL of the journal entries (7 days) |
| `OUTBOX_POLL_INTERVAL_SECONDS` | `1.0` | interval of the outbox publisher |
| `OUTBOX_BATCH_SIZE` | `20` | rows delivered per outbox cycle |
| `OUTBOX_MAX_RETRY_SECONDS` | `60` | upper bound of the delivery backoff |
| `OUTBOX_PUBLISHING_TIMEOUT_SECONDS` | `300` | a row stuck in `PUBLISHING` is reclaimed |
| `LLM_API_KEY` / `OPENAI_API_KEY` | – | when set the real model is used |
| `LLM_BASE_URL` / `LLM_MODEL` | `https://api.openai.com/v1` / `gpt-4o-mini` | OpenAI compatible endpoint |
| `GITHUB_TOKEN` | – | optional; without it the public API is used (low rate limit) |
| `INTERNAL_API_TOKEN` | – | bearer token for `/internal/**`; **required** in `production`/`staging` |
| `RESUME_LEASE_SECONDS` | `30` | how long a worker may continue a paused thread |
| `RESUME_LEASE_WAIT_SECONDS` | `15` | how long a delivery waits for the lease of a resume |
| `RESUME_MAX_TAKEOVERS` | `3` | how often a resume may be taken over before it is given up |
| `CANCEL_POLL_SECONDS` | `2.0` | how often a running agent reads the cancel state |
| `PROTOCOL_SCHEMA_DIR` | auto detected | directory of the shared JSON Schema files |
| `OTEL_ENABLED` | `true` | `false` → no OpenTelemetry spans |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | – | OTLP gRPC endpoint; empty → the spans stay local |
| `OTEL_SERVICE_NAME` | `flowpilot-agent-service` | resource of the spans |

---

## 3. Design notes

### 3.1 Unified envelope

`app/messaging/events.py` implements the envelope of
`flowpilot-protocol/common/envelope.schema.json` as a Pydantic model with the
exact field names (`extra="forbid"`, `schemaVersion` pattern, uuid `messageId`,
32 hex `traceId`, ISO-8601 timestamp with millisecond precision). The payload
models mirror `flowpilot-protocol/agent/*.schema.json`.

`ProtocolSchemaRegistry` loads the *shared* schema files and validates every
incoming and outgoing message with `jsonschema`
(`tests/test_protocol_schema.py` is the contract guard).

### 3.2 Idempotency

| Message | Idempotency key | Enforcement |
|---|---|---|
| `AgentExecute` | `invocationId` | `UNIQUE (invocation_id)` on `agent_execution_record` |
| `AgentResume` | `invocationId` + `resumeRequestId` | `UNIQUE (invocation_id, resume_request_id)` on `agent_execution_record` and on `agent_resume_record` |

The executor inserts the invocation record *before* it runs the graph
(`INSERT … ON CONFLICT DO NOTHING`). A redelivered message therefore finds the
existing record and returns the recorded terminal result instead of starting a
second run — the recorded `result` / `error` payload is written to the outbox
again, which also repairs the case "the agent finished but the event was lost".

A retry with a **higher `attemptNo`** of a previously failed invocation is not
a redelivery: it is executed again **on the same thread**.

### 3.3 Checkpoint / resume

* The LangGraph `thread_id` is always the one the backend sent
  (`flowpilot-{invocationId}`); a mismatch is rejected with `AG-THREAD-ID-001`.
  The thread is never regenerated, not even on redelivery.
* The checkpointer is `langgraph-checkpoint-postgres` against
  `flowpilot_agent` (`AsyncPostgresSaver`, `setup()` is executed at start-up).
* After every run the newest `checkpoint_id` is read back and stored in
  `agent_execution_record.checkpoint_id`; it is reported in `agent.completed`
  and `agent.interrupted`.

### 3.4 Interrupt / human in the loop

`REPORT_REVIEWER` (and the research graph when the analysis is risky) calls
LangGraph's `interrupt()`. The service publishes `AgentInterrupted`
(`interruptType: HUMAN_APPROVAL`, the interrupt payload and the checkpoint id)
and waits. The backend answers with `AgentResume`, the graph is continued with
`Command(resume=...)` **on the same thread**.

> **LangGraph re-executes the node that raised the interrupt when the thread is
> resumed.** Therefore every side effect *before* `interrupt()` must be
> idempotent.
> In `app/graphs/review_graph.py` the only side effect in front of the
> interrupt is the registration of the approval request in a **keyed
> idempotency journal** (`AgentExecutionContext.journal`): the key is derived
> deterministically from the invocation id and the draft, so the second
> execution of the node observes the key and does not repeat the effect. No
> non repeatable work (mail, notifications, external writes) happens before the
> interrupt — creating the approval record is the job of the Java backend when
> it receives `AgentInterrupted`.
> `tests/test_interrupt_resume.py` asserts that the effect happened exactly
> once.
>
> The journal is **shared by every replica** of the service: `REDIS_URL`
> selects the Redis implementation (`SET key value NX EX ttl`, therefore atomic)
> and an empty value falls back to the in memory journal with a warning.

### 3.5 The recovery of a resume

A resume that is continued by a worker that dies would be stuck forever: the
ledger of `agent_resume_record` holds the claim, and every later delivery of
the resume command would find the claim and give up.

The ledger therefore carries a **lease**:

| Column | Meaning |
|---|---|
| `lease_token` | the fencing token of the worker that owns the resume |
| `lease_expire_at` | the moment the lease of the owner expires |
| `takeover_count` | how often the resume was taken over |

* The worker that claims a resume takes the lease and renews it while it
  continues the thread.
* A delivery that finds an **expired** lease takes it over: the token is
  incremented, the takeover is counted, and the graph continues on the **same**
  thread and the **same** checkpoint. The takeover is fenced by the token: the
  worker that lost the lease can no longer write the result.
* A delivery that finds a **live** lease belongs to a worker that is still
  working. It waits for the lease and gives up if the owner finishes in the
  meantime.
* A resume that was taken over too often (`RESUME_MAX_TAKEOVERS`) is given up
  with `AG-RESUME-TOKEOVER-LIMIT-001` instead of looping forever. Its ledger row
  is marked `RECOVERABLE`: the thread and its checkpoint are intact, so the
  request may be continued again once the cause is gone. The takeover limit
  only guards the *takeover* — a delivery that finds a live lease never gives
  the resume up.

The lifecycle of a ledger row:

| Status | Meaning |
|---|---|
| `CLAIMED` | a worker owns the lease and continues the graph |
| `PROCESSING` | the same, written by an older version of the service |
| `RECOVERABLE` | the automatic takeover was given up, the request may be continued again |
| `COMPLETED` / `FAILED` / `INTERRUPTED` | the result of the resume |

`tests/test_resume_recovery.py` and `tests/test_postgres_integration.py` assert
the takeover, the fencing, the limit and the recoverable state.

### 3.6 The cancellation of a running agent

`AgentCancel` is a **request**, not a kill: the side effects the agent already
caused are not undone.

* The terminal state is written first and **conditionally** (`guard_statuses`):
  a success that is already on its way wins the race, and a cancelled
  invocation never receives a late success event.
* The running graph is stopped where it can be stopped: the graph runs in its
  own task, the cancel command cancels it, and the nodes check the
  cancellation before every tool call and every model call.
* A cancel that was handled by **another replica** is noticed through the
  execution record: the running worker reads it regularly and stops.

### 3.7 Budgets

`maxIterations`, `maxToolCalls`, `maxTokens` and `timeoutSeconds` come from the
`AgentExecute` payload config. The tool budget is reserved *before* the call is
made, the wall clock budget is enforced with `asyncio.wait_for`.

| Violation | Error code | Category | Retryable |
|---|---|---|---|
| wall clock budget | `AG-TIMEOUT-001` | `TRANSIENT` | yes |
| max iterations / tool calls / tokens | `AG-BUDGET-001` | `BUSINESS` | no |
| unexpected defect | `AG-INTERNAL-001` | `INTERNAL` | no |
| GitHub 403/429 quota | `GITHUB_RATE_LIMITED` | `RATE_LIMIT` | yes |

The service never hangs: every path ends in `agent.completed` or
`agent.failed`.

### 3.8 Tool security (allow list, SSRF, prompt injection)

* Only `owner/repo` or `https://github.com/owner/repo` are accepted; every
  other form (other hosts, other schemes, `git@` remotes, extra path segments,
  `..`) is rejected with `GITHUB_INVALID_REPOSITORY`.
* Every request goes to the **fixed** base URL `https://api.github.com`; the
  host is never taken from the message or from the model. Redirects are not
  followed (`follow_redirects=False`), so a renamed repository cannot point
  the service at an internal address.
* Only five read operations are exposed; there is no shell execution, no
  local file access and no arbitrary URL fetching.
* Repository content (README, issue bodies, commit messages) is **untrusted
  data**. `app/tools/untrusted.py` strips control characters, truncates the
  text and wraps it into an explicitly labelled `UNTRUSTED_CONTENT` block that
  states that the content must not be interpreted as an instruction.
* `GITHUB_TOKEN` is used when present; without it the unauthenticated public
  API is used and a 403/429 is mapped to the `RATE_LIMIT` category.

### 3.9 LLM provider

`app/llm/provider.py` returns the real OpenAI compatible client when
`LLM_API_KEY`/`OPENAI_API_KEY` is set, otherwise the deterministic
`FakeChatModel`. The fake model returns structured JSON that matches the
analysis schema, so the service, the tests and the demo work completely
offline and produce stable output.

### 3.10 Messaging reliability

* Durable topic exchange `flowpilot.events`, durable queues
  `agent.execute.queue` / `agent.control.queue`, both dead lettering to
  `flowpilot.dead-letter.queue`.
* Persistent messages, **manual ack** — the ACK is only sent after the business
  work succeeded.
* The result event is **not** published by the consumer: the execution record
  and the event are written in **one transaction** to the transactional outbox
  (`agent_outbox`), the outbox publisher (`app/messaging/outbox_publisher.py`)
  delivers the rows afterwards. The ACK can therefore never lose a result.
* Publisher confirms are enabled on the producing channel.
* Transient failures are requeued with a bounded attempt counter
  (`RABBITMQ_MAX_REDELIVERIES`); everything else is rejected into the dead
  letter queue. There is no infinite requeue loop.
* `aio_pika.connect_robust` plus a supervisor loop in `app/main.py` keep the
  consumer alive across broker restarts.

The outbox publisher claims the pending rows with
`FOR UPDATE SKIP LOCKED`, marks them as `PUBLISHING` and delivers them. A
failed delivery returns the row to `PENDING` with an exponential backoff
(`OUTBOX_MAX_RETRY_SECONDS`), a row that stayed in `PUBLISHING` for more than
`OUTBOX_PUBLISHING_TIMEOUT_SECONDS` is reclaimed. The delivery is *at least
once*: the backend stays idempotent on the invocation id.

### 3.11 Observability

Structured JSON logs (one JSON object per line) always carry `traceId`,
`invocationId`, `threadId` and `messageId`; the identifiers are bound through
`contextvars` so that they also appear in the LangGraph worker tasks.
`input_tokens` / `output_tokens` / `llmCalls` / `toolCalls` / `toolFailures`
are accumulated in the graph state and in the run counters
(`AgentExecutionContext.counters`) and reported in the `usage` block of
`agent.completed`.

OpenTelemetry spans join the trace the Java backend opened — the `traceId` of
the envelope *is* the OpenTelemetry trace id:

| Span | Source |
|---|---|
| `agent.graph` | `AgentExecutor._run_graph` |
| `llm.call` | `ChatModel.ainvoke` (`model`, `inputTokens`, `outputTokens`) |
| `tool.github_<operation>` | the read only GitHub operations |
| `tool.dependency_scan` | `analyze_dependencies` |

The spans only carry identifiers, counters and error codes — never a prompt,
an answer or an API key. `OTEL_EXPORTER_OTLP_ENDPOINT` enables the OTLP gRPC
exporter; without it the spans stay local.

---

## 4. Tests

```bash
cd agent-service
.venv/bin/python -m pytest tests -q
```

The suite needs **no** RabbitMQ, no PostgreSQL and no network access: the
broker is replaced by fakes and the checkpointer by
`langgraph.checkpoint.memory`. The PostgreSQL integration tests
(`tests/test_postgres_integration.py`) are skipped automatically when the
database is not reachable.

Coverage of the hard requirements:

| Requirement | Test |
|---|---|
| envelope + payload schemas | `tests/test_protocol_schema.py`, `tests/test_events.py` |
| idempotency of `AgentExecute` / `AgentResume` | `tests/test_idempotency.py` |
| checkpoint / interrupt / resume | `tests/test_interrupt_resume.py` |
| budgets and timeouts | `tests/test_budgets.py` |
| tool allow list, SSRF, prompt injection | `tests/test_github_tool.py` |
| consumer ack / requeue / dead letter | `tests/test_consumer.py` |
| producer (persistent, confirms, schema) | `tests/test_producer.py` |
| durable records | `tests/test_repository.py` |
| HTTP surface | `tests/test_api.py` |
| transactional outbox | `tests/test_outbox.py` |
| result of an invocation (recovery) | `tests/test_result_endpoint.py` |
| startup without PostgreSQL | `tests/test_startup.py` |
| shared idempotency journal | `tests/test_journal.py` |
| LLM node type | `tests/test_llm_graph.py` |
| llm / tool counters | `tests/test_counters.py` |
| OpenTelemetry spans | `tests/test_telemetry.py` |
| PostgreSQL tables + checkpointer | `tests/test_postgres_integration.py` |
