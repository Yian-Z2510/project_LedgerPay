ALTER TABLE webhook_event
ADD COLUMN refund_id UUID;

ALTER TABLE webhook_event
ALTER COLUMN payment_id DROP NOT NULL;

ALTER TABLE webhook_event
DROP CONSTRAINT ck_webhook_event_type;

ALTER TABLE webhook_event
ADD CONSTRAINT ck_webhook_event_type
    CHECK (
        event_type IN (
            'PAYMENT_SUCCEEDED',
            'PAYMENT_FAILED',
            'REFUND_SUCCEEDED',
            'REFUND_FAILED'
        )
    ),
ADD CONSTRAINT fk_webhook_event_refund
    FOREIGN KEY (refund_id, merchant_id)
    REFERENCES refund (id, merchant_id)
    ON DELETE RESTRICT,
ADD CONSTRAINT ck_webhook_event_exactly_one_source
    CHECK (
        (payment_id IS NOT NULL AND refund_id IS NULL)
        OR
        (payment_id IS NULL AND refund_id IS NOT NULL)
    ),
ADD CONSTRAINT ck_webhook_event_type_source
    CHECK (
        (
            event_type IN ('PAYMENT_SUCCEEDED', 'PAYMENT_FAILED')
            AND payment_id IS NOT NULL
            AND refund_id IS NULL
        )
        OR
        (
            event_type IN ('REFUND_SUCCEEDED', 'REFUND_FAILED')
            AND payment_id IS NULL
            AND refund_id IS NOT NULL
        )
    );

CREATE UNIQUE INDEX ux_webhook_refund_event
ON webhook_event (refund_id, event_type)
WHERE refund_id IS NOT NULL;
