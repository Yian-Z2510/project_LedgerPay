# LedgerPay Engineering Decisions

This document summarizes the most important backend decisions. The
complete endpoint contract remains in [`api_design.md`](api_design.md), and the
physical schema remains in [`database_design.md`](database_design.md).

## 1. Payment lifecycle and idempotency

A Payment is an immutable attempt against one Merchant-owned Order:

```text
PENDING -> SUCCEEDED
PENDING -> FAILED
```

Creation copies amount and currency from the Order, so clients cannot create a
Payment whose value disagrees with its Order. The service locks the
Merchant-owned Order row, rechecks eligibility, creates the `PENDING` Payment,
and moves the Order to `PAYMENT_PENDING` in one transaction. A successful
simulation moves the Order to `PAID`; a failed Payment remains historical and a
new attempt may later be created.

`completedAt` records the terminal Payment transition. It does not change when
later Refund operations update `refundedAmount` or `pendingRefundAmount`, which
is why it is distinct from `updatedAt`.

Payment creation requires an `Idempotency-Key`. Its namespace is the
authenticated Merchant and its request identity is `orderId`:

- same Merchant, key, and Order: return the historical Payment as a replay;
- same Merchant and key but another Order: return `IDEMPOTENCY_CONFLICT`;
- another Merchant may reuse the same opaque key independently.

The service performs a fast historical lookup before opening the bounded write
transaction, then performs a second lookup after acquiring the Order lock. The
database `UNIQUE (merchant_id, idempotency_key)` constraint is the final guard
when concurrent requests race. A unique-constraint loser reloads the winner and
applies the same replay-versus-conflict comparison. Partial unique indexes also
prevent more than one `PENDING` or `SUCCEEDED` Payment per Order.

This layered design is deliberate. An in-memory idempotency cache would not
survive process restart or protect multiple application instances. A single
lookup before the transaction leaves a check-then-insert race, while treating
all key reuse as success could return a Payment created for a different Order.

Payment simulation, its Order transition, and creation of the terminal
WebhookEvent commit together. The manual simulation endpoint does not lock the
Payment row; concurrent simulation of the same Payment is an explicit v1
limitation rather than a capability claim.

## 2. Refund capacity and concurrency

A Refund follows:

```text
PENDING -> SUCCEEDED
PENDING -> FAILED
```

Refundable capacity is calculated from persisted Payment aggregates:

```text
available = amount - refundedAmount - pendingRefundAmount
```

`refundedAmount` represents completed value returned to the customer.
`pendingRefundAmount` is a reservation for accepted Refunds that have not yet
completed. Without that reservation, two individually valid Refund requests
could both observe the same remaining balance and together over-refund the
Payment.

For a new Refund, the service:

1. checks historical Merchant-scoped idempotency;
2. starts a bounded write transaction;
3. locks the Merchant-owned Payment with `PESSIMISTIC_WRITE`;
4. repeats the idempotency lookup after acquiring the lock;
5. recalculates capacity from the locked row;
6. inserts a `PENDING` Refund and increases `pendingRefundAmount` atomically.

The second lookup matters because another transaction may have created the same
request while this transaction waited for the Payment lock. As with Payments,
`UNIQUE (merchant_id, idempotency_key)` is the final race-condition guard, and
the full Refund identity is `paymentId + amount + reasonCode`.

A normal read followed by a write would allow concurrent requests to validate
the same balance, and checking only `refundedAmount` would ignore accepted work
that has not completed. A JVM lock would not protect another application
instance. The database row lock therefore serializes the short capacity
decision for one Payment while allowing unrelated Payments to proceed.

On success, one transaction moves the reservation from
`pendingRefundAmount` to `refundedAmount`, marks the Refund `SUCCEEDED`, updates
the Order to `PARTIALLY_REFUNDED` or `REFUNDED`, and persists a
`refund.succeeded` event. On failure, the transaction releases the reservation,
keeps `refundedAmount` unchanged, marks the Refund `FAILED`, and persists a
`refund.failed` event. Any failure rolls back the complete unit.

Payment summary completion uses conditional PostgreSQL `UPDATE ... RETURNING`
statements rather than a stale Java read-modify-save sequence. This avoids lost
updates when different accepted Refunds complete concurrently. The manual
simulation endpoint does not explicitly lock the same Refund row, so duplicate
concurrent simulation of one Refund remains a documented v1 limitation.

## 3. Webhook lifecycle and delivery

Payment and Refund terminal transitions create immutable WebhookEvent payloads
with one of four public types:

```text
payment.succeeded
payment.failed
refund.succeeded
refund.failed
```

Each event starts as `PENDING` and is committed in the same database transaction
as its business transition. External HTTP is deliberately performed later, so
a slow or unavailable Merchant endpoint cannot hold open the payment/refund
transaction.

The enabled worker polls every 5 seconds, processes at most 50 due event IDs,
and allows three total automatic HTTP attempts. A failed real HTTP attempt
remains eligible after a fixed 10-second interval. Each request has a 10-second
timeout, redirects are not followed, and any `2xx` response is successful.

Attempt accounting is based on whether an HTTP request began:

- actual HTTP request: increment `attemptCount` and set `lastAttemptAt`;
- success: move to `DELIVERED` and set `deliveredAt`;
- HTTP/connection failure before attempt three: remain `PENDING`;
- third HTTP/connection failure: move to `FAILED`;
- no configured URL: move directly to `FAILED` with
  `WEBHOOK_URL_NOT_CONFIGURED` and no consumed attempt;
- pre-HTTP processing failure: move to `FAILED` with `PROCESSING_ERROR` and no
  consumed attempt.

A Merchant can query one owned event or the complete Payment event history,
which includes Payment and related Refund events. Manual retry is allowed only
for `FAILED` events and performs one synchronous request against the Merchant's
current webhook URL while preserving the same event ID and immutable payload.

Delivery is at least once, not exactly once. Merchants must deduplicate by the
stable event ID. One worker, aggregate attempt metadata, no strict event
ordering, and no same-event manual-retry lock are deliberate v1 limitations.

Inline HTTP delivery was rejected because it would couple a successful
Payment/Refund transaction to another service's latency and availability. An
in-memory fire-and-forget queue would lose work on restart. Exactly-once
delivery is not claimed because LedgerPay cannot distinguish a lost response
from a Merchant that processed the event before the acknowledgement was lost.

## 4. Deployment health and rollback

Application images use immutable full-Git-SHA tags. The EC2 deployment script
saves the current `IMAGE_TAG`, updates it atomically, pulls and starts the new
application images, waits for Compose health, and then verifies the public
HTTPS `/health` endpoint.

`docker compose up` alone is not considered success because it proves only that
the orchestration command completed, not that the public TLS-to-application path
works. A mutable `latest` tag was also avoided because it would make both the
deployed version and rollback target ambiguous.

If public health verification fails, the script restores the previous tag,
starts the previous application images, verifies rollback health, and returns a
failed deployment result. It never deletes the PostgreSQL named volume.

This rollback covers application images only. It cannot undo Flyway migrations,
so future schema changes must remain compatible with the previous application
image or use a staged migration or forward-fix strategy.
