# PayLedger

[![CI](https://github.com/laeba27/payledger/actions/workflows/ci.yml/badge.svg)](https://github.com/laeba27/payledger/actions/workflows/ci.yml)

A card payment system in the style of Amex: virtual cards, authorize / capture / refund, a
double-entry ledger, idempotent APIs and rule-based fraud checks.

**Stack:** Java 21 · Spring Boot 4 · PostgreSQL 16 · Redis 7 · Flyway · Testcontainers · Docker · GitHub Actions

> Work in progress. Built in slices; see [`docs/learning/`](docs/learning) for a write-up of each one.

## Run it

```bash
docker compose up -d          # Postgres (localhost:5433) + Redis (localhost:6379)
./mvnw spring-boot:run
curl localhost:8080/actuator/health
```

## Test it

```bash
./mvnw test      # unit tests
./mvnw verify    # unit + integration tests (needs Docker)
```
