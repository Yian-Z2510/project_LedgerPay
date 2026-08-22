# LedgerPay PRD

## Project Overview

LedgerPay is a mini payment gateway simulator. It simulates a basic payment lifecycle: create a payment, track its status, simulate success or failure, issue refunds, and send webhook notifications.

The project is also a portfolio piece to demonstrate backend product thinking, API design, payment domain understanding, and an AI-assisted development workflow.

## Problem Statement

Developers who integrate with payment systems need a **safe sandbox** to practice real-world flows—create, confirm, refund, and receive webhooks—without connecting to a real payment provider or moving real money.

## Target Users

### API integrators (primary)

Developers building or learning payment integrations. They need to:

- Create payments safely (including retries without duplicate charges)
- Drive payments and refunds to success or failure for testing
- Poll payment and refund status
- Receive webhook events when something changes

### Operations / debugging users (secondary)

Developers checking that the system behaved correctly. They need to:

- Look up a payment or refund by ID
- See refund history for a payment
- Inspect webhook events for a payment

## User Stories


| ID    | As a…          | I want to…                                              | So that…                              |
| ----- | -------------- | ------------------------------------------------------- | ------------------------------------- |
| US-01 | API integrator | create a payment with an amount in EUR                  | I can start a checkout flow           |
| US-02 | API integrator | retry a create request without creating a duplicate     | network failures are safe to handle   |
| US-03 | API integrator | simulate payment success or failure                     | I can test both happy and error paths |
| US-04 | API integrator | check payment status by ID                              | I know when a payment is done         |
| US-05 | API integrator | create a partial or full refund on a succeeded payment  | I can start a refund flow             |
| US-06 | API integrator | simulate refund success or failure                      | I can test async refund outcomes      |
| US-07 | API integrator | check refund status by ID                               | I can confirm a refund completed      |
| US-08 | API integrator | receive a webhook when payment or refund status changes | I can update my app without polling   |
| US-09 | Ops user       | look up a payment, its refunds, and its webhook events  | I can debug what happened             |


## Assumptions and Constraints


| Assumption      | Detail                                                                                     |
| --------------- | ------------------------------------------------------------------------------------------ |
| Sandbox only    | No real banks, cards, or money movement                                                    |
| Merchant model  | Authenticated multi-Merchant system; business resources are scoped to their owning Merchant |
| Amounts         | Whole numbers in **minor units** (e.g. euro cents). `€10.00` = `1000`                      |
| Currency        | `EUR` only; no currency exchange or conversion                                             |
| Idempotency     | Client keys are scoped by Merchant so retries are safe without cross-Merchant conflicts   |
| Authentication  | Merchants authenticate with API keys; end-user accounts and login are not included         |
| Payment method  | Optional label (e.g. `card`); not validated against real cards                             |


## Simulation Model

Payments and refunds do **not** auto-complete. The integrator must explicitly simulate the final outcome.

### Payment

1. **Create** — A new payment starts in `PENDING` status.
2. **Simulate** — The integrator calls a simulate action with `SUCCEEDED` or `FAILED`. Only a `PENDING` Payment may transition, and the resulting status is terminal.

### Refund

1. **Create** — The integrator requests a refund on a `SUCCEEDED` Payment with available refundable capacity.
  - The amount is always required, including for a full refund, which explicitly supplies the remaining amount.
  - If the request is **invalid** because the Payment is not refundable or capacity is insufficient, the API returns an error. No Refund row, `FAILED` Refund, or `refund.failed` WebhookEvent is created.
  - If the request is **valid**, the Refund becomes `PENDING`, and `Payment.pendingRefundAmount` increases by its amount to reserve capacity. `Payment.refundedAmount` does not increase yet.
2. **Simulate** — The integrator calls a simulate action with `SUCCEEDED` or `FAILED`.
  - On **success**, the Refund becomes `SUCCEEDED`, the pending reservation moves to `Payment.refundedAmount`, and a `refund.succeeded` event is persisted.
  - On **failure**, the Refund becomes `FAILED`, the pending reservation is released without increasing `Payment.refundedAmount`, and a `refund.failed` event is persisted.

## Payment Lifecycle

### Statuses


| Status      | Meaning                                  |
| ----------- | ---------------------------------------- |
| `PENDING`   | Payment recorded; waiting for simulation |
| `SUCCEEDED` | Payment completed successfully            |
| `FAILED`    | Payment failed; no further transition     |


### Main flow

`PENDING` → (simulate) → `SUCCEEDED` or `FAILED`

After `SUCCEEDED`, refunds are created separately (see Refund Lifecycle).
Refund progress is represented by the Payment refund aggregate fields and the
related Order lifecycle/status; it does not add refund-progress statuses to the
Payment state machine.

### Rules

- Simulate a Payment only when its status is `PENDING`.
- `SUCCEEDED` and `FAILED` Payments are terminal and cannot be simulated again.

## Refund Lifecycle

### Statuses


| Status       | Meaning                                                         |
| ------------ | --------------------------------------------------------------- |
| `PENDING`    | Refund accepted; waiting for simulation                         |
| `SUCCEEDED`  | Refund completed; payment balance updated                       |
| `FAILED`     | Accepted Refund failed during simulation; payment balance unchanged |


### Main flow

`PENDING` → (simulate) → `SUCCEEDED` or `FAILED`

On `SUCCEEDED`, the Payment refund aggregate fields are updated. The related
Order becomes `PARTIALLY_REFUNDED` or `REFUNDED` depending on the remaining
refundable balance; the Payment remains `SUCCEEDED`.

### Rules

- Simulate refund only when status is `PENDING`.
- Create a refund only when the Payment status is `SUCCEEDED` and refundable capacity remains.
- Multiple partial refunds are allowed until the full payment amount is refunded.
- Accepted `PENDING` Refunds increase `pendingRefundAmount`; `refundedAmount`
  increases only when a Refund reaches `SUCCEEDED`.
- Repeated simulation of a terminal Refund is rejected.

## Refund Rules

- One refund action handles both **partial** and **full** refunds. `amount` is
  always required; a full Refund explicitly sends the remaining amount.
- A single refund cannot exceed the **remaining** refundable amount.
- All refunds are in EUR and match the original payment.
- `PAYMENT_NOT_REFUNDABLE` and `INSUFFICIENT_REFUNDABLE_AMOUNT` are request-level
  errors and do not persist a Refund or WebhookEvent.
- Refund idempotency is scoped by Merchant. Its request identity is
  `paymentId + amount + reasonCode`; the same key and identity return the same
  historical Refund even after a terminal result. A genuine retry after
  `FAILED` requires a new idempotency key and creates a new Refund.

## Webhooks

When a payment or refund reaches a meaningful end state, the system emits a webhook event.

**Event types (MVP):**

- `payment.succeeded`
- `payment.failed`
- `refund.succeeded`
- `refund.failed`

**Behavior (MVP):**

- Events fire automatically on status change (not a separate manual “simulate webhook” step).
- All events are stored so they can be looked up for debugging (even if no callback URL was set).
- A terminal Refund snapshot contains `id`, `paymentId`, `amount`, `currency`,
  `reasonCode`, `status`, and `failureCode`.
- The WebhookEvent row is durably persisted in the same business database
  transaction. External HTTP delivery occurs outside that transaction and is
  not yet implemented.

## MVP Scope

### Payment

- Create payment (with idempotency key)
- Query payment status
- Simulate payment success or failure

### Refund

- Create refund (partial or full via one action)
- Simulate refund success or failure
- Query refund status
- List refunds for a payment

### Webhooks

- Emit events on payment and refund status changes
- Store events for lookup
- Persist Payment and Refund terminal events for later delivery

### Merchant

- Soft-deactivate when no `PENDING` Payment, Refund, or WebhookEvent exists;
  terminal and historical records do not block deactivation

### Non-functional

- Basic API documentation
- Unit tests for core business logic

## Out of Scope

This MVP does not include:

- Real bank or card network integration
- Real money movement
- User authentication
- Frontend UI
- Production-level security (signed webhooks, rate limits, etc.)
- Multiple currencies, currency exchange, or conversion
- Complex risk or fraud engine
- Webhook retries, chargebacks, or payment search/list APIs

## Glossary


| Term            | Definition                                                                            |
| --------------- | ------------------------------------------------------------------------------------- |
| Payment         | A single charge attempt for a set amount in EUR                                       |
| Refund          | Returning part or all of a succeeded payment                                          |
| Idempotency key | A unique client-provided key so repeating the same request does not create duplicates |
| Webhook         | A notification sent when something important changes (e.g. payment succeeded)         |
| Callback URL    | Optional address where webhook events are delivered                                   |
| Terminal state  | A final status with no further changes (e.g. `failed`, `refunded`)                    |
| Minor units     | Smallest currency unit (euro cents for EUR)                                           |


## Completion Criteria

The project is considered complete when:

- Core payment and refund capabilities work as described above
- The payment lifecycle can be tested through Postman or curl
- Webhook events are stored and delivered when a callback URL is set
- Main business logic has unit tests
- README explains how to run and test the project
- This PRD is up to date in the `docs/` folder
