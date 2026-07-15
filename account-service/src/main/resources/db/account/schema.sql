CREATE TABLE IF NOT EXISTS accounts (
    account_id VARCHAR(128) PRIMARY KEY,
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_accounts_currency_length CHECK (CHAR_LENGTH(currency) = 3),
    CONSTRAINT uk_accounts_id_currency UNIQUE (account_id, currency)
);

CREATE TABLE IF NOT EXISTS account_transactions (
    event_id VARCHAR(128) PRIMARY KEY,
    account_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(10) NOT NULL,
    amount DECIMAL(38, 18) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    event_timestamp TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    applied_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_transaction_account_currency
        FOREIGN KEY (account_id, currency) REFERENCES accounts(account_id, currency),
    CONSTRAINT ck_transaction_type
        CHECK (event_type IN ('CREDIT', 'DEBIT')),
    CONSTRAINT ck_transaction_amount
        CHECK (amount > 0),
    CONSTRAINT ck_transaction_currency_length
        CHECK (CHAR_LENGTH(currency) = 3)
);

CREATE INDEX IF NOT EXISTS ix_account_transactions_account_time
    ON account_transactions (account_id, event_timestamp, event_id);

CREATE INDEX IF NOT EXISTS ix_account_transactions_account_currency
    ON account_transactions (account_id, currency);
