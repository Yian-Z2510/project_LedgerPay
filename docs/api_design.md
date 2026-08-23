# LedgerPay API Design

**Version:** v1  
**Status:** Final design for implementation  
**Base path:** `/api/v1`  
**Primary implementation target:** Spring Boot + PostgreSQL

---

## 1. API Overview

LedgerPay is a sandbox payment lifecycle simulator. It models Merchant, Order, Payment, Refund, and WebhookEvent lifecycles without connecting to a real payment gateway or moving real money.

The v1 API is designed to demonstrate:

- API-key authentication;
- merchant-scoped resource isolation;
- payment and refund state transitions;
- request idempotency;
- transaction boundaries;
- targeted pessimistic locking;
- webhook outbox persistence;
- retryable at-least-once webhook delivery.

### 1.1 Core resources

| Resource | Public ID prefix | Purpose |
|---|---|---|
| Merchant | `mer_` | Organisation integrating with LedgerPay |
| Order | `ord_` | Merchant order awaiting payment |
| Payment | `pay_` | One payment attempt for an Order |
| Refund | `re_` | One refund request against a successful Payment |
| WebhookEvent | `evt_` | Immutable business-event payload awaiting delivery |

### 1.2 Resource ownership

Every business resource belongs to the authenticated Merchant.

```text
Merchant 1 -> N Orders
Order    1 -> N Payments
Payment  1 -> N Refunds
Payment  1 -> N WebhookEvents
Refund   1 -> N WebhookEvents
```

A client never supplies `merchantId`. Merchant identity is derived from the secret API key.

### 1.3 Public ID mapping

PostgreSQL stores UUID primary keys. The API adds a resource prefix dynamically.

```text
Database UUID:
550e8400-e29b-41d4-a716-446655440000

Payment API ID:
pay_550e8400-e29b-41d4-a716-446655440000
```

Incoming resource IDs are processed as follows:

1. validate the expected prefix;
2. remove the prefix;
3. parse the remaining value as a UUID;
4. perform a merchant-scoped database lookup.

An invalid prefix or malformed UUID returns `400 VALIDATION_ERROR`. A correctly formatted but missing or cross-merchant resource returns a resource-specific `404`.

---

## 2. Authentication

### 2.1 Authentication model

LedgerPay v1 uses one active secret API key per Merchant.

```http
Authorization: Bearer lp_test_<secret>
```

Only the Bearer scheme is supported. API keys must not be supplied through:

- query parameters;
- request bodies;
- cookies;
- `X-API-Key`;
- any alternative authorization scheme.

### 2.2 Unauthenticated endpoint

The only unauthenticated endpoint is:

```http
POST /api/v1/merchants
```

It creates a Merchant and issues the first plaintext API key. The plaintext key is returned once and is never retrievable later.

### 2.3 Authentication failures

The following cases all return the same response:

- missing `Authorization` header;
- unsupported authorization scheme;
- empty Bearer token;
- malformed API key;
- unknown API key;
- previously rotated API key;
- API key belonging to an `INACTIVE` Merchant.

```http
401 Unauthorized
```

```json
{
  "code": "UNAUTHORIZED",
  "message": "Invalid or missing API credentials."
}
```

LedgerPay does not reveal whether a Merchant or API key exists.

### 2.4 Inactive Merchant behaviour

After successful deactivation:

- `status` becomes `INACTIVE`;
- `deactivatedAt` is set;
- the API response is returned;
- the Merchant's API key becomes unusable.

Subsequent requests return the generic `401 UNAUTHORIZED` response.

### 2.5 No `403` in v1

Cross-merchant resource access is hidden using a resource-specific `404`, not `403`.

---

## 3. API Conventions

### 3.1 JSON and field naming

- Requests and responses use JSON.
- JSON fields use `camelCase`.
- Enum values use uppercase strings.
- Public webhook event types use lowercase dot-separated strings.
- Unknown request fields are rejected.

### 3.2 Content type

Endpoints with a JSON body require:

```http
Content-Type: application/json
```

LedgerPay v1 does not define a custom error response for a wrong or missing content type. Spring handles unsupported media types using its normal framework behaviour.

Malformed JSON, invalid field types, missing required fields, unknown fields, and invalid enum values return `400 VALIDATION_ERROR`.

### 3.3 Response shape

Single resources are returned directly:

```json
{
  "id": "ord_<uuid>",
  "amount": 1000,
  "currency": "EUR"
}
```

Lists are returned as direct arrays:

```json
[
  {
    "id": "ord_<uuid>"
  }
]
```

There is no `{ "data": ... }` envelope in v1.

### 3.4 Nullable fields

Nullable response fields are explicitly present with `null`.

### 3.5 Money

All monetary values are integer minor units.

```text
EUR 10.99 -> 1099
```

Rules:

- integer only;
- greater than zero where used as a request amount;
- no decimal values;
- no floating-point types.

LedgerPay v1 supports EUR only. Clients do not send `currency`; the server sets it to `EUR`.

### 3.6 Timestamps

- UTC only;
- ISO-8601 format;
- represented using Java `Instant`;
- clients cannot set audit timestamps.

Example:

```text
2026-07-25T20:30:00Z
```

### 3.7 String normalization

- outer whitespace is trimmed;
- interior whitespace is preserved;
- a required string that becomes empty after trimming is invalid;
- email is trimmed and converted to lowercase before persistence;
- idempotency keys are not normalized.

### 3.8 Create and replay status codes

Unless an endpoint-specific contract states otherwise, a newly created resource returns:

```http
201 Created
Location: <canonical resource URL>
```

The following are approved v1 exceptions to the `Location` header convention:

- `POST /api/v1/merchants` returns `201 Created` with a
  `CreateMerchantResponse` body and no `Location` header;
- `POST /api/v1/orders` returns `201 Created` with an `OrderResponse` body and
  no `Location` header;
- `POST /api/v1/payments` returns `201 Created` with a `PaymentResponse` body
  and no `Location` header.

A historical idempotent replay returns:

```http
200 OK
```

A replay does not require a `Location` header.

### 3.9 State actions and updates

Successful updates and state-transition actions return:

```http
200 OK
```

with the complete latest resource representation. v1 does not use `204 No Content` for these operations.

### 3.10 List behaviour

All v1 list endpoints:

- return direct arrays;
- default to `createdAt DESC`, unless otherwise specified;
- do not support pagination;
- do not support filtering;
- do not support custom sorting.

An existing parent with no child resources returns:

```http
200 OK
```

```json
[]
```

A missing or cross-merchant parent returns a resource-specific `404`.

---

## 4. Resource Representations

## 4.1 Merchant

```json
{
  "id": "mer_<uuid>",
  "name": "Alice Shop",
  "email": "alice@example.com",
  "status": "ACTIVE",
  "webhookUrl": "https://merchant.example.com/webhooks/ledgerpay",
  "deactivatedAt": null,
  "createdAt": "2026-07-25T20:00:00Z",
  "updatedAt": "2026-07-25T20:00:00Z"
}
```

The ordinary Merchant representation never contains:

- `apiKey`;
- `apiKeyHash`;
- a masked API key;
- `hasApiKey`.

The creation response includes a one-time `apiKey`. The rotation response returns only a new `apiKey`.

## 4.2 Order

```json
{
  "id": "ord_<uuid>",
  "amount": 1000,
  "currency": "EUR",
  "status": "CREATED",
  "cancelledAt": null,
  "createdAt": "2026-07-25T20:05:00Z",
  "updatedAt": "2026-07-25T20:05:00Z"
}
```

Order statuses:

```text
CREATED
PAYMENT_PENDING
PAID
PARTIALLY_REFUNDED
REFUNDED
CANCELLED
```

## 4.3 Payment

```json
{
  "id": "pay_<uuid>",
  "orderId": "ord_<uuid>",
  "amount": 1000,
  "currency": "EUR",
  "status": "SUCCEEDED",
  "refundedAmount": 200,
  "pendingRefundAmount": 300,
  "availableRefundAmount": 500,
  "failureCode": null,
  "completedAt": "2026-07-25T20:10:00Z",
  "createdAt": "2026-07-25T20:08:00Z",
  "updatedAt": "2026-07-25T20:25:00Z"
}
```

Payment statuses:

```text
PENDING
SUCCEEDED
FAILED
```

Payment failure codes:

```text
PAYMENT_DECLINED
PROCESSING_ERROR
```

`availableRefundAmount` is derived at response time:

```text
availableRefundAmount
= amount
- refundedAmount
- pendingRefundAmount
```

It is not stored as a database column.

`completedAt` is separate from `updatedAt` because later Refund operations can update Payment summary fields.

The response does not include `idempotencyKey`.

## 4.4 Refund

```json
{
  "id": "re_<uuid>",
  "paymentId": "pay_<uuid>",
  "amount": 300,
  "currency": "EUR",
  "reasonCode": "CUSTOMER_REQUEST",
  "status": "PENDING",
  "failureCode": null,
  "createdAt": "2026-07-25T20:20:00Z",
  "updatedAt": "2026-07-25T20:20:00Z"
}
```

Refund statuses:

```text
PENDING
SUCCEEDED
FAILED
```

Refund reason codes:

```text
CUSTOMER_REQUEST
DUPLICATE_CHARGE
PRODUCT_NOT_RECEIVED
OTHER
```

Persisted Refund simulation failure code:

```text
REFUND_PROCESSING_ERROR
```

A Refund does not expose `completedAt` in v1. A terminal Refund is not modified again, so its final `updatedAt` can be treated as its completion time.

The response does not include `idempotencyKey`.

## 4.5 WebhookEvent

```json
{
  "id": "evt_<uuid>",
  "type": "payment.succeeded",
  "status": "DELIVERED",
  "attemptCount": 2,
  "lastAttemptAt": "2026-07-25T20:31:00Z",
  "deliveredAt": "2026-07-25T20:31:00Z",
  "lastFailureCode": "CONNECTION_TIMEOUT",
  "createdAt": "2026-07-25T20:10:00Z",
  "data": {
    "payment": {
      "id": "pay_<uuid>",
      "orderId": "ord_<uuid>",
      "amount": 1000,
      "currency": "EUR",
      "status": "SUCCEEDED",
      "failureCode": null
    }
  }
}
```

Webhook statuses:

```text
PENDING
DELIVERED
FAILED
```

Public event types:

```text
payment.succeeded
payment.failed
refund.succeeded
refund.failed
```

Delivery failure codes:

```text
WEBHOOK_URL_NOT_CONFIGURED
CONNECTION_TIMEOUT
HTTP_ERROR
PROCESSING_ERROR
```

`lastFailureCode` may remain populated after later successful delivery. It represents the most recent historical delivery failure, not the current status.

---

## 5. Merchant API

## 5.1 Create Merchant

```http
POST /api/v1/merchants
```

### Purpose

Creates a Merchant and issues its first secret API key.

### Authentication

None.

### Request

```json
{
  "name": "Alice Shop",
  "email": "alice@example.com",
  "webhookUrl": "https://merchant.example.com/webhooks/ledgerpay"
}
```

Rules:

- `name`: required, trimmed, non-empty, maximum 100 characters;
- `email`: required, normalized to lowercase, maximum 254 characters;
- `webhookUrl`: optional and nullable;
- when present, `webhookUrl` must be a valid HTTP or HTTPS URL with a maximum length of 2048 characters;
- an empty-string webhook URL is invalid.

### Success

```http
201 Created
```

The response body is `CreateMerchantResponse`. A `Location` header is not required for Merchant registration.

```json
{
  "id": "mer_<uuid>",
  "name": "Alice Shop",
  "email": "alice@example.com",
  "status": "ACTIVE",
  "webhookUrl": "https://merchant.example.com/webhooks/ledgerpay",
  "deactivatedAt": null,
  "createdAt": "2026-07-25T20:00:00Z",
  "updatedAt": "2026-07-25T20:00:00Z",
  "apiKey": "lp_test_<secret>"
}
```

The plaintext key is returned only in this response.

### Errors

| Status | Code | Condition |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Invalid body or fields |
| 409 | `MERCHANT_EMAIL_ALREADY_EXISTS` | Normalized email already exists |

### Security note

This endpoint is intentionally unauthenticated for v1 sandbox bootstrap. Production-grade rate limiting, CAPTCHA, email verification, invitation codes, and platform-admin approval are out of scope.

---

## 5.2 Get Current Merchant

```http
GET /api/v1/merchant
```

### Authentication

Required.

### Success

```http
200 OK
```

Returns the ordinary Merchant representation.

### Errors

| Status | Code | Condition |
|---|---|---|
| 401 | `UNAUTHORIZED` | Invalid or missing credentials |

---

## 5.3 Update Current Merchant

```http
PATCH /api/v1/merchant
```

### Purpose

Updates the Merchant webhook destination.

### Authentication

Required.

### Request

```json
{
  "webhookUrl": "https://merchant.example.com/webhooks/ledgerpay"
}
```

To clear the URL:

```json
{
  "webhookUrl": null
}
```

Rules:

- `webhookUrl` is the only mutable Merchant field in v1;
- `name`, `email`, and `status` cannot be changed through this endpoint;
- an empty string is invalid and is not converted to `null`;
- a request with no supported field is invalid.

### Success

```http
200 OK
```

Returns the complete updated Merchant.

### Errors

| Status | Code | Condition |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Unsupported field, invalid URL, blank URL, or empty patch |
| 401 | `UNAUTHORIZED` | Invalid or missing credentials |

---

## 5.4 Rotate API Key

```http
POST /api/v1/merchant/api-key/rotate
```

### Purpose

Replaces the Merchant's active API key.

### Authentication

Required using the current key.

### Request

No request body.

### Success

```http
200 OK
```

```json
{
  "apiKey": "lp_test_<new-secret>"
}
```

The old key becomes invalid immediately after the operation commits. The new plaintext key is returned once.

### Errors

| Status | Code | Condition |
|---|---|---|
| 401 | `UNAUTHORIZED` | Invalid or missing current credentials |

---

## 5.5 Deactivate Merchant

```http
POST /api/v1/merchant/deactivate
```

### Purpose

Soft-deactivates the current Merchant.

### Authentication

Required.

### Preconditions

Deactivation is rejected while the Merchant has any unfinished:

- `PENDING` Payment;
- `PENDING` Refund;
- `PENDING` WebhookEvent.

Terminal Payment, Refund, and WebhookEvent history does not block deactivation.

### Success

```http
200 OK
```

Returns the complete Merchant with:

```text
status = INACTIVE
deactivatedAt = current UTC time
```

After the response is issued, the API key is no longer accepted.

### Errors

| Status | Code | Condition |
|---|---|---|
| 401 | `UNAUTHORIZED` | Invalid or missing credentials |
| 409 | `MERCHANT_HAS_UNFINISHED_OPERATIONS` | Unfinished operations exist |

```json
{
  "code": "MERCHANT_HAS_UNFINISHED_OPERATIONS",
  "message": "Merchant cannot be deactivated while unfinished operations exist."
}
```

Merchant reactivation is not supported in v1.

---

## 6. Order API

## 6.1 Create Order

```http
POST /api/v1/orders
```

### Authentication

Required.

### Request

```json
{
  "amount": 1000
}
```

The client does not send `currency`; the server sets `EUR`. Order currency is
immutable in v1.

### Success

```http
201 Created
```

Returns the complete `CREATED` Order as an `OrderResponse`. A `Location` header
is not returned for Order creation in v1.

### Errors

| Status | Code | Condition |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Amount is missing, non-integer, zero, or negative |
| 401 | `UNAUTHORIZED` | Invalid or missing credentials |

---

## 6.2 List Orders

```http
GET /api/v1/orders
```

### Authentication

Required.

### Behaviour

Returns all Orders belonging to the authenticated Merchant.

- order: `createdAt DESC`;
- no pagination;
- no filtering;
- no date range;
- no custom sorting.

### Success

```http
200 OK
```

Returns a direct array. No Orders returns `[]`.

---

## 6.3 Get Order

```http
GET /api/v1/orders/{orderId}
```

### Authentication

Required.

### Success

```http
200 OK
```

Returns the complete Order.

### Errors

| Status | Code | Condition |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Invalid Order ID format |
| 401 | `UNAUTHORIZED` | Invalid or missing credentials |
| 404 | `ORDER_NOT_FOUND` | Missing or cross-merchant Order |

---

## 6.4 Update Order Amount

```http
PATCH /api/v1/orders/{orderId}
```

### Transaction and concurrency

The update runs in one database transaction and acquires a pessimistic write lock
on the merchant-owned Order row. Status and historical-Payment eligibility are
checked after the lock is acquired. Payment creation uses the same Order-row lock,
so a Payment cannot be created from one amount while a concurrent update commits
another amount.

### Authentication

Required.

### Request

```json
{
  "amount": 1200
}
```

### Preconditions

Both conditions must be true:

1. `Order.status = CREATED`;
2. no Payment has ever been created for the Order.

Only `amount` may be changed by this endpoint. Currency is immutable in v1,
always remains `EUR`, and must not be accepted in the PATCH request.

### Success

```http
200 OK
```

Returns the complete updated Order.

### Errors

| Status | Code | Condition |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Invalid ID, amount, or unsupported field |
| 401 | `UNAUTHORIZED` | Invalid or missing credentials |
| 404 | `ORDER_NOT_FOUND` | Missing or cross-merchant Order |
| 409 | `ORDER_INVALID_STATE` | Order is not editable |

---

## 6.5 Cancel Order

```http
POST /api/v1/orders/{orderId}/cancel
```

### Transaction and concurrency

Cancellation runs in one database transaction and acquires the same pessimistic
write lock on the merchant-owned Order row that Payment creation uses. Order status
and the current-`PENDING`-Payment check are evaluated after the lock is acquired,
so cancellation and Payment creation cannot both pass eligibility concurrently.

### Authentication

Required.

### Allowed states

Cancellation is allowed when:

```text
Order.status = CREATED
```

or:

```text
Order.status = PAYMENT_PENDING
and no Payment is currently PENDING
```

Cancellation is rejected for:

```text
PAID
PARTIALLY_REFUNDED
REFUNDED
CANCELLED
```

It is also rejected while a Payment is `PENDING`.

### Success

```http
200 OK
```

Returns the complete Order with:

```text
status = CANCELLED
cancelledAt = current UTC time
```

### Errors

| Status | Code | Condition |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Invalid Order ID format |
| 401 | `UNAUTHORIZED` | Invalid or missing credentials |
| 404 | `ORDER_NOT_FOUND` | Missing or cross-merchant Order |
| 409 | `ORDER_INVALID_STATE` | Cancellation is not allowed |

---

## 7. Payment API

## 7.1 Create Payment

```http
POST /api/v1/payments
Authorization: Bearer <secret_api_key>
Idempotency-Key: payment-order-001
Content-Type: application/json
```

### Request

```json
{
  "orderId": "ord_<uuid>"
}
```

The Payment amount and currency are copied from the Order.

### Idempotency identity

```text
orderId
```

Rules:

- same Merchant, same key, same `orderId` -> historical replay;
- same Merchant, same key, different `orderId` -> `409 IDEMPOTENCY_CONFLICT`;
- Payment and Refund idempotency-key namespaces are independent.

### Eligibility

A Payment may be created when:

- the Order belongs to the authenticated Merchant;
- the Order is `CREATED` or `PAYMENT_PENDING`;
- no Payment is currently `PENDING`;
- no Payment has already `SUCCEEDED`;
- the Order is not cancelled or refunded.

Multiple failed Payment records may exist for one Order.

### Success: new Payment

```http
201 Created
```

Returns a complete `PENDING` Payment. No `Location` header is returned.

### Success: historical replay

```http
200 OK
```

Returns the previously created Payment.

### Transaction and concurrency

For a non-replayed request:

```text
BEGIN
-> lock MerchantOrder row FOR UPDATE
-> recheck Payment eligibility
-> insert Payment as PENDING
-> update Order to PAYMENT_PENDING
COMMIT
```

The database also enforces:

- one `PENDING` Payment per Order;
- one `SUCCEEDED` Payment per Order;
- unique `(merchantId, idempotencyKey)`.

Historical idempotency lookup happens before mutable state validation. Under concurrent identical requests, a unique-constraint loser reloads the existing Payment and compares request identity.

### Errors

| Status | Code | Condition |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Missing key, invalid key, invalid body, or invalid Order ID |
| 401 | `UNAUTHORIZED` | Invalid or missing credentials |
| 404 | `ORDER_NOT_FOUND` | Missing or cross-merchant Order |
| 409 | `IDEMPOTENCY_CONFLICT` | Key previously used for a different Order |
| 409 | `PAYMENT_ALREADY_PENDING` | A current Payment is already pending |
| 409 | `ORDER_ALREADY_PAID` | A Payment already succeeded |
| 409 | `ORDER_INVALID_STATE` | Order cannot accept a Payment |

---

## 7.2 Get Payment

```http
GET /api/v1/payments/{paymentId}
```

### Authentication

Required.

### Success

```http
200 OK
```

Returns the complete Payment.

### Errors

| Status | Code | Condition |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Invalid Payment ID format |
| 401 | `UNAUTHORIZED` | Invalid or missing credentials |
| 404 | `PAYMENT_NOT_FOUND` | Missing or cross-merchant Payment |

---

## 7.3 List Order Payments

```http
GET /api/v1/orders/{orderId}/payments
```

### Behaviour

Returns all Payment attempts for the Order, including:

```text
PENDING
SUCCEEDED
FAILED
```

Order: `createdAt DESC`.

### Success

```http
200 OK
```

Returns a direct array. An existing Order with no Payments returns `[]`.

### Errors

| Status | Code | Condition |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Invalid Order ID format |
| 401 | `UNAUTHORIZED` | Invalid or missing credentials |
| 404 | `ORDER_NOT_FOUND` | Missing or cross-merchant Order |

---

## 7.4 Simulate Payment Outcome

```http
POST /api/v1/payments/{paymentId}/simulate
```

### Authentication

Required.

### Idempotency

No `Idempotency-Key` header is used.

### Success request

```json
{
  "outcome": "SUCCEEDED"
}
```

For `SUCCEEDED`, `failureCode` may be omitted or explicitly `null`. A non-null
`failureCode` is invalid.

### Failure request

```json
{
  "outcome": "FAILED",
  "failureCode": "PAYMENT_DECLINED"
}
```

Allowed failure codes:

```text
PAYMENT_DECLINED
PROCESSING_ERROR
```

For `FAILED`, `failureCode` is required and must be non-null.

### Preconditions

Only a `PENDING` Payment may be simulated.

### Successful Payment transition

In one database transaction:

```text
Payment.status = SUCCEEDED
Payment.failureCode = null
Payment.completedAt = now

Order.status = PAID

Insert payment.succeeded WebhookEvent as PENDING
```

### Failed Payment transition

In one database transaction:

```text
Payment.status = FAILED
Payment.failureCode = supplied code
Payment.completedAt = now

Order remains PAYMENT_PENDING

Insert payment.failed WebhookEvent as PENDING
```

After failure, the Merchant may create a new Payment attempt or cancel the Order when no Payment remains pending.

### Concurrency note

v1 intentionally does not acquire a pessimistic lock for this manual sandbox simulation endpoint. Concurrent simulation of the same Payment is a documented v1 limitation.

### Success

```http
200 OK
```

Returns the complete terminal Payment.

### Errors

| Status | Code | Condition |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Invalid ID or request-body combination |
| 401 | `UNAUTHORIZED` | Invalid or missing credentials |
| 404 | `PAYMENT_NOT_FOUND` | Missing or cross-merchant Payment |
| 409 | `PAYMENT_INVALID_STATE` | Payment is no longer `PENDING` |

---

## 8. Refund API

## 8.1 Create Refund

```http
POST /api/v1/payments/{paymentId}/refunds
Authorization: Bearer <secret_api_key>
Idempotency-Key: refund-001
Content-Type: application/json
```

### Request

```json
{
  "amount": 300,
  "reasonCode": "CUSTOMER_REQUEST"
}
```

### Request rules

- `amount`: required integer minor units greater than zero;
- `reasonCode`: required enum value;
- Payment, Order, Merchant, currency, and initial status are server-derived.

Allowed reason codes:

```text
CUSTOMER_REQUEST
DUPLICATE_CHARGE
PRODUCT_NOT_RECEIVED
OTHER
```

### Idempotency identity

```text
paymentId + amount + reasonCode
```

Rules:

- namespace is the authenticated Merchant plus the idempotency key;
- same key and identical identity -> the same historical Refund, including after
  the Refund becomes `SUCCEEDED` or `FAILED`, Payment capacity changes, or the
  related Order changes state;
- same key with a different Payment, amount, or reason -> `409 IDEMPOTENCY_CONFLICT`;
- historical replay is checked before current refund-capacity validation.
- a genuine business retry after `FAILED` requires a new idempotency key and
  creates a new Refund.

### Eligibility

The related Payment must be `SUCCEEDED`.

Available refundable capacity is:

```text
Payment.amount
- Payment.refundedAmount
- Payment.pendingRefundAmount
```

The requested amount must not exceed this value.

If eligibility or capacity validation fails, the request is rejected without
creating a Refund row, a `FAILED` Refund, or a `refund.failed` WebhookEvent.
`PAYMENT_NOT_REFUNDABLE` and `INSUFFICIENT_REFUNDABLE_AMOUNT` are request-level
errors only.

### Success: new Refund

```http
201 Created
Location: /api/v1/refunds/re_<uuid>
```

Returns a complete `PENDING` Refund.

### Success: historical replay

```http
200 OK
```

Returns the existing Refund.

### Transaction and concurrency

```text
historical merchant-scoped idempotency lookup
-> BEGIN bounded write transaction
-> lock Payment row FOR UPDATE
-> second merchant-scoped idempotency lookup
-> for a genuinely new request, verify Payment is SUCCEEDED
-> recalculate and validate available refundable capacity
-> insert Refund as PENDING
-> increase Payment.pendingRefundAmount
-> COMMIT
```

The Payment lock serializes refund-capacity decisions for the same Payment while allowing Refunds for different Payments to proceed concurrently.

### Errors

| Status | Code | Condition |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Missing key, invalid ID, amount, or reason |
| 401 | `UNAUTHORIZED` | Invalid or missing credentials |
| 404 | `PAYMENT_NOT_FOUND` | Missing or cross-merchant Payment |
| 409 | `IDEMPOTENCY_CONFLICT` | Key previously used for a different request |
| 409 | `PAYMENT_NOT_REFUNDABLE` | Payment has not succeeded |
| 409 | `INSUFFICIENT_REFUNDABLE_AMOUNT` | Requested amount exceeds available capacity |

```json
{
  "code": "INSUFFICIENT_REFUNDABLE_AMOUNT",
  "message": "The requested refund amount exceeds the available refundable amount."
}
```

---

## 8.2 Get Refund

```http
GET /api/v1/refunds/{refundId}
```

### Success

```http
200 OK
```

Returns the complete Refund.

### Errors

| Status | Code | Condition |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Invalid Refund ID format |
| 401 | `UNAUTHORIZED` | Invalid or missing credentials |
| 404 | `REFUND_NOT_FOUND` | Missing or cross-merchant Refund |

---

## 8.3 List Payment Refunds

```http
GET /api/v1/payments/{paymentId}/refunds
```

Returns all Refund records for the Payment in `createdAt DESC` order.

An existing Payment with no Refunds returns `[]`.

### Errors

| Status | Code | Condition |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Invalid Payment ID format |
| 401 | `UNAUTHORIZED` | Invalid or missing credentials |
| 404 | `PAYMENT_NOT_FOUND` | Missing or cross-merchant Payment |

---

## 8.4 Simulate Refund Outcome

```http
POST /api/v1/refunds/{refundId}/simulate
```

### Authentication

Required.

### Idempotency

No `Idempotency-Key` header is used.

### Success request

```json
{
  "outcome": "SUCCEEDED"
}
```

`failureCode` may be omitted or explicitly `null`. A non-null `failureCode` is
invalid for `SUCCEEDED`.

### Failure request

```json
{
  "outcome": "FAILED",
  "failureCode": "REFUND_PROCESSING_ERROR"
}
```

The only persisted Refund simulation failure code in v1 is:

```text
REFUND_PROCESSING_ERROR
```

Refund eligibility and capacity failures are rejected during Refund creation; they are not simulated after a Refund has been accepted.

The Refund lifecycle is `PENDING -> SUCCEEDED / FAILED`; there is no
`PROCESSING` state. Repeating simulation for a terminal Refund returns
`409 REFUND_INVALID_STATE`.

### Preconditions

Only a `PENDING` Refund may be simulated.

### Successful Refund transition

In one database transaction:

```text
Refund.status = SUCCEEDED

Payment.pendingRefundAmount -= Refund.amount
Payment.refundedAmount += Refund.amount

Read post-update Payment totals using UPDATE ... RETURNING

if refundedAmount = Payment.amount:
    Order.status = REFUNDED
else:
    Order.status = PARTIALLY_REFUNDED

Insert refund.succeeded WebhookEvent as PENDING
```

`Payment.status` remains `SUCCEEDED`.

### Failed Refund transition

In one database transaction:

```text
Refund.status = FAILED
Refund.failureCode = REFUND_PROCESSING_ERROR

Payment.pendingRefundAmount -= Refund.amount
Payment.refundedAmount unchanged

Insert refund.failed WebhookEvent as PENDING
```

`Payment.status` remains `SUCCEEDED`.

For either terminal result, the persisted Refund webhook snapshot contains:

```text
id
paymentId
amount
currency
reasonCode
status
failureCode
```

The Refund transition, Payment accounting, Order state, and WebhookEvent insert
commit in the same database transaction. External HTTP delivery occurs outside
that transaction.

Payment summary changes must use atomic database increments and decrements rather than stale Java read-modify-save logic.

### Concurrency note

v1 intentionally does not acquire a pessimistic lock for this manual sandbox simulation endpoint. Concurrent simulation of the same Refund is a documented v1 limitation.

### Success

```http
200 OK
```

Returns the complete terminal Refund.

### Errors

| Status | Code | Condition |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Invalid ID or request-body combination |
| 401 | `UNAUTHORIZED` | Invalid or missing credentials |
| 404 | `REFUND_NOT_FOUND` | Missing or cross-merchant Refund |
| 409 | `REFUND_INVALID_STATE` | Refund is no longer `PENDING` |

---

## 9. Webhook API

## 9.1 Get WebhookEvent

```http
GET /api/v1/webhook-events/{eventId}
```

### Success

```http
200 OK
```

Returns the complete WebhookEvent.

### Errors

| Status | Code | Condition |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Invalid event ID format |
| 401 | `UNAUTHORIZED` | Invalid or missing credentials |
| 404 | `WEBHOOK_EVENT_NOT_FOUND` | Missing or cross-merchant event |

---

## 9.2 List Payment WebhookEvents

```http
GET /api/v1/payments/{paymentId}/webhook-events
```

Returns all Payment and Refund lifecycle events associated with the Payment.

Order: `createdAt DESC`.

An existing Payment with no events returns `[]`.

### Errors

| Status | Code | Condition |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Invalid Payment ID format |
| 401 | `UNAUTHORIZED` | Invalid or missing credentials |
| 404 | `PAYMENT_NOT_FOUND` | Missing or cross-merchant Payment |

---

## 9.3 Manually Retry WebhookEvent

```http
POST /api/v1/webhook-events/{eventId}/retry
```

### Purpose

Performs one synchronous HTTP delivery attempt using the same event ID and immutable payload.

### Preconditions

- the event must be `FAILED`;
- the current Merchant `webhookUrl` must be configured.

Manual retry does not create a new WebhookEvent and does not start a new automatic retry cycle.

### Attempt accounting

A real HTTP request:

```text
attemptCount += 1
lastAttemptAt = now
```

If no current webhook URL exists:

- return `409 WEBHOOK_URL_NOT_CONFIGURED`;
- do not make an HTTP request;
- do not increment `attemptCount`;
- do not change `lastAttemptAt`;
- leave the event unchanged.

### Delivery success

```text
status = DELIVERED
deliveredAt = now
lastFailureCode may retain the most recent historical failure
```

### Delivery failure

```text
status remains FAILED
lastFailureCode = current failure
```

### HTTP response semantics

When an actual manual attempt executes, the API returns `200 OK` with the latest event even when the remote delivery fails. The action itself completed successfully; the returned WebhookEvent shows whether delivery succeeded.

### Errors

| Status | Code | Condition |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Invalid event ID format |
| 401 | `UNAUTHORIZED` | Invalid or missing credentials |
| 404 | `WEBHOOK_EVENT_NOT_FOUND` | Missing or cross-merchant event |
| 409 | `WEBHOOK_INVALID_STATE` | Event is not `FAILED` |
| 409 | `WEBHOOK_URL_NOT_CONFIGURED` | Merchant has no current webhook URL |

---

## 10. Error Model

LedgerPay-defined API errors use:

```json
{
  "code": "ERROR_CODE",
  "message": "Human-readable explanation."
}
```

The response does not expose:

- stack traces;
- SQL;
- entity internals;
- API-key hashes;
- internal exception class names.

### 10.1 Common errors

| HTTP status | Code | Meaning |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Invalid path ID, header, JSON, field, type, or enum |
| 401 | `UNAUTHORIZED` | Invalid or missing API credentials |
| 404 | `ENDPOINT_NOT_FOUND` | Route does not exist |
| 405 | `METHOD_NOT_ALLOWED` | Route exists but method is unsupported |
| 409 | `IDEMPOTENCY_CONFLICT` | Key reused with a different request identity |
| 500 | `INTERNAL_ERROR` | Unexpected server failure |

Unknown route:

```json
{
  "code": "ENDPOINT_NOT_FOUND",
  "message": "The requested endpoint was not found."
}
```

Unsupported method includes the HTTP `Allow` header:

```json
{
  "code": "METHOD_NOT_ALLOWED",
  "message": "The requested HTTP method is not allowed for this endpoint."
}
```

A missing or unsupported JSON `Content-Type` returns `415 Unsupported Media
Type`. LedgerPay does not guarantee a custom `ApiErrorResponse` body or error
code for this framework-level response.

Unexpected error:

```json
{
  "code": "INTERNAL_ERROR",
  "message": "An unexpected error occurred."
}
```

### 10.2 Resource-not-found errors

| Resource | Code |
|---|---|
| Order | `ORDER_NOT_FOUND` |
| Payment | `PAYMENT_NOT_FOUND` |
| Refund | `REFUND_NOT_FOUND` |
| WebhookEvent | `WEBHOOK_EVENT_NOT_FOUND` |

These codes cover both absent and cross-merchant resources.

### 10.3 Business conflict errors

| Code | Meaning |
|---|---|
| `MERCHANT_EMAIL_ALREADY_EXISTS` | Email already registered |
| `MERCHANT_HAS_UNFINISHED_OPERATIONS` | Merchant cannot be deactivated |
| `ORDER_INVALID_STATE` | Order operation is not currently permitted |
| `PAYMENT_ALREADY_PENDING` | Order already has a pending attempt |
| `ORDER_ALREADY_PAID` | Order already has a successful Payment |
| `PAYMENT_INVALID_STATE` | Payment is not pending |
| `PAYMENT_NOT_REFUNDABLE` | Payment has not succeeded |
| `INSUFFICIENT_REFUNDABLE_AMOUNT` | Refund capacity is insufficient |
| `REFUND_INVALID_STATE` | Refund is not pending |
| `WEBHOOK_INVALID_STATE` | Event is not eligible for manual retry |
| `WEBHOOK_URL_NOT_CONFIGURED` | No current destination URL exists |

---

## 11. HTTP Status Code Summary

| Status | Usage |
|---|---|
| `200 OK` | Read, update, state action, manual retry, idempotent replay |
| `201 Created` | First successful resource creation |
| `400 Bad Request` | Request validation failure |
| `401 Unauthorized` | Authentication failure |
| `404 Not Found` | Missing route or merchant-scoped resource |
| `405 Method Not Allowed` | Unsupported method on an existing route |
| `409 Conflict` | Business-state or idempotency conflict |
| `415 Unsupported Media Type` | JSON content type missing or wrong |
| `500 Internal Server Error` | Unexpected server error |

v1 does not use `403 Forbidden` for business resources and does not return `204 No Content` for successful actions.

---

## 12. Idempotency Rules

Idempotency is required for:

```text
POST /api/v1/payments
POST /api/v1/payments/{paymentId}/refunds
```

### 12.1 Header format

```http
Idempotency-Key: <opaque-key>
```

Rules:

- required;
- length 1-100 characters;
- case-sensitive;
- no trimming or lowercasing;
- scoped to the authenticated Merchant;
- Payment and Refund use separate namespaces.

### 12.2 Payment identity

```text
orderId
```

### 12.3 Refund identity

```text
paymentId + amount + reasonCode
```

### 12.4 Replay ordering

The service checks for an existing idempotency record before evaluating mutable business state.

This means a historical retry can still replay successfully after:

- an Order changed state;
- a Payment completed;
- refund capacity changed.

For Refunds, this includes replay after `SUCCEEDED` or `FAILED`; current Payment
capacity and Order state are not revalidated for a matching historical request.

### 12.5 First execution and replay

```text
First creation -> 201 Created
Historical replay -> 200 OK
Different identity -> 409 IDEMPOTENCY_CONFLICT
```

### 12.6 Concurrent identical requests

Application lookup is not sufficient on its own. PostgreSQL unique constraints are the final defence:

```text
payment UNIQUE (merchant_id, idempotency_key)
refund  UNIQUE (merchant_id, idempotency_key)
```

A transaction that loses a concurrent uniqueness race reloads the winner and compares identity:

- matching identity -> replay;
- different identity -> conflict.

---

## 13. Transaction and Concurrency Boundaries

### 13.1 Isolation level

LedgerPay v1 uses PostgreSQL `READ COMMITTED`.

### 13.2 Payment creation

- lock the Order row using `FOR UPDATE`;
- recheck current state after acquiring the lock;
- create Payment and move Order to `PAYMENT_PENDING` in one transaction;
- rely on partial unique indexes for final protection.

### 13.3 Refund creation

- perform the historical merchant-scoped idempotency lookup before the bounded
  write transaction;
- lock the related Payment row using `FOR UPDATE`;
- perform a second idempotency lookup after acquiring the lock;
- recalculate available capacity inside the transaction;
- insert Refund and reserve `pendingRefundAmount` atomically.

### 13.4 Refund completion

Use atomic database updates and `RETURNING` for Payment summaries.

Do not:

1. read summary values into Java;
2. modify stale values;
3. save them later.

This avoids lost updates when different Refunds complete concurrently.

### 13.5 Business transition and outbox event

The related business change and WebhookEvent insertion must commit together.

Examples:

```text
Payment transition
+ Order transition
+ WebhookEvent(PENDING)
= one database transaction
```

```text
Refund transition
+ Payment summary changes
+ Order transition
+ WebhookEvent(PENDING)
= one database transaction
```

### 13.6 External HTTP boundary

Webhook HTTP delivery must never run inside the business database transaction.

Correct flow:

```text
commit business state and WebhookEvent
-> worker later reads event
-> perform external HTTP request
-> update delivery metadata
```

### 13.7 Explicit v1 concurrency limitations

v1 does not add explicit same-resource locking for:

- concurrent Payment simulation;
- concurrent Refund simulation;
- concurrent manual retry of the same WebhookEvent.

These are accepted sandbox limitations and are recorded in the separate V2 backlog.

Merchant deactivation also does not provide linearizable exclusion against a
request that already authenticated or entered a mutation path. Such a request
may race with deactivation after the pending-resource checks. A shared
Merchant-level locking protocol is deferred to V2.

---

## 14. Webhook Reliability Model

### 14.1 Durable event persistence

A WebhookEvent is durably persisted in the same business database transaction as
the related transition. External HTTP delivery occurs outside that transaction.

### 14.2 Automatic retry policy

```text
Maximum total automatic attempts: 3
Initial attempt: included
Automatic retries after the initial attempt: 2
Retry interval: fixed 30 seconds
HTTP timeout: 10 seconds
Worker count: 1
Polling model: fixed delay of 5 seconds
Maximum due events per polling cycle: 50
```

The 5-second polling interval controls how often the worker looks for work. It
does not shorten the fixed 30-second eligibility interval between automatic
HTTP attempts for the same event.

### 14.3 Success and failure classification

Any `2xx` response is successful.

The following are failures:

- `3xx`;
- `4xx`;
- `5xx`;
- connection timeout;
- connection failure;
- internal delivery-processing failure.

Redirects are not followed.

Failure mapping:

| Condition | `lastFailureCode` |
|---|---|
| No URL configured | `WEBHOOK_URL_NOT_CONFIGURED` |
| Timeout or connection timeout | `CONNECTION_TIMEOUT` |
| Non-2xx HTTP response | `HTTP_ERROR` |
| Internal delivery error | `PROCESSING_ERROR` |

### 14.4 Attempt lifecycle

At event creation:

```text
status = PENDING
attemptCount = 0
lastAttemptAt = null
deliveredAt = null
lastFailureCode = null
```

For every actual HTTP request:

```text
attemptCount += 1
lastAttemptAt = now
```

Unsuccessful attempt before the limit:

```text
status remains PENDING
lastFailureCode = current failure
```

Successful attempt:

```text
status = DELIVERED
deliveredAt = now
lastFailureCode may retain historical failure information
```

Automatic attempts exhausted:

```text
status = FAILED
lastFailureCode is not null
```

No webhook URL:

```text
status = FAILED
attemptCount = 0
lastAttemptAt = null
lastFailureCode = WEBHOOK_URL_NOT_CONFIGURED
```

Pre-HTTP delivery processing failure:

```text
status = FAILED
attemptCount unchanged
lastAttemptAt unchanged
lastFailureCode = PROCESSING_ERROR
```

Because no HTTP request began, this does not consume an attempt. Thread
interruption propagates as an internal runtime failure and is not mapped to a
Webhook failure code.

Therefore `FAILED` means automatic delivery has stopped, either because:

1. the maximum attempt count was exhausted; or
2. delivery could not start because of a non-retryable configuration error.

### 14.5 Immutable event identity and payload

Retries use:

- the same `event.id`;
- the same `type`;
- the same immutable `data` payload.

A retry never creates a replacement event.

Every outbound HTTP request uses `Content-Type: application/json` and contains
only this stable envelope:

```json
{
  "id": "evt_<uuid>",
  "type": "payment.succeeded",
  "createdAt": "2026-07-25T20:10:00Z",
  "data": {}
}
```

`data` comes directly from the immutable `WebhookEvent.payload` snapshot. The
outbound body does not include mutable delivery metadata such as `status`,
`attemptCount`, `lastAttemptAt`, `deliveredAt`, or `lastFailureCode`.

### 14.6 Delivery guarantee

LedgerPay provides **at-least-once delivery**.

Duplicate delivery can occur when:

```text
Merchant commits event processing
-> Merchant returns HTTP 200
-> LedgerPay worker crashes before marking DELIVERED
-> event is delivered again after recovery
```

LedgerPay cannot know whether a lost HTTP acknowledgement occurred before or after Merchant processing.

Merchant requirement:

> Merchants must deduplicate events using the stable `event.id` and process each event idempotently.

### 14.7 Ordering

v1 does not guarantee strict webhook ordering.

The worker may prefer due events by `createdAt ASC`, but this is a processing priority rather than a delivery-order guarantee. One failing event does not block later events.

Merchants should use:

- `event.id`;
- `createdAt`;
- Payment and Refund IDs;
- current resource state;

rather than assuming arrival order.

---

## 15. End-to-End Example Flows

## 15.1 Flow 1: Merchant Bootstrap

### Step 1: Create Merchant

```http
POST /api/v1/merchants
Content-Type: application/json
```

```json
{
  "name": "Alice Shop",
  "email": "alice@example.com",
  "webhookUrl": null
}
```

Result:

```text
Merchant.status = ACTIVE
Merchant.webhookUrl = null
plaintext API key returned once
```

### Step 2: Authenticate

```http
Authorization: Bearer lp_test_<secret>
```

### Step 3: Configure Webhook URL

```http
PATCH /api/v1/merchant
Authorization: Bearer lp_test_<secret>
Content-Type: application/json
```

```json
{
  "webhookUrl": "https://merchant.example.com/webhooks/ledgerpay"
}
```

---

## 15.2 Flow 2: Successful Payment

### Step 1: Create Order

```http
POST /api/v1/orders
```

```json
{
  "amount": 1000
}
```

Result:

```text
Order.status = CREATED
Order.currency = EUR
```

### Step 2: Create Payment

```http
POST /api/v1/payments
Idempotency-Key: payment-order-001
```

```json
{
  "orderId": "ord_<uuid>"
}
```

Transaction result:

```text
Payment.status = PENDING
Order.status = PAYMENT_PENDING
```

### Step 3: Simulate Success

```http
POST /api/v1/payments/pay_<uuid>/simulate
```

```json
{
  "outcome": "SUCCEEDED"
}
```

Transaction result:

```text
Payment.status = SUCCEEDED
Order.status = PAID
payment.succeeded WebhookEvent = PENDING
```

### Step 4: Deliver Webhook

The worker sends the immutable event payload. A `2xx` response changes the event to `DELIVERED`.

---

## 15.3 Flow 3: Failed Payment Then Successful New Attempt

### First attempt

```text
Create Payment #1
-> Payment #1 = PENDING
-> simulate FAILED with PAYMENT_DECLINED
-> Payment #1 = FAILED
-> Order remains PAYMENT_PENDING
-> payment.failed event created
```

### Second attempt

Use a new idempotency key:

```text
Create Payment #2
-> allowed because no Payment is currently PENDING
-> Payment #2 = PENDING
```

Simulate success:

```text
Payment #2 = SUCCEEDED
Order = PAID
payment.succeeded event created
```

Payment #1 remains immutable historical failure data.

---

## 15.4 Flow 4: Partial Then Full Refund

Assume:

```text
Payment.amount = 1000
Payment.refundedAmount = 0
Payment.pendingRefundAmount = 0
```

### Partial Refund

Create:

```json
{
  "amount": 300,
  "reasonCode": "CUSTOMER_REQUEST"
}
```

After acceptance:

```text
Refund #1 = PENDING
Payment.pendingRefundAmount = 300
Payment.availableRefundAmount = 700
```

After simulated success:

```text
Refund #1 = SUCCEEDED
Payment.pendingRefundAmount = 0
Payment.refundedAmount = 300
Order.status = PARTIALLY_REFUNDED
refund.succeeded event created
```

### Remaining Refund

Create:

```json
{
  "amount": 700,
  "reasonCode": "CUSTOMER_REQUEST"
}
```

After simulated success:

```text
Payment.refundedAmount = 1000
Payment.availableRefundAmount = 0
Order.status = REFUNDED
refund.succeeded event created
```

---

## 15.5 Flow 5: Webhook Failure and Manual Retry

### Automatic delivery

```text
Attempt 1 -> timeout
status = PENDING
attemptCount = 1

Attempt 2 -> HTTP 500
status = PENDING
attemptCount = 2

Attempt 3 -> timeout
status = FAILED
attemptCount = 3
```

### Merchant fixes the endpoint

The Merchant updates `webhookUrl` if necessary.

### Manual retry

```http
POST /api/v1/webhook-events/evt_<uuid>/retry
```

The same event ID and payload are sent once.

Successful delivery:

```text
status = DELIVERED
attemptCount = 4
deliveredAt = now
lastFailureCode may remain for history
```

Failed delivery:

```text
status remains FAILED
attemptCount = 4
lastFailureCode updated
```

No new automatic retry cycle is started.

---

## 16. v1 Scope and Out of Scope

## 16.1 Included in v1

- Merchant bootstrap registration;
- one active secret API key per Merchant;
- immediate API-key rotation;
- Merchant webhook configuration and soft deactivation;
- Merchant-scoped Order lifecycle;
- Payment attempts and deterministic simulation;
- merchant-scoped Payment idempotency;
- partial and full Refunds;
- Refund-capacity reservation;
- merchant-scoped Refund idempotency;
- transactional business state and WebhookEvent persistence;
- one polling Webhook worker;
- automatic and manual delivery attempts;
- stable event IDs and immutable payloads;
- at-least-once delivery;
- PostgreSQL transactions, constraints, targeted locking, and atomic updates.

## 16.2 Explicitly out of scope

- real payment-gateway or banking integration;
- real money movement;
- multiple currencies;
- authorization and capture as separate stages;
- split payments and instalments;
- Merchant users or interactive login;
- JWT Merchant authentication;
- multiple active API keys;
- test-mode and live-mode credential separation;
- API-key rotation grace periods;
- Merchant reactivation or administrative recovery;
- multiple webhook endpoints;
- webhook signatures;
- strict webhook ordering;
- exactly-once webhook delivery;
- a WebhookDeliveryAttempt table;
- multiple automatic Webhook workers;
- worker claiming or lease infrastructure;
- Kafka, RabbitMQ, or another message broker;
- pagination, filtering, and custom sorting;
- Merchant-level Payment, Refund, or WebhookEvent search;
- concurrent simulation protection;
- concurrent manual-retry protection;
- production-grade rate limiting and registration-abuse prevention;
- physical deletion of Merchant or financial history.

Future improvements are tracked separately in [`v2_backlog.md`](v2_backlog.md).

---

## 17. Final Endpoint List

### Merchant

```text
POST   /api/v1/merchants
GET    /api/v1/merchant
PATCH  /api/v1/merchant
POST   /api/v1/merchant/api-key/rotate
POST   /api/v1/merchant/deactivate
```

### Order

```text
POST   /api/v1/orders
GET    /api/v1/orders
GET    /api/v1/orders/{orderId}
PATCH  /api/v1/orders/{orderId}
POST   /api/v1/orders/{orderId}/cancel
```

### Payment

```text
POST   /api/v1/payments
GET    /api/v1/payments/{paymentId}
GET    /api/v1/orders/{orderId}/payments
POST   /api/v1/payments/{paymentId}/simulate
```

### Refund

```text
POST   /api/v1/payments/{paymentId}/refunds
GET    /api/v1/refunds/{refundId}
GET    /api/v1/payments/{paymentId}/refunds
POST   /api/v1/refunds/{refundId}/simulate
```

### WebhookEvent

```text
GET    /api/v1/webhook-events/{eventId}
GET    /api/v1/payments/{paymentId}/webhook-events
POST   /api/v1/webhook-events/{eventId}/retry
```
