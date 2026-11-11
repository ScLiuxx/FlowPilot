-- =====================================================================
-- FlowPilot V1.1 MySQL schema additions
--
-- Three groups of changes:
--   1. The counters of the agent calls. The architecture design asks for the number of model
--      calls, the token consumption and the tool call success rate, which can only be computed
--      from the calls the agent service reported.
--   2. The trace spans, so the execution chain stays queryable even when the collector is down.
--   3. The deadline of an approval, so a pending approval cannot wait forever.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 13. The call counters of an agent invocation
-- ---------------------------------------------------------------------
ALTER TABLE agent_invocation
    ADD COLUMN llm_call_count INT NOT NULL DEFAULT 0,
    ADD COLUMN tool_call_count INT NOT NULL DEFAULT 0,
    ADD COLUMN tool_failure_count INT NOT NULL DEFAULT 0;

-- ---------------------------------------------------------------------
-- 13b. The trace of a workflow execution
--
-- The whole execution belongs to one trace. Without the column every message would start a
-- new trace, and the execution chain could not be reconstructed.
-- ---------------------------------------------------------------------
ALTER TABLE workflow_execution
    ADD COLUMN trace_id VARCHAR(64) NULL;

CREATE INDEX idx_execution_trace ON workflow_execution (trace_id);

-- ---------------------------------------------------------------------
-- 14. trace_span (the persisted spans of one execution)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS trace_span (
    id BIGINT PRIMARY KEY,

    trace_id VARCHAR(64) NOT NULL,
    -- The span id of the parent span, empty for a root span.
    parent_span_id VARCHAR(32),
    span_id VARCHAR(32) NOT NULL,
    span_name VARCHAR(128) NOT NULL,

    workflow_execution_id BIGINT,
    node_execution_id BIGINT,

    duration_ms BIGINT,
    status VARCHAR(32),

    -- The attributes of the span. They never contain prompts or credentials.
    attributes JSON,

    started_at DATETIME(3),
    finished_at DATETIME(3),

    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    KEY idx_trace (trace_id, started_at),
    KEY idx_workflow_execution (workflow_execution_id),
    KEY idx_node_execution (node_execution_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- 15. execution_event_archive (the events of a finished execution)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS execution_event_archive (
    id BIGINT PRIMARY KEY,

    workflow_execution_id BIGINT NOT NULL,
    node_execution_id BIGINT,

    event_type VARCHAR(64) NOT NULL,
    event_data JSON,

    sequence_no BIGINT NOT NULL,

    operator_id BIGINT,
    trace_id VARCHAR(64),

    -- The moment the event was archived.
    archived_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_at DATETIME(3) NOT NULL,

    KEY idx_execution_sequence (workflow_execution_id, sequence_no),
    KEY idx_archived_at (archived_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- 16. The deadline of an approval
-- ---------------------------------------------------------------------
ALTER TABLE approval_record
    ADD COLUMN expire_at DATETIME(3) NULL,
    ADD COLUMN timeout_action VARCHAR(32) NULL;

CREATE INDEX idx_approval_expire
    ON approval_record (status, expire_at);
