\set ON_ERROR_STOP on

BEGIN;

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '5min';

-- Prevent overlapping cleanup jobs while keeping the lock transaction-scoped.
SELECT pg_advisory_xact_lock(hashtext('ledgerpay-demo-cleanup'));

CREATE TEMPORARY TABLE cleanup_demo_merchant_ids (
    id UUID PRIMARY KEY
) ON COMMIT DROP;

-- Match only merchants created by the production browser demo flow.
INSERT INTO cleanup_demo_merchant_ids (id)
SELECT id
FROM merchant
WHERE created_at < CURRENT_TIMESTAMP - make_interval(days => :retention_days)
  AND name = 'LedgerPay Demo'
  AND email ~ '^ledgerpay-demo-[0-9]+-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}@example[.]com$'
  AND webhook_url = 'http://webhook-receiver:9000/webhook'
FOR UPDATE;

SELECT
    (SELECT COUNT(*) FROM cleanup_demo_merchant_ids) AS eligible_merchants,
    (SELECT COUNT(*) FROM webhook_event WHERE merchant_id IN (SELECT id FROM cleanup_demo_merchant_ids)) AS eligible_webhook_events,
    (SELECT COUNT(*) FROM refund WHERE merchant_id IN (SELECT id FROM cleanup_demo_merchant_ids)) AS eligible_refunds,
    (SELECT COUNT(*) FROM payment WHERE merchant_id IN (SELECT id FROM cleanup_demo_merchant_ids)) AS eligible_payments,
    (SELECT COUNT(*) FROM merchant_order WHERE merchant_id IN (SELECT id FROM cleanup_demo_merchant_ids)) AS eligible_orders;

\if :execute_cleanup
DELETE FROM webhook_event
WHERE merchant_id IN (SELECT id FROM cleanup_demo_merchant_ids);

DELETE FROM refund
WHERE merchant_id IN (SELECT id FROM cleanup_demo_merchant_ids);

DELETE FROM payment
WHERE merchant_id IN (SELECT id FROM cleanup_demo_merchant_ids);

DELETE FROM merchant_order
WHERE merchant_id IN (SELECT id FROM cleanup_demo_merchant_ids);

DELETE FROM merchant
WHERE id IN (SELECT id FROM cleanup_demo_merchant_ids);

COMMIT;
\echo 'Demo cleanup committed.'
\else
ROLLBACK;
\echo 'Dry run complete; no rows were deleted.'
\endif
