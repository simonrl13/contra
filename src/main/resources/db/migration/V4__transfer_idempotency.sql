-- Idempotency: every transfer remembers the client's Idempotency-Key and a SHA-256 of the request.
-- The UNIQUE constraint is what really stops a double transfer: two requests with the same key can
-- both pass the "already seen?" lookup, but only one of them can insert this row.
-- NOT NULL without a default is fine here: the table has no rows in any environment yet.
ALTER TABLE transfers
    ADD COLUMN idempotency_key VARCHAR(100) NOT NULL,
    ADD COLUMN request_hash    VARCHAR(64)  NOT NULL,
    ADD CONSTRAINT transfers_idempotency_key_unique UNIQUE (idempotency_key);
