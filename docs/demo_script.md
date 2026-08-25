# LedgerPay Demo Walkthrough

This walkthrough demonstrates the system's main backend guarantees without
calling every endpoint. Use the public demo at `https://ledgerpay.yianz.me` and
keep [`architecture.md`](architecture.md) available for the architecture steps.

## 1. Start a Demo Merchant session

- **Action:** Open the Demo Console and locate the API console and current Demo
  Merchant.
- **Expected result:** The browser creates a real Merchant when the current tab
  has no active demo credential. The returned API key is stored in that tab's
  `sessionStorage` and is masked in the UI.
- **Engineering behavior:** The frontend does not invent a local Merchant. It
  calls `POST /api/v1/merchants`, then uses the issued credential for ordinary
  authenticated API calls.

## 2. Review the request path

- **Action:** Open the diagrams in [`architecture.md`](architecture.md).
- **Expected result:** The application path is Browser → frontend Nginx →
  Spring Boot → PostgreSQL, with the demo webhook receiver available only on
  the internal Docker network.
- **Engineering behavior:** React is a thin demonstration client. Spring Boot
  owns state transitions and Merchant ownership, while PostgreSQL constraints
  provide final integrity protection.

## 3. Create an Order and Payment

- **Action:** Create a €10.00 Order and create its Payment, leaving the Payment
  `PENDING` initially.
- **Expected result:** The Order moves from `CREATED` to `PAYMENT_PENDING`. The
  Payment amount and currency match the Order.
- **Engineering behavior:** Payment amount/currency are server-derived. Payment
  creation locks the Merchant-owned Order and commits the Payment insertion and
  Order transition together.

## 4. Demonstrate Payment idempotency

- **Action:** While the Payment is `PENDING`, select **Retry Same Request**.
  Then select **Simulate Success**.
- **Expected result:** The retry returns the same Payment ID instead of creating
  another attempt. Simulation moves the Payment to `SUCCEEDED`, the Order to
  `PAID`, and sets `completedAt`.
- **Engineering behavior:** Idempotency is scoped to the Merchant and compares
  the original `orderId`. A second lookup after locking plus a PostgreSQL unique
  constraint protects concurrent retries. The terminal Payment, Order update,
  and WebhookEvent commit in one transaction.

## 5. Demonstrate partial Refund accounting

- **Action:** Create a €3.00 Refund and simulate success. Select **Create
  Another Refund** and review the remaining €7.00 capacity. Optionally complete
  the second Refund for the remaining amount.
- **Expected result:** An accepted Refund first increases
  `pendingRefundAmount`. Success moves that value to `refundedAmount`; the Order
  becomes `PARTIALLY_REFUNDED`, then `REFUNDED` when the full amount succeeds.
- **Engineering behavior:** Refund creation locks the Payment, recalculates
  available capacity, and reserves accepted work before commit. Atomic database
  updates prevent different accepted Refund completions from overwriting each
  other's accounting changes.

## 6. Inspect Webhook delivery

- **Action:** Open the Webhook Events panel. If necessary, wait for the worker
  and refresh the events before selecting the Payment or Refund event.
- **Expected result:** The event retains a stable ID and immutable payload and
  reaches `DELIVERED` after a real HTTP request to the internal demo receiver.
  Attempt metadata shows whether delivery was attempted.
- **Engineering behavior:** The WebhookEvent is persisted with the business
  transition, while external HTTP runs after commit. The worker polls for due
  events, performs at most three automatic attempts, and supports manual retry
  for terminal failures.

## 7. Review production delivery

- **Action:** Review the CI/CD diagram and, when available, a successful GitHub
  Actions run.
- **Expected result:** Validation precedes immutable Git-SHA image publication
  to GHCR, followed by OIDC/SSM deployment and public HTTPS health verification.
- **Engineering behavior:** Deployment does not use SSH or static AWS keys.
  Failed health verification restores the previous application image tag and
  verifies rollback health. Application rollback does not reverse Flyway
  migrations.

## Shortened walkthrough

When time is limited, complete only the first partial Refund and use the visible
capacity fields to explain the final `REFUNDED` transition. Merchant profile
updates, API-key rotation, every query endpoint, and frontend styling are not
required to demonstrate the core backend design.
