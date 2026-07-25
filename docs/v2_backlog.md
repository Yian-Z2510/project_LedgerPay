# LedgerPay V2 Optimization Backlog

**Status:** Prioritized future-work backlog  
**Related document:** `ledgerpay_api_design.md`

---

## 1. Purpose

This document records capabilities intentionally deferred from LedgerPay v1.

The backlog exists to show that these areas were considered and deliberately postponed based on:

- implementation complexity;
- project scope;
- current sandbox usage;
- risk;
- absence of a concrete v1 requirement.

Priority reflects engineering value and risk reduction, not a committed delivery schedule.

---

## 2. Priority 1 — Reliability and Concurrency

These items address data races, duplicate processing, operational visibility, and webhook-worker scalability.

## 2.1 Concurrent Payment Simulation Protection

### Current v1 limitation

Two callers could attempt to simulate the same `PENDING` Payment at nearly the same time.

v1 relies on normal transaction behaviour and does not explicitly serialize this manual sandbox action.

### V2 direction

Use one of:

- `SELECT ... FOR UPDATE` on the Payment row; or
- a conditional atomic update:

```sql
UPDATE payment
SET status = :terminal_status,
    failure_code = :failure_code,
    completed_at = now()
WHERE id = :payment_id
  AND status = 'PENDING'
RETURNING *;
```

Exactly one caller should transition the Payment. A caller receiving no updated row returns `PAYMENT_INVALID_STATE`.

### Expected benefit

- prevents two terminal transitions;
- prevents duplicate Order updates;
- prevents duplicate WebhookEvent creation;
- makes the state transition safe under concurrent requests.

---

## 2.2 Concurrent Refund Simulation Protection

### Current v1 limitation

Two callers could simulate the same `PENDING` Refund concurrently.

### V2 direction

Use a Refund row lock or conditional terminal-state update before modifying Payment summaries.

The Refund transition, Payment summary changes, Order state transition, and WebhookEvent insertion must remain one transaction.

### Expected benefit

- prevents double decrement of `pendingRefundAmount`;
- prevents double increment of `refundedAmount`;
- prevents duplicate terminal webhook events.

---

## 2.3 Concurrent Manual Webhook Retry Protection

### Current v1 limitation

Two callers may manually retry the same `FAILED` WebhookEvent simultaneously.

### V2 direction

Introduce one of:

- a row lock on the event;
- an atomic claim state;
- a short-lived retry lease;
- a dedicated delivery-attempt record with uniqueness protection.

A second caller should receive a conflict when another retry is already in progress.

### Expected benefit

- avoids duplicate simultaneous HTTP requests;
- keeps attempt metadata accurate;
- improves operational predictability.

---

## 2.4 Multiple Webhook Workers with Safe Claiming

### Current v1 limitation

v1 uses one polling worker. This is simple but limits throughput and creates one processing bottleneck.

### V2 direction

Allow multiple workers to claim due events safely using:

```sql
SELECT ...
FROM webhook_event
WHERE status = 'PENDING'
  AND next_attempt_at <= now()
FOR UPDATE SKIP LOCKED
LIMIT :batch_size;
```

Possible additional fields:

```text
claimed_at
claimed_by
lease_expires_at
next_attempt_at
```

A lease-expiry mechanism should recover events abandoned by crashed workers.

### Expected benefit

- horizontal worker scaling;
- safe parallel delivery;
- crash recovery;
- reduced duplicate concurrent delivery.

---

## 2.5 WebhookDeliveryAttempt Table

### Current v1 limitation

`WebhookEvent` stores only aggregate metadata:

- `attemptCount`;
- `lastAttemptAt`;
- `lastFailureCode`;
- `deliveredAt`.

It does not preserve every attempt.

### V2 direction

Add a `webhook_delivery_attempt` table containing:

```text
id
webhook_event_id
attempt_number
destination_url
started_at
completed_at
http_status
failure_code
response_duration_ms
response_body_excerpt
worker_id
created_at
```

Sensitive response data should be bounded and redacted.

### Expected benefit

- complete retry audit history;
- better production debugging;
- delivery-latency metrics;
- separation of immutable event data from mutable delivery operations.

---

## 2.6 Explicit Retry Scheduling

### Current v1 limitation

v1 uses a fixed 30-second retry interval.

### V2 direction

Store `nextAttemptAt` and use configurable exponential backoff with bounded jitter.

Example:

```text
attempt 1 -> +30 seconds
attempt 2 -> +2 minutes
attempt 3 -> +10 minutes
```

### Expected benefit

- avoids synchronized retry spikes;
- reduces load on failing Merchant endpoints;
- supports efficient due-event queries.

---

## 2.7 Delivery Circuit Breaker per Merchant

### Problem

A persistently failing Merchant endpoint can consume worker capacity.

### V2 direction

Track recent Merchant delivery failures and temporarily pause automatic delivery after a threshold.

Manual retry and Merchant configuration updates can trigger recovery checks.

### Expected benefit

- prevents one Merchant from monopolizing workers;
- reduces unnecessary network traffic;
- improves multi-tenant fairness.

---

## 3. Priority 2 — Security and Operations

These items improve credential lifecycle, webhook authenticity, account recovery, and public-endpoint protection.

## 3.1 Multiple Active API Keys

### Current v1 limitation

Each Merchant has one `api_key_hash`. Rotation immediately invalidates the old key.

### V2 direction

Create a separate `merchant_api_key` table:

```text
id
merchant_id
key_prefix
key_hash
status
created_at
expires_at
revoked_at
last_used_at
```

### Expected benefit

- separate credentials for different applications;
- safer operational rotation;
- individual key revocation;
- usage auditing.

---

## 3.2 Graceful API-Key Rotation

### Current v1 limitation

Old credentials stop working immediately.

### V2 direction

Allow an overlap period:

```text
new key = ACTIVE
old key = EXPIRING
old key remains valid until expiresAt
```

The Merchant can confirm deployment before revoking the old key.

### Expected benefit

- avoids service interruption;
- supports controlled production rollouts;
- reduces emergency rollback risk.

---

## 3.3 Test and Live Credential Separation

### Current v1 limitation

All keys use the sandbox-style `lp_test_` prefix and share one environment model.

### V2 direction

Separate:

```text
lp_test_
lp_live_
```

Test and live resources must not be mixed. Possible designs include:

- separate databases;
- separate schemas;
- an explicit environment column with strict scoping.

### Expected benefit

- safer production operations;
- prevents test data from affecting live data;
- clearer Merchant integration workflows.

---

## 3.4 Webhook Signatures

### Current v1 limitation

Merchants cannot cryptographically verify that a request came from LedgerPay or that the payload was unchanged.

### V2 direction

Generate a per-Merchant webhook secret and sign:

```text
timestamp + "." + raw_request_body
```

Use HMAC-SHA256 and send headers such as:

```http
LedgerPay-Signature: t=<timestamp>,v1=<signature>
LedgerPay-Event-Id: evt_<uuid>
```

Verification guidance should include:

- constant-time comparison;
- timestamp tolerance;
- replay protection;
- secret rotation.

### Expected benefit

- origin authentication;
- payload-integrity verification;
- protection against forged webhook requests.

---

## 3.5 Merchant Reactivation and Administrative Recovery

### Current v1 limitation

`ACTIVE -> INACTIVE` is one-way.

### V2 direction

Introduce an administrative recovery workflow with:

- authorization separate from Merchant API keys;
- reason and actor audit fields;
- explicit checks before reactivation;
- optional API-key regeneration.

### Expected benefit

- supports accidental deactivation recovery;
- provides controlled support operations;
- preserves an audit trail.

---

## 3.6 Merchant Registration Abuse Protection

### Current v1 limitation

`POST /api/v1/merchants` is unauthenticated and has no production-grade abuse controls.

### V2 direction

Consider:

- IP and account rate limiting;
- CAPTCHA;
- email verification;
- invitation codes;
- platform-admin approval;
- disposable-email controls;
- registration quotas.

### Expected benefit

- reduces spam Merchant creation;
- protects database and compute capacity;
- improves public deployment safety.

---

## 3.7 Production Rate Limiting

### V2 direction

Apply limits by:

- API key;
- Merchant;
- IP address;
- endpoint category.

Return:

```http
429 Too Many Requests
Retry-After: <seconds>
```

Use tighter controls for:

- Merchant registration;
- API-key rotation;
- simulation endpoints;
- manual webhook retry.

---

## 3.8 Secret and Credential Operational Hardening

Possible additions:

- API-key prefix lookup plus hash comparison;
- KMS-backed webhook-secret encryption;
- key-use timestamps;
- suspicious-use alerts;
- administrative revocation;
- audit logging;
- secure log redaction.

---

## 3.9 Administrative Observability

Introduce internal operational views and metrics for:

- active and inactive Merchants;
- Payment and Refund transition failures;
- pending and failed webhook counts;
- worker lag;
- delivery latency;
- retry success rate;
- oldest pending event;
- deactivation blockers.

---

## 4. Priority 3 — Product and API Expansion

These items expand usability and payment-domain coverage after the core lifecycle is stable.

## 4.1 Cursor-Based Pagination

### Current v1 limitation

List endpoints return all matching resources.

### V2 direction

Use cursor pagination rather than offset pagination.

Example:

```http
GET /api/v2/orders?limit=50&startingAfter=ord_<uuid>
```

Response:

```json
{
  "data": [],
  "hasMore": false,
  "nextCursor": null
}
```

### Expected benefit

- predictable performance on large datasets;
- stable traversal while new rows are inserted;
- bounded response size.

---

## 4.2 Filtering and Sorting

Possible filters:

```text
status
createdAtFrom
createdAtTo
orderId
paymentId
eventType
webhook status
```

Custom sorting should be restricted to indexed, documented fields.

---

## 4.3 Merchant-Level Search Endpoints

Potential endpoints:

```text
GET /api/v2/payments
GET /api/v2/refunds
GET /api/v2/webhook-events
```

These would support Merchant-wide operational queries without requiring a parent resource ID.

---

## 4.4 External Merchant Order Reference

### Current v1 limitation

The Merchant must store LedgerPay's `orderId` and maintain its own mapping.

### V2 direction

Add a Merchant-scoped external reference:

```text
merchant_order.external_reference
UNIQUE (merchant_id, external_reference)
```

### Expected benefit

- easier reconciliation;
- simpler Merchant support workflows;
- direct lookup using the Merchant's own identifier.

---

## 4.5 Multiple Currencies

### V2 direction

Support an explicit set of ISO-4217 currencies with per-currency minor-unit validation.

The design must avoid assuming every currency has two decimal places.

Potential additions:

- currency metadata;
- amount-formatting utilities;
- currency-aware validation;
- stricter Order and Payment equality rules.

Foreign exchange remains a separate product decision.

---

## 4.6 Authorization and Capture

### Current v1 limitation

Payment moves directly from `PENDING` to `SUCCEEDED` or `FAILED`.

### V2 direction

Introduce states such as:

```text
REQUIRES_AUTHORIZATION
AUTHORIZED
CAPTURED
CANCELLED
FAILED
```

Potential operations:

```text
authorize
capture
void
```

Partial capture requires additional amount tracking and idempotency rules.

---

## 4.7 Split Payments and Instalments

### Current v1 limitation

One successful Payment must equal the full Order amount.

### V2 direction

Permit multiple successful Payments whose captured totals do not exceed the Order amount.

This requires redesigned:

- Order payment summaries;
- paid and partially paid states;
- concurrency constraints;
- refund allocation;
- unique-index rules.

This should not be added as a small extension to the current model.

---

## 4.8 Multiple Webhook Endpoints

### V2 direction

Replace one `merchant.webhook_url` with a `webhook_endpoint` table.

Possible fields:

```text
id
merchant_id
url
status
subscribed_event_types
secret
created_at
updated_at
```

Each business event may require one delivery record per subscribed endpoint.

---

## 4.9 Strict Per-Resource Webhook Ordering

### Current v1 limitation

LedgerPay does not guarantee delivery order.

### V2 direction

Only add ordering when a concrete integration requires it.

Possible strategies:

- partition by Payment ID;
- sequence numbers per resource;
- block later events until an earlier event is delivered or dead-lettered;
- Merchant-side version checks.

### Trade-off

Strict ordering can create head-of-line blocking. One failing event may delay every later event in the same partition.

---

## 4.10 Message Broker Integration

### Current v1 design

PostgreSQL acts as a simplified transactional outbox and work queue.

### V2 direction

Introduce Kafka, RabbitMQ, SQS, or another broker only when required by throughput or architecture.

The database transaction still needs an outbox bridge; publishing directly to a broker inside a database transaction does not make the operation atomic.

---

## 4.11 Merchant Users and Role-Based Access

Possible future roles:

```text
OWNER
DEVELOPER
SUPPORT
VIEWER
```

This requires:

- interactive login;
- password or identity-provider integration;
- sessions or user JWTs;
- role-based authorization;
- user audit records.

Merchant API-key authentication remains separate from dashboard-user authentication.

---

## 4.12 Production-Grade Deletion and Retention Policies

LedgerPay v1 never physically deletes financial lifecycle records.

Future work may define:

- retention periods;
- legal holds;
- personal-data anonymization;
- audit-log retention;
- Merchant account closure;
- backup deletion policies.

Financial-history integrity must be preserved.

---

## 5. Exactly-Once Position

LedgerPay should not promise distributed exactly-once webhook delivery.

The ambiguity cannot be removed by the sender alone:

```text
Merchant commits processing
-> acknowledgement is lost
-> LedgerPay cannot know whether processing occurred
```

The practical integration model is:

```text
at-least-once delivery
+ stable event ID
+ idempotent Merchant handler
+ Merchant-side inbox/deduplication table
```

A Merchant can obtain effectively-once business effects by inserting `event.id` into an inbox table with a unique constraint and applying the business update in the same local database transaction.

Distributed two-phase commit is not an intended LedgerPay roadmap item for normal external HTTP webhooks.

---

## 6. Suggested Delivery Sequence

A sensible V2 implementation sequence is:

1. concurrent Payment and Refund simulation protection;
2. concurrent manual-retry protection;
3. WebhookDeliveryAttempt history;
4. explicit retry scheduling;
5. multi-worker event claiming;
6. webhook signatures;
7. multiple API keys and graceful rotation;
8. registration rate limiting and account recovery;
9. cursor pagination and Merchant-level search;
10. larger payment-product expansions only after the core platform is stable.

This order prioritizes correctness and reliability before adding breadth.
