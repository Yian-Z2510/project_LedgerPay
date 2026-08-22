# LedgerPay

## Project Overview

LedgerPay is a backend payment gateway simulator built to practise REST API design, relational database design, transaction management, idempotency, concurrency control, webhook delivery, and testing. The project is currently under active development.

## Current Status

The following functionality is currently implemented:

- Spring Boot application scaffold
- Java 21 and Maven configuration
- PostgreSQL datasource configuration through environment variables
- `GET /health` application-level liveness endpoint
- Merchant registration, retrieval, webhook URL update, and API-key rotation endpoints
- Merchant API-key authentication, with only API-key hashes stored in PostgreSQL
- Merchant-scoped Order creation, retrieval, listing, amount update, and cancellation
- Merchant-scoped Payment creation, retrieval, Order history, and manual simulation
- Payment idempotency with current-representation replay and PostgreSQL race protection
- Order-row pessimistic locking across Payment creation and Order mutations
- Durable `PAYMENT_SUCCEEDED` and `PAYMENT_FAILED` WebhookEvent persistence
- Refund persistence, merchant-scoped create/query/history APIs, and historical idempotency replay
- Refund `PENDING` capacity reservation and Payment-row concurrency protection during creation
- Manual Refund simulation to `SUCCEEDED` or `FAILED`, with Payment refund accounting and Order `PARTIALLY_REFUNDED` / `REFUNDED` transitions
- Durable `REFUND_SUCCEEDED` and `REFUND_FAILED` WebhookEvent persistence
- Merchant soft-deactivation with unfinished Payment, Refund, and WebhookEvent checks
- Validation and consistent API error responses across the implemented modules
- Focused unit, MVC, persistence, security, integration, concurrency, and lifecycle tests

## Remaining Planned V1 Scope

The remaining planned v1 implementation includes:

- Webhook HTTP delivery and retry processing
- Validation and consistent error responses for the remaining delivery APIs

WebhookEvent records are already created durably with Payment and Refund terminal
transitions; only their external HTTP delivery and retry processing remain deferred.

## Tech Stack

- Java 21
- Spring Boot 4.1.0
- Maven with Maven Wrapper
- Spring Web MVC
- Spring Data JPA
- PostgreSQL
- JUnit 5
- MockMvc
- direnv as an optional, recommended local-environment tool

## Prerequisites

- Java 21
- PostgreSQL
- A local PostgreSQL database and user
- Git
- direnv (optional but recommended)

Installing Maven globally is not required because the repository includes Maven Wrapper. The setup is portable and does not require macOS or Homebrew.

## Local Setup

### 1. Clone and enter the repository

```bash
git clone <repository-url>
cd project_LedgerPay
```

### 2. Create the local PostgreSQL database and user

Run the following SQL using a PostgreSQL administrator account:

```sql
CREATE USER ledgerpay WITH PASSWORD 'replace_with_a_local_password';
CREATE DATABASE ledgerpay OWNER ledgerpay;
```

You may use different database names, usernames, and passwords if you update the environment configuration accordingly.

### 3. Create the local environment file

```bash
cp .env.local.example .env.local
```

Update `.env.local` with your local database settings:

```properties
DB_URL=jdbc:postgresql://localhost:5432/ledgerpay
DB_USERNAME=ledgerpay
DB_PASSWORD=replace_with_your_local_database_password
```

`.env.local` contains local credentials, is ignored by Git, and must never be committed.

### 4. Recommended direnv workflow

direnv is optional but recommended. After installing direnv and connecting it to your shell, allow the repository configuration once:

```bash
direnv allow
```

Entering the repository will then load values from `.env.local` through `.envrc` automatically.

### 5. Manual environment-variable alternative

If you do not use direnv, export the variables manually:

```bash
export DB_URL='jdbc:postgresql://localhost:5432/ledgerpay'
export DB_USERNAME='ledgerpay'
export DB_PASSWORD='replace_with_your_local_database_password'
```

These variables must be available to the Maven process that starts or tests the application.

## Run the Application

```bash
./mvnw spring-boot:run
```

The application runs on port `8080` by default. Verify it with:

```bash
curl http://localhost:8080/health
```

Expected response:

```json
{
  "status": "UP"
}
```

The current `/health` endpoint is an application-level liveness-style check. It confirms that the application can respond to HTTP requests but does not actively query PostgreSQL. No readiness endpoint is currently implemented.

## Run Tests

Run the focused Web MVC test:

```bash
./mvnw -Dtest=HealthControllerTest test
```

`HealthControllerTest` tests the Web MVC endpoint without requiring PostgreSQL.

Run the complete test suite when the datasource environment variables are already loaded:

```bash
./mvnw test
```

To load the local environment explicitly with direnv:

```bash
direnv exec . ./mvnw test
```

`LedgerPayApplicationTests` loads the complete Spring application context. The complete application-context test therefore requires valid datasource environment variables and an accessible PostgreSQL instance.

## Available Endpoints

| Method | Path | Description | Response |
| --- | --- | --- | --- |
| GET | `/health` | Application liveness-style check | `{"status":"UP"}` |
| POST | `/api/v1/merchants` | Register a Merchant and issue its first API key | `CreateMerchantResponse` |
| GET | `/api/v1/merchant` | Get the authenticated Merchant | `MerchantResponse` |
| PATCH | `/api/v1/merchant` | Update the authenticated Merchant webhook URL | `MerchantResponse` |
| POST | `/api/v1/merchant/api-key/rotate` | Rotate the authenticated Merchant API key | `RotateApiKeyResponse` |
| POST | `/api/v1/merchant/deactivate` | Soft-deactivate an eligible Merchant | `MerchantResponse` |
| POST | `/api/v1/orders` | Create an Order | `OrderResponse` |
| GET | `/api/v1/orders` | List the authenticated Merchant's Orders | `OrderResponse[]` |
| GET | `/api/v1/orders/{orderId}` | Get an owned Order | `OrderResponse` |
| PATCH | `/api/v1/orders/{orderId}` | Update an eligible Order amount | `OrderResponse` |
| POST | `/api/v1/orders/{orderId}/cancel` | Cancel an eligible Order | `OrderResponse` |
| POST | `/api/v1/payments` | Create or replay a Payment | `PaymentResponse` |
| GET | `/api/v1/payments/{paymentId}` | Get an owned Payment | `PaymentResponse` |
| GET | `/api/v1/orders/{orderId}/payments` | List Payment attempts newest first | `PaymentResponse[]` |
| POST | `/api/v1/payments/{paymentId}/simulate` | Simulate a terminal Payment outcome | `PaymentResponse` |
| POST | `/api/v1/payments/{paymentId}/refunds` | Create or replay a Refund | `RefundResponse` |
| GET | `/api/v1/refunds/{refundId}` | Get an owned Refund | `RefundResponse` |
| GET | `/api/v1/payments/{paymentId}/refunds` | List Refund history newest first | `RefundResponse[]` |
| POST | `/api/v1/refunds/{refundId}/simulate` | Simulate a terminal Refund outcome | `RefundResponse` |

## Project Structure

```text
src/
├── main/
│   ├── java/com/ledgerpay/
│   │   ├── controller/
│   │   ├── dto/
│   │   ├── entity/
│   │   ├── exception/
│   │   ├── repository/
│   │   ├── security/
│   │   ├── service/
│   │   └── validation/
│   └── resources/
│       ├── application.properties
│       └── db/migration/
└── test/
    └── java/com/ledgerpay/
        ├── controller/
        ├── dto/
        ├── exception/
        ├── repository/
        ├── security/
        └── service/
```

Other repository entries include:

```text
docs/
.envrc
.env.local.example
pom.xml
mvnw
mvnw.cmd
```

## Design Documentation

- [Product Requirements](docs/PRD.md)
- [API Design](docs/api_design.md)
- [Database Design](docs/database_design.md)
- [V2 Backlog](docs/v2_backlog.md)
