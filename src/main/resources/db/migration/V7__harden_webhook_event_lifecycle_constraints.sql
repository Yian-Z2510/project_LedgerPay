ALTER TABLE webhook_event
ADD CONSTRAINT ck_webhook_event_delivered_requires_attempt
    CHECK (status <> 'DELIVERED' OR attempt_count >= 1),
ADD CONSTRAINT ck_webhook_event_pending_failure_consistency
    CHECK (
        status <> 'PENDING'
        OR (
            attempt_count = 0
            AND last_failure_code IS NULL
        )
        OR (
            attempt_count > 0
            AND last_failure_code IN ('HTTP_ERROR', 'CONNECTION_TIMEOUT')
        )
    ),
ADD CONSTRAINT ck_webhook_event_remote_failure_requires_attempt
    CHECK (
        last_failure_code IS NULL
        OR last_failure_code NOT IN ('HTTP_ERROR', 'CONNECTION_TIMEOUT')
        OR attempt_count > 0
    );
