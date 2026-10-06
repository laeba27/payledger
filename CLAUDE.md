# PayLedger

A small Amex-style card payment system: issue virtual cards, authorize, capture, refund,
with a double-entry ledger, idempotency and fraud rules. Built as a portfolio project.

## Stack
- Java 21, Spring Boot, Maven
- PostgreSQL (source of truth) + Redis (cache, fraud counters), run via Docker Compose (OrbStack)
- Flyway owns the schema; JPA uses `ddl-auto: validate`
- Later slices: Redpanda (Kafka API), Prometheus/Grafana, k6, each behind a Compose profile

## Rules
- Money is always `long` cents (`BIGINT`). Never `double`/`float`.
- Ledger entries are append-only. Corrections are new entries, never UPDATE/DELETE.
- Every ledger transaction must balance (debits == credits).
- Never store a full card number; only `last4` + a random token.
- Postgres is the source of truth; Redis is never the only copy of anything that matters.
- Organise code by feature (`card/`, `payment/`, `ledger/`…), not by layer.
- Unit tests are `*Test` (Surefire, no Docker); integration tests are `*IT` (Failsafe, Testcontainers).

## Commands
- `docker compose up -d` start Postgres + Redis
- `./mvnw test` unit tests
- `./mvnw verify` unit + integration tests

## Working agreement
- Build in slices; each slice is one branch + PR with green CI.
- Every phase ships a learning doc in `docs/learning/NN-name.md` explaining what was
  installed/written, why, and the interview questions it answers.
