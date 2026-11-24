CREATE TABLE transfers (
    id               UUID            PRIMARY KEY,
    from_account_id  UUID            NOT NULL REFERENCES accounts (id),
    to_account_id    UUID            NOT NULL REFERENCES accounts (id),
    amount           NUMERIC(19, 2)  NOT NULL,
    currency         VARCHAR(3)      NOT NULL,
    status           VARCHAR(20)     NOT NULL,
    created_at       TIMESTAMPTZ     NOT NULL,

    CONSTRAINT transfers_amount_positive      CHECK (amount > 0),
    CONSTRAINT transfers_different_accounts   CHECK (from_account_id <> to_account_id),
    CONSTRAINT transfers_status_known         CHECK (status IN ('COMPLETED'))
);

-- V2 left ledger_entries.transfer_id without a foreign key because this table didn't exist yet.
-- Consequence: a transfer row must be inserted before its ledger entries.
ALTER TABLE ledger_entries
    ADD CONSTRAINT ledger_entries_transfer_id_fk FOREIGN KEY (transfer_id) REFERENCES transfers (id);
