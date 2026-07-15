CREATE TABLE IF NOT EXISTS gateway_events (
    event_id VARCHAR(128) PRIMARY KEY,
    account_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(10) NOT NULL,
    amount DECIMAL(38, 18) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    event_timestamp TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    metadata_json CLOB NOT NULL,
    application_status VARCHAR(20) NOT NULL,
    received_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    last_attempt_at TIMESTAMP(9) WITH TIME ZONE,
    applied_at TIMESTAMP(9) WITH TIME ZONE,
    attempt_count INTEGER NOT NULL,
    last_failure_code VARCHAR(40),
    last_failure_message VARCHAR(300),
    version BIGINT NOT NULL,
    CONSTRAINT ck_gateway_event_type
        CHECK (event_type IN ('CREDIT', 'DEBIT')),
    CONSTRAINT ck_gateway_amount
        CHECK (amount > 0),
    CONSTRAINT ck_gateway_currency_length
        CHECK (CHAR_LENGTH(currency) = 3),
    CONSTRAINT ck_gateway_application_status
        CHECK (application_status IN ('RECEIVED', 'APPLIED', 'APPLY_FAILED')),
    CONSTRAINT ck_gateway_failure_code
        CHECK (last_failure_code IS NULL
               OR last_failure_code IN ('RETRYABLE_UNCONFIRMED', 'TERMINAL_CONFLICT', 'CONTRACT_ERROR')),
    CONSTRAINT ck_gateway_attempt_count
        CHECK (attempt_count >= 0),
    CONSTRAINT ck_gateway_version
        CHECK (version >= 0)
);

CREATE INDEX IF NOT EXISTS ix_gateway_events_account_time
    ON gateway_events (account_id, event_timestamp, event_id);
