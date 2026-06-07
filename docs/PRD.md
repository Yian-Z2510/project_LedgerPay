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
| Single merchant | One implicit merchant; no multi-tenant model in MVP                                        |
| Amounts         | Whole numbers in **minor units** (e.g. euro cents). `€10.00` = `1000`                      |
| Currency        | `EUR` only; no currency exchange or conversion                                             |
| Idempotency     | Client sends an **idempotency key** when creating payments and refunds so retries are safe |
| No login        | No user authentication in MVP; documented as local/dev use only                            |
| Payment method  | Optional label (e.g. `card`); not validated against real cards                             |


## Simulation Model

Payments and refunds do **not** auto-complete. The integrator must explicitly simulate the final outcome.

### Payment

1. **Create** — A new payment starts in `created` status.
2. **Simulate** — The integrator calls a simulate action with `success` or `failure`. The payment moves to `succeeded` or `failed`.

### Refund

1. **Create** — The integrator requests a refund on a `succeeded` or `partially_refunded` payment.
  - If the request is **invalid** (e.g. payment not refundable, amount too high), the refund is `**failed` immediately** and a `refund.failed` webhook is sent.
  - If the request is **valid**, the refund is `**pending`** and waits for simulation. The payment balance does **not** change yet.
2. **Simulate** — The integrator calls a simulate action with `success` or `failure`.
  - On **success**, the refund becomes `succeeded`, the payment’s refunded amount is updated, and a `refund.succeeded` webhook is sent.
  - On **failure**, the refund becomes `failed` and a `refund.failed` webhook is sent. The payment balance is unchanged.

## Payment Lifecycle

### Statuses


| Status               | Meaning                                  |
| -------------------- | ---------------------------------------- |
| `created`            | Payment recorded; waiting for simulation |
| `processing`         | Brief step while simulation runs         |
| `succeeded`          | Payment completed successfully           |
| `failed`             | Payment failed; no further action        |
| `partially_refunded` | Some amount refunded; balance remains    |
| `refunded`           | Full amount refunded                     |


### Main flow

`created` → (simulate) → `succeeded` or `failed`

After `succeeded`, refunds are created separately (see Refund Lifecycle). The payment moves to `partially_refunded` or `refunded` only when a refund simulation **succeeds**.

### Rules

- Simulate payment only when status is `created`.
- Cannot simulate a `failed` or `refunded` payment.

## Refund Lifecycle

### Statuses


| Status       | Meaning                                                         |
| ------------ | --------------------------------------------------------------- |
| `pending`    | Refund accepted; waiting for simulation                         |
| `processing` | Brief step while simulation runs                                |
| `succeeded`  | Refund completed; payment balance updated                       |
| `failed`     | Refund rejected or simulation failed; payment balance unchanged |


### Main flow

**Immediate failure (on create):**

`(invalid refund request)` → `failed` → `refund.failed` webhook

**Accepted refund:**

`pending` → (simulate) → `succeeded` or `failed`

On `succeeded`, the payment becomes `partially_refunded` or `refunded` depending on remaining balance.

### Rules

- Simulate refund only when status is `pending`.
- Create refund only when payment status is `succeeded` or `partially_refunded`.
- Multiple partial refunds are allowed until the full payment amount is refunded.
- Payment `amount_refunded` and status update **only** when a refund reaches `succeeded`.

## Refund Rules

- One refund action handles both **partial** and **full** refunds: send an amount for partial; omit amount (or send remaining balance) for full.
- A single refund cannot exceed the **remaining** refundable amount.
- All refunds are in EUR and match the original payment.
- Invalid create requests fail immediately with `failed` status and a `refund.failed` webhook (no simulation step).

## Webhooks

When a payment or refund reaches a meaningful end state, the system emits a webhook event.

**Event types (MVP):**

- `payment.succeeded`
- `payment.failed`
- `refund.succeeded`
- `refund.failed`

**Behavior (MVP):**

- Events fire automatically on status change (not a separate manual “simulate webhook” step).
- If the payment has a **callback URL**, the system POSTs the event there.
- All events are stored so they can be looked up for debugging (even if no callback URL was set).

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
- Deliver to callback URL when provided

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

