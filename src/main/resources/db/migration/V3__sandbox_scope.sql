-- LedgerGuard schema, version 3: private demo sandboxes.
-- Everyone created by one "Try the demo" click (customers and the analyst) shares a sandbox_id.
-- An analyst with a sandbox_id only sees and reviews payments made by people in that sandbox,
-- so visitors trying the demo at the same time never see each other's data.
-- Regular users have no sandbox_id; an analyst without one sees every payment, as at a real bank.
ALTER TABLE users ADD COLUMN sandbox_id UUID;
CREATE INDEX users_sandbox_idx ON users (sandbox_id) WHERE sandbox_id IS NOT NULL;
