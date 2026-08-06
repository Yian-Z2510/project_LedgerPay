# LedgerPay

## Project Overview

LedgerPay is a backend payment gateway simulator built to practise REST API design, relational database design, transaction management, idempotency, concurrency control, webhook delivery, and testing. The project is currently under active development.

## Current Status

The following functionality is currently implemented:

- Spring Boot application scaffold
- Java 21 and Maven configuration
- PostgreSQL datasource configuration through environment variables
- `GET /health` application-level liveness endpoint
- Focused Spring MVC test for the health endpoint
- Full Spring application-context test

## Planned V1 Scope

The planned v1 design includes:

- Merchant registration and API-key authentication
- Merchant-scoped resource isolation
- Order lifecycle management
- Payment creation and manual payment simulation
- Refund creation and manual refund simulation
- Payment and Refund idempotency
- PostgreSQL transaction and locking rules
- Webhook event persistence, delivery, and retry
- Validation and consistent error responses

These capabilities are planned and are not yet implemented.

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

## Project Structure

```text
src/
├── main/
│   ├── java/com/ledgerpay/
│   │   ├── LedgerPayApplication.java
│   │   └── controller/
│   │       └── HealthController.java
│   └── resources/
│       └── application.properties
└── test/
    └── java/com/ledgerpay/
        ├── LedgerPayApplicationTests.java
        └── controller/
            └── HealthControllerTest.java
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

Pull requests are validated through GitHub Actions CI.