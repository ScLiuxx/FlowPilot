-- =====================================================================
-- FlowPilot V1.2 MySQL schema additions
--
-- The outbox publisher needs to know when a row entered the PUBLISHING
-- state: the recovery of a row whose publisher died must be based on the
-- time the row was claimed, not on the time the row was created. A row that
-- was created hours ago and is published now would be recovered while it is
-- being published.
-- =====================================================================

-- ---------------------------------------------------------------------
-- The moment the row was claimed for publishing
-- ---------------------------------------------------------------------
ALTER TABLE message_outbox
    ADD COLUMN publishing_at DATETIME(3) NULL;

CREATE INDEX idx_outbox_publishing
    ON message_outbox (status, publishing_at);
