# Known Limitations and Production Evolution

LedgerPay is a production-deployed engineering portfolio project and payment
sandbox. It is not presented as a regulated, highly available payment platform.

## 1. LedgerPay v1 limitations

- **Single host and region:** host Nginx, the application containers, worker,
  and PostgreSQL run on one EC2 instance, with no multi-AZ failover.
- **Database recovery:** PostgreSQL uses a persistent Docker volume on the same
  EC2 host. The repository does not provide managed backups, replicas, or a
  tested off-instance restore system.
- **In-place deployments:** application containers are updated on the same host
  and may have a brief interruption. Rollback restores application image tags,
  not database state or Flyway migrations.
- **Lightweight abuse protection:** host Nginx limits only Merchant creation by
  source IP. It is not a distributed, Merchant-aware, or API-key-aware limiter.
- **Public demo data:** browser sessions create real Demo Merchants. A guarded
  host job removes identified Demo Merchant graphs older than seven days; this
  is not a general data-retention service.
- **Demo destination:** the included webhook receiver is evidence of delivery,
  not a Merchant-grade endpoint. Webhook requests are not signed.
- **Simulated payments:** outcomes are deterministic manual simulations. There
  is no PSP, acquirer, card network, ledger, settlement, chargeback, or real
  money movement.
- **Webhook throughput:** one polling worker performs direct HTTP delivery with
  fixed retries. There is no broker, worker lease, per-attempt table, strict
  ordering, or exactly-once guarantee.
- **Accepted concurrency limits:** Refund creation is capacity-safe, but same
  Payment simulation, same Refund simulation, and same-event manual retries do
  not have explicit same-resource serialization.
- **Observability:** bounded container/system logs and health checks exist, but
  there is no centralized metrics, tracing, alerting, or SLO platform.
- **Credential model:** each Merchant has one active sandbox API key; rotation
  immediately invalidates the previous key. There is no secrets manager-backed
  multi-key lifecycle or test/live environment split.

## 2. Credible evolution for a real payment platform

These are architectural directions, not claims that the portfolio version
needs every component immediately.

1. **Move PostgreSQL to a managed service.** Add automated backups, point-in-time
   recovery, tested restore procedures, Multi-AZ failover, and read replicas
   only when read traffic justifies them.
2. **Make the application tier stateless and redundant.** Run multiple backend
   instances behind a load balancer across availability zones; keep session
   credentials client-side or in an appropriate shared identity system.
3. **Use a durable delivery queue.** Publish committed outbox events to a broker,
   let workers claim work with leases, persist delivery attempts, and introduce
   exponential backoff and dead-letter operations.
4. **Strengthen edge controls.** Add WAF/bot controls and distributed limits by
   endpoint, Merchant, API key, and IP, with explicit quotas and monitoring.
5. **Centralize secrets and observability.** Use a managed secrets service,
   structured centralized logs, metrics, traces, alerting, and operational
   dashboards for worker lag and business-state failures.
6. **Evolve schema changes safely.** Use expand-and-contract migrations that are
   compatible with both old and new application versions; use staged rollout
   or forward-fix plans for irreversible changes.
7. **Add payment-platform controls deliberately.** Integrate a real PSP/acquirer,
   signed webhooks, stronger audit trails, reconciliation, and compliance
   controls only with clear product and regulatory requirements.
