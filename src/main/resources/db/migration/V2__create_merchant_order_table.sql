CREATE FUNCTION set_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

DROP TRIGGER trg_merchant_set_updated_at ON merchant;

CREATE TRIGGER trg_merchant_set_updated_at
BEFORE UPDATE ON merchant
FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

DROP FUNCTION set_merchant_updated_at();

CREATE TABLE merchant_order (
    id UUID NOT NULL PRIMARY KEY,
    merchant_id UUID NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'CREATED',
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_merchant_order_merchant
        FOREIGN KEY (merchant_id)
        REFERENCES merchant (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_merchant_order_amount_positive
        CHECK (amount > 0),
    CONSTRAINT ck_merchant_order_currency
        CHECK (currency = 'EUR'),
    CONSTRAINT ck_merchant_order_status
        CHECK (
            status IN (
                'CREATED',
                'PAYMENT_PENDING',
                'PAID',
                'PARTIALLY_REFUNDED',
                'REFUNDED',
                'CANCELLED'
            )
        ),
    CONSTRAINT ck_merchant_order_status_cancelled_at
        CHECK (
            (status = 'CANCELLED' AND cancelled_at IS NOT NULL)
            OR
            (status <> 'CANCELLED' AND cancelled_at IS NULL)
        ),
    CONSTRAINT uq_merchant_order_id_merchant_id
        UNIQUE (id, merchant_id)
);

CREATE INDEX idx_merchant_order_merchant_id_created_at
ON merchant_order (merchant_id, created_at DESC);

CREATE TRIGGER trg_merchant_order_set_updated_at
BEFORE UPDATE ON merchant_order
FOR EACH ROW
EXECUTE FUNCTION set_updated_at();
