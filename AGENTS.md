# LedgerPay Repository Agent Guidelines

## Working method

- Inspect the current implementation, tests, Git status, and relevant
  documentation before editing.
- Follow the user's requested scope and make the smallest coherent change.
- Preserve unrelated work already present in the working tree.
- Do not add features, refactors, dependencies, or infrastructure outside the
  requested task.
- If implementation and documentation disagree, identify the conflict instead
  of silently choosing one or rewriting the contract.
- Report the files changed, relevant validation, and any concrete blocker.

## Sources of truth

Use this order when resolving LedgerPay behavior:

1. the latest explicit requirement in the current task;
2. current executable behavior in implementation, tests, Flyway migrations,
   and deployment configuration;
3. `docs/api_design.md` for API and lifecycle contracts;
4. `docs/database_design.md` for schema and persistence invariants;
5. `docs/engineering_decisions.md` for design rationale and concurrency or
   transaction boundaries;
6. `docs/architecture.md` and `docs/known_limitations.md` for runtime and
   production boundaries;
7. `docs/PRD.md` for product scope;
8. `docs/v2_backlog.md` for deferred work only.

When a real conflict remains, stop and report it rather than guessing.

## LedgerPay invariants

- Derive Merchant identity from the authenticated API key. Clients do not
  supply `merchantId`, and cross-Merchant access remains hidden behind the
  resource-specific not-found response.
- Keep Payment and Refund idempotency Merchant-scoped. Preserve replay versus
  conflict behavior and database uniqueness as the final concurrency guard.
- Preserve the Order lock used for Payment creation and the Payment lock plus
  `pendingRefundAmount` reservation used for Refund creation.
- Keep Payment/Refund transitions, related aggregate or Order updates, and
  WebhookEvent persistence within their defined database transaction.
- Perform external webhook HTTP delivery after the business transaction.
  Delivery is at least once; do not claim exactly once.
- PostgreSQL and Flyway migrations define production persistence. Do not
  replace production-relevant behavior with an embedded database model.
- Do not silently expand v1 with capabilities listed in `docs/v2_backlog.md`.

## Production safety

- Never commit real `.env` files, API keys, database passwords, AWS/GHCR
  credentials, private keys, or certificates.
- Treat schema migrations as forward database changes. Application-image
  rollback does not reverse Flyway migrations; future migrations must remain
  backward-compatible or use a staged/forward-fix strategy.
- Preserve the PostgreSQL named volume. Never use `docker compose down -v` or
  delete production data unless the user explicitly requests a verified,
  narrowly scoped data operation.
- Preserve internal-only backend, PostgreSQL, and webhook-receiver networking
  unless an explicit architecture change is approved.
- Treat deployment, cleanup, secret, network, and persistence changes as
  high-risk. Validate targets and failure behavior before modifying them.
- Do not access or mutate live production systems unless the user explicitly
  requests it.

## Verification

Run validation proportional to the change:

- backend behavior: relevant tests or `./mvnw test` with PostgreSQL available;
- frontend behavior: `npm run build` and `npm run lint`;
- workflow changes: `actionlint`;
- Compose changes: `docker compose config` with the appropriate env example;
- shell changes: `bash -n` and `shellcheck` where available;
- documentation changes: link/reference sanity and `git diff --check`.

Do not hide failures or warnings. Report the first concrete failure and whether
it is caused by the change or by the local environment.

## Git

- Keep changes focused and reviewable.
- Do not overwrite unrelated user changes.
- Do not commit, push, open a pull request, or deploy unless explicitly
  requested.
