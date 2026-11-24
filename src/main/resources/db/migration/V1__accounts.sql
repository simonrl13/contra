CREATE TABLE accounts (
    id          UUID            PRIMARY KEY,
    owner_name  VARCHAR(100)    NOT NULL,
    currency    VARCHAR(3)      NOT NULL,
    balance     NUMERIC(19, 2)  NOT NULL,
    created_at  TIMESTAMPTZ     NOT NULL,
    version     BIGINT          NOT NULL,

    -- Last line of defence: even a buggy service can't commit an overdraft.
    CONSTRAINT accounts_balance_not_negative CHECK (balance >= 0),
    CONSTRAINT accounts_currency_iso_format  CHECK (currency ~ '^[A-Z]{3}$')
);
