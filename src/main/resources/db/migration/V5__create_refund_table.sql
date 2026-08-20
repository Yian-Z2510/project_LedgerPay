CREATE TABLE refund (
    id UUID NOT NULL PRIMARY KEY,
    payment_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    reason_code VARCHAR(50) NOT NULL,
    failure_code VARCHAR(50),
    idempotency_key VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_refund_merchant
        FOREIGN KEY (merchant_id)
        REFERENCES merchant (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_refund_payment
        FOREIGN KEY (payment_id, merchant_id)
        REFERENCES payment (id, merchant_id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_refund_id_merchant_id
        UNIQUE (id, merchant_id),
    CONSTRAINT uq_refund_merchant_id_idempotency_key
        UNIQUE (merchant_id, idempotency_key),
    CONSTRAINT ck_refund_amount_positive
        CHECK (amount > 0),
    CONSTRAINT ck_refund_currency
        CHECK (currency = 'EUR'),
    CONSTRAINT ck_refund_status
        CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_refund_reason_code
        CHECK (
            reason_code IN (
                'CUSTOMER_REQUEST',
                'DUPLICATE_CHARGE',
                'PRODUCT_NOT_RECEIVED',
                'OTHER'
            )
        ),
    CONSTRAINT ck_refund_failure_code
        CHECK (
            failure_code IS NULL
            OR failure_code = 'REFUND_PROCESSING_ERROR'
        ),
    CONSTRAINT ck_refund_idempotency_key_length
        CHECK (char_length(idempotency_key) BETWEEN 1 AND 100),
    CONSTRAINT ck_refund_lifecycle_consistency
        CHECK (
            (status IN ('PENDING', 'SUCCEEDED') AND failure_code IS NULL)
            OR
            (status = 'FAILED' AND failure_code IS NOT NULL)
        )
);

CREATE INDEX idx_refund_payment_id
ON refund (payment_id);

CREATE TRIGGER trg_refund_set_updated_at
BEFORE UPDATE ON refund
FOR EACH ROW
EXECUTE FUNCTION set_updated_at();
