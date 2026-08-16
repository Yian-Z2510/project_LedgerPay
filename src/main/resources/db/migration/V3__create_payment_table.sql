CREATE TABLE payment (
    id UUID NOT NULL PRIMARY KEY,
    merchant_order_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    idempotency_key VARCHAR(100) NOT NULL,
    refunded_amount BIGINT NOT NULL DEFAULT 0,
    pending_refund_amount BIGINT NOT NULL DEFAULT 0,
    failure_code VARCHAR(50),
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_payment_merchant
        FOREIGN KEY (merchant_id)
        REFERENCES merchant (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_payment_merchant_order
        FOREIGN KEY (merchant_order_id, merchant_id)
        REFERENCES merchant_order (id, merchant_id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_payment_id_merchant_id
        UNIQUE (id, merchant_id),
    CONSTRAINT uq_payment_merchant_id_idempotency_key
        UNIQUE (merchant_id, idempotency_key),
    CONSTRAINT ck_payment_amount_positive
        CHECK (amount > 0),
    CONSTRAINT ck_payment_currency
        CHECK (currency = 'EUR'),
    CONSTRAINT ck_payment_status
        CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_payment_failure_code
        CHECK (
            failure_code IS NULL
            OR failure_code IN ('PAYMENT_DECLINED', 'PROCESSING_ERROR')
        ),
    CONSTRAINT ck_payment_idempotency_key_length
        CHECK (char_length(idempotency_key) BETWEEN 1 AND 100),
    CONSTRAINT ck_payment_refunded_amount_non_negative
        CHECK (refunded_amount >= 0),
    CONSTRAINT ck_payment_pending_refund_amount_non_negative
        CHECK (pending_refund_amount >= 0),
    CONSTRAINT ck_payment_refund_amounts_within_amount
        CHECK (refunded_amount + pending_refund_amount <= amount),
    CONSTRAINT ck_payment_lifecycle_consistency
        CHECK (
            (
                status = 'PENDING'
                AND failure_code IS NULL
                AND completed_at IS NULL
            )
            OR
            (
                status = 'SUCCEEDED'
                AND failure_code IS NULL
                AND completed_at IS NOT NULL
            )
            OR
            (
                status = 'FAILED'
                AND failure_code IS NOT NULL
                AND completed_at IS NOT NULL
            )
        )
);

CREATE UNIQUE INDEX ux_payment_one_pending_per_order
ON payment (merchant_order_id)
WHERE status = 'PENDING';

CREATE UNIQUE INDEX ux_payment_one_succeeded_per_order
ON payment (merchant_order_id)
WHERE status = 'SUCCEEDED';

CREATE INDEX idx_payment_merchant_order_id
ON payment (merchant_order_id);

CREATE TRIGGER trg_payment_set_updated_at
BEFORE UPDATE ON payment
FOR EACH ROW
EXECUTE FUNCTION set_updated_at();
