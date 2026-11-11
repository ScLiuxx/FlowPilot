-- FlowPilot agent service — durable execution records.
--
-- The tables live in the PostgreSQL database `flowpilot_agent`, next to the
-- LangGraph checkpoint tables. MySQL stays the authority for the business
-- state; these tables only hold what the agent runtime needs to be idempotent
-- and resumable.

CREATE TABLE IF NOT EXISTS agent_execution_record
(
    id                BIGSERIAL PRIMARY KEY,
    invocation_id     BIGINT      NOT NULL,
    thread_id         TEXT        NOT NULL,
    agent_type        TEXT        NOT NULL,
    status            TEXT        NOT NULL,
    attempt_no        INTEGER,
    checkpoint_id     TEXT,
    result            JSONB,
    error             JSONB,
    usage             JSONB,
    resume_request_id TEXT,
    message_id        TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Business idempotency key of AgentExecute: one invocation, one record.
    CONSTRAINT uk_agent_execution_record_invocation UNIQUE (invocation_id),
    -- Business idempotency key of AgentResume: invocation + resume request.
    CONSTRAINT uk_agent_execution_record_resume UNIQUE (invocation_id, resume_request_id)
);

CREATE INDEX IF NOT EXISTS idx_agent_execution_record_status
    ON agent_execution_record (status);

COMMENT ON TABLE agent_execution_record IS
    'Durable record of every agent invocation; the unique constraints implement the '
    'protocol idempotency rules (AgentExecute -> invocationId, AgentResume -> invocationId + resumeRequestId).';

-- One row per processed resume request. It keeps the terminal result of a
-- resume so that a redelivered AgentResume can be answered with the recorded
-- result instead of resuming the graph a second time.
CREATE TABLE IF NOT EXISTS agent_resume_record
(
    id                BIGSERIAL PRIMARY KEY,
    invocation_id     BIGINT      NOT NULL,
    resume_request_id TEXT        NOT NULL,
    thread_id         TEXT        NOT NULL,
    status            TEXT        NOT NULL,
    payload           JSONB,
    checkpoint_id     TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uk_agent_resume_record UNIQUE (invocation_id, resume_request_id)
);

COMMENT ON TABLE agent_resume_record IS
    'Idempotency ledger of AgentResume messages: invocationId + resumeRequestId is unique.';
