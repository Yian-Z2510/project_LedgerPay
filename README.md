# LedgerPay

**LedgerPay is an end-to-end payment platform simulator built with Java and Spring Boot, modelling the Order → Payment → Refund → Webhook lifecycle from API request to persistence, asynchronous delivery, and production deployment.**

Payment systems need to remain correct even when clients retry requests, multiple operations arrive concurrently, or downstream delivery fails. LedgerPay addresses these failure scenarios through idempotent request handling, transactional state management, concurrency-safe refund accounting, database-enforced invariants, and persisted WebhookEvents with retryable delivery.

The complete system is deployed as a live HTTPS application on AWS, with a lightweight React console for exploring the payment lifecycle and engineering behaviour.

**🌐 Live Demo — https://ledgerpay.yianz.me**

`Java 21` · `Spring Boot` · `PostgreSQL` · `React` · `Docker` · `AWS` · `GitHub Actions`

---

## Engineering Highlights

### Payment Correctness Under Retries

Merchant-scoped idempotency, transactional state transitions, and PostgreSQL uniqueness constraints work together to prevent duplicate Payments and inconsistent state when requests are retried or race concurrently.

### Concurrency-Safe Refund Accounting

Refund capacity is reserved transactionally while holding a pessimistic lock on the Payment, preventing concurrent partial refunds from collectively exceeding the original payment amount.

### Durable Webhook Delivery

Payment and Refund outcomes are persisted as immutable WebhookEvents with retry state, attempt tracking, failure handling, and at-least-once delivery semantics.

### Production Delivery & Recovery

LedgerPay runs as a Dockerized HTTPS service on AWS EC2, with SHA-tagged images, automated GitHub Actions deployment through OIDC and AWS Systems Manager, production health verification, and application image rollback.

---

## Architecture

LedgerPay keeps business rules in the backend and uses PostgreSQL as the durable source of payment state.

**Application flow**

**React Demo Console → REST API & API-key Authentication → Spring Boot Domain Services → JPA / Repositories → PostgreSQL**

The Spring Boot service layer owns lifecycle rules, merchant ownership, transaction boundaries, idempotency, and concurrency control across Merchant, Order, Payment, Refund, and WebhookEvent resources.

PostgreSQL provides a second integrity layer through foreign keys, unique constraints, lifecycle constraints, and transactional persistence. WebhookEvents are stored durably before delivery and processed independently by the webhook delivery worker.

The React application is a thin interaction layer over the backend rather than the owner of business state.

For the full application, deployment, and CI/CD architecture, see [Architecture](docs/architecture.md).

---

## Tech Stack

| Area | Technologies |
| --- | --- |
| **Backend** | Java 21, Spring Boot 4.1, Spring Web MVC, Spring Data JPA |
| **Database** | PostgreSQL 17, Flyway |
| **Frontend** | React, TypeScript, Vite, Nginx |
| **Testing** | JUnit 5, MockMvc, PostgreSQL integration and concurrency tests |
| **Infrastructure** | Docker, Docker Compose, Nginx, Let's Encrypt / Certbot |
| **Cloud & CD** | AWS EC2, AWS Systems Manager, GHCR, GitHub Actions, GitHub OIDC |
| **Security** | API-key authentication, hashed credentials, merchant-scoped ownership |

---

## Production & CI/CD

LedgerPay is deployed as a containerized application on a single AWS EC2 instance.

**Internet → HTTPS / Host Nginx → Docker Frontend → Spring Boot → PostgreSQL**

Only HTTP/HTTPS are publicly exposed for application traffic. The backend, PostgreSQL, and demo webhook receiver remain on an internal Docker network, while PostgreSQL data persists through a named Docker volume.

Production deployment follows:

**Merge to `main` → CI validation → SHA-tagged Docker images → GHCR → GitHub OIDC → AWS SSM → EC2 deployment → HTTPS health verification**

GitHub Actions uses short-lived AWS credentials through OIDC instead of stored AWS access keys, and AWS Systems Manager allows automated deployment without exposing SSH to GitHub-hosted runners.

If deployment health verification fails, the application returns to the previous image version. Database migrations are treated separately because rolling back an application image does not automatically reverse a Flyway migration.

---

## Run Locally

The complete stack can be started with Docker Compose:

```bash
git clone https://github.com/Yian-Z2510/project_LedgerPay.git
cd project_LedgerPay

cp .env.example .env
docker compose up --build
```

For backend-only development with PostgreSQL configured:

```bash
./mvnw spring-boot:run
```

Health check:

```bash
curl http://localhost:8080/health
```

---

## Testing

LedgerPay combines **415 automated tests** with manual end-to-end validation of the deployed application.

Automated coverage includes API behaviour, authentication and ownership, business rules, PostgreSQL constraints, transaction rollback, idempotency races, refund concurrency, webhook behaviour, and full Payment/Refund lifecycle scenarios.

PostgreSQL-backed integration and concurrency tests are used for behaviours such as locking, uniqueness races, and transaction rollback that cannot be meaningfully proven with mocks alone.

The live application is also manually validated through the complete **Merchant → Order → Payment → Refund → Webhook** flow, including browser-based interaction, frontend-backend integration, persistence, webhook delivery, HTTPS deployment, and production health checks.

```bash
./mvnw test
```

---

## Further Reading

For deeper technical details:

- **[Architecture](docs/architecture.md)** — application, production, and CI/CD design
- **[Engineering Decisions](docs/engineering_decisions.md)** — idempotency, transactions, refund concurrency, webhook delivery, and deployment trade-offs
- **[API Design](docs/api_design.md)** — REST contracts, authentication, lifecycle rules, ownership, and error semantics
- **[Database Design](docs/database_design.md)** — schema relationships and database-level integrity guarantees
- **[Product Requirements](docs/PRD.md)** — LedgerPay v1 scope and business rules
- **[V2 Backlog](docs/v2_backlog.md)** — deferred features and engineering hardening opportunities

---

## Limitations & Future Evolution

LedgerPay v1 focuses on the core **Order → Payment → Refund → Webhook** lifecycle rather than the full scope of a commercial payment platform. Payments are simulated rather than connected to a real PSP, and capabilities such as settlement, reconciliation, disputes, fraud controls, and production merchant onboarding remain outside the current scope. The system also runs on a single application instance with PostgreSQL and a polling-based webhook worker, with lightweight infrastructure appropriate for a portfolio-scale deployment.

If LedgerPay continued into a second engineering phase, I would prioritize three areas:

1. **Event-driven architecture** — evolve webhook processing toward a transactional outbox, message queue, and independently scalable workers.
2. **Richer payment lifecycle** — integrate an external payment provider and model more realistic asynchronous flows such as authorization and capture.
3. **Distributed scaling** — support multiple stateless application instances, managed PostgreSQL, stronger observability, and higher availability.
