CREATE TABLE webhook_event (
    id UUID NOT NULL PRIMARY KEY,
    merchant_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payment_id UUID NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    last_failure_code VARCHAR(50),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_webhook_event_merchant
        FOREIGN KEY (merchant_id)
        REFERENCES merchant (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_webhook_event_payment
        FOREIGN KEY (payment_id, merchant_id)
        REFERENCES payment (id, merchant_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_webhook_event_type
        CHECK (event_type IN ('PAYMENT_SUCCEEDED', 'PAYMENT_FAILED')),
    CONSTRAINT ck_webhook_event_status
        CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED')),
    CONSTRAINT ck_webhook_event_attempt_count_non_negative
        CHECK (attempt_count >= 0),
    CONSTRAINT ck_webhook_event_last_failure_code
        CHECK (
            last_failure_code IS NULL
            OR last_failure_code IN (
                'WEBHOOK_URL_NOT_CONFIGURED',
                'CONNECTION_TIMEOUT',
                'HTTP_ERROR',
                'PROCESSING_ERROR'
            )
        ),
    CONSTRAINT ck_webhook_event_delivery_time_consistency
        CHECK (
            (status = 'DELIVERED' AND delivered_at IS NOT NULL)
            OR
            (status <> 'DELIVERED' AND delivered_at IS NULL)
        ),
    CONSTRAINT ck_webhook_event_attempt_time_consistency
        CHECK (
            (attempt_count = 0 AND last_attempt_at IS NULL)
            OR
            (attempt_count > 0 AND last_attempt_at IS NOT NULL)
        ),
    CONSTRAINT ck_webhook_event_failed_has_failure_code
        CHECK (status <> 'FAILED' OR last_failure_code IS NOT NULL)
);

CREATE UNIQUE INDEX ux_webhook_payment_event
ON webhook_event (payment_id, event_type)
WHERE payment_id IS NOT NULL;

CREATE INDEX idx_webhook_event_merchant_id
ON webhook_event (merchant_id);

CREATE INDEX idx_webhook_event_status
ON webhook_event (status);

CREATE TRIGGER trg_webhook_event_set_updated_at
BEFORE UPDATE ON webhook_event
FOR EACH ROW
EXECUTE FUNCTION set_updated_at();
