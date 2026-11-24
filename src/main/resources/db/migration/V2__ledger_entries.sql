-- One row per money movement on one account. Amounts are signed: credits > 0, debits < 0,
-- so an account's balance is simply SUM(amount) of its entries.
CREATE TABLE ledger_entries (
    id           UUID            PRIMARY KEY,
    account_id   UUID            NOT NULL REFERENCES accounts (id),
    type         VARCHAR(20)     NOT NULL,
    amount       NUMERIC(19, 2)  NOT NULL,
    -- NULL for OPENING entries. The foreign key to transfers is added with the transfers table (Stage 3).
    transfer_id  UUID,
    created_at   TIMESTAMPTZ     NOT NULL,

    CONSTRAINT ledger_entries_type_known CHECK (type IN ('OPENING', 'TRANSFER_DEBIT', 'TRANSFER_CREDIT')),
    CONSTRAINT ledger_entries_sign_matches_type CHECK (
        (type = 'TRANSFER_DEBIT' AND amount < 0) OR (type <> 'TRANSFER_DEBIT' AND amount > 0)
    ),
    CONSTRAINT ledger_entries_transfer_matches_type CHECK (
        (type = 'OPENING') = (transfer_id IS NULL)
    )
);

-- Serves "entries of one account, newest first" (GET /accounts/{id}/transactions).
CREATE INDEX ledger_entries_account_id_created_at ON ledger_entries (account_id, created_at DESC);
