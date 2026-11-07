-- LedgerGuard schema, version 2: fraud scoring, held transfers and an analyst audit log.

-- A transfer can now wait for review (HELD) or be stopped by an analyst (BLOCKED).
-- HELD and BLOCKED transfers have no ledger entries: no money moves until an analyst approves.
ALTER TABLE transfers DROP CONSTRAINT transfers_status_check;
ALTER TABLE transfers ADD CONSTRAINT transfers_status_check
    CHECK (status IN ('SETTLED', 'REJECTED', 'HELD', 'BLOCKED'));

-- What the fraud rules saw when the transfer was requested, and who reviewed it.
ALTER TABLE transfers
    ADD COLUMN risk_score   INTEGER NOT NULL DEFAULT 0 CHECK (risk_score >= 0),
    ADD COLUMN risk_signals TEXT    NOT NULL DEFAULT '',
    ADD COLUMN reviewed_by  UUID REFERENCES users (id),
    ADD COLUMN reviewed_at  TIMESTAMPTZ,
    ADD COLUMN review_note  TEXT;

-- The review queue only ever asks for HELD transfers, oldest first.
CREATE INDEX transfers_held_idx ON transfers (created_at) WHERE status = 'HELD';

-- Rules look at recent payments from the same account (velocity, first payment to a recipient).
CREATE INDEX transfers_from_to_idx ON transfers (from_account_id, to_account_id);

-- The audit log: one row per decision. actor_id is NULL when the system itself acted (holding a transfer).
CREATE TABLE review_events (
    id          BIGSERIAL PRIMARY KEY,
    transfer_id UUID        NOT NULL REFERENCES transfers (id),
    actor_id    UUID REFERENCES users (id),
    action      TEXT        NOT NULL CHECK (action IN ('HELD', 'APPROVED', 'BLOCKED', 'REJECTED_ON_APPROVAL')),
    note        TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX review_events_recent_idx ON review_events (id DESC);
CREATE INDEX review_events_transfer_idx ON review_events (transfer_id);

-- Like the ledger, the audit log is append-only: nobody can quietly rewrite a decision.
CREATE FUNCTION forbid_review_event_changes() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'review_events is append-only';
END
$$;
CREATE TRIGGER review_events_append_only
    BEFORE UPDATE OR DELETE ON review_events
    FOR EACH ROW EXECUTE FUNCTION forbid_review_event_changes();
