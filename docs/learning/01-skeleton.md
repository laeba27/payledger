# Slice 1: Project skeleton

**Goal:** an empty but *production-shaped* Spring Boot app that:
- starts against a real PostgreSQL and Redis,
- manages its database schema with Flyway,
- reports its own health,
- runs as a Docker container,
- is tested automatically on every pull request.

There are no payment features yet. This is the foundation every later slice builds on.

```
                ┌──────────────────────── your Mac ────────────────────────┐
                │                                                          │
 browser/curl ──►  Spring Boot app :8080  ──JDBC──►  Postgres (container)  │
                │   /actuator/health      │         localhost:5433         │
                │                         └─Redis─►  Redis (container)     │
                │                                    localhost:6379        │
                └──────────────────────────────────────────────────────────┘
```

---

## 1. What we added (nothing to install this time)

The new tools are **libraries**. Maven downloads them automatically from `pom.xml`, so there's
no `brew install` this slice.

| Library (dependency) | What it is | Why we need it |
|---|---|---|
| `spring-boot-starter-webmvc` | Spring MVC + embedded Tomcat web server | Lets us write REST endpoints (`@RestController`) |
| `spring-boot-starter-data-jpa` | Hibernate + Spring Data repositories | Maps Java classes ↔ database tables |
| `postgresql` | The JDBC driver | How Java actually talks to Postgres |
| `spring-boot-starter-flyway` + `flyway-database-postgresql` | Database migration tool | Creates and changes tables in a controlled, versioned way |
| `spring-boot-starter-data-redis` | Redis client (Lettuce) | Idempotency cache and fraud counters later |
| `spring-boot-starter-validation` | Bean Validation (`@NotNull`, `@Positive`…) | Rejects bad request bodies with a 400 |
| `spring-boot-starter-actuator` | Production endpoints | `/actuator/health` checks DB + Redis |
| `*-test` starters | JUnit 5, Mockito, AssertJ, Spring test support | Writing tests |
| `testcontainers-postgresql`, `testcontainers-junit-jupiter`, `spring-boot-testcontainers` | Testcontainers 2 | Real Postgres/Redis inside tests |

### Why Spring Boot 4.1 (not 3.x as `review.md` said)?
Spring Boot 4 is the current version, and start.spring.io no longer offers 3.x. Two things changed
in version 4 that you can see in our `pom.xml`:
1. **Modular starters:** `spring-boot-starter-web` became `spring-boot-starter-webmvc`, and Flyway has
   its own `spring-boot-starter-flyway`. Each starter brings only what it needs, so apps are smaller.
2. **Testcontainers 2:** artifacts are renamed (`testcontainers-postgresql`), and
   `PostgreSQLContainer` moved to the `org.testcontainers.postgresql` package.

Saying "I used Spring Boot 4, and here's what changed from 3" in an interview shows you're current.

### What is a "starter"?
A starter is a bundle: one dependency that brings a set of libraries that work together, plus
**auto-configuration**. For example, add the Redis starter and set `spring.data.redis.host`, and Spring creates a working
`StringRedisTemplate` for you. You write no setup code.

---

## 2. File by file

### `pom.xml`: the build definition
- `<parent>spring-boot-starter-parent 4.1.1</parent>` → we inherit tested version numbers for
  every Spring library, so we don't pick versions ourselves.
- `<java.version>21</java.version>` → compile for Java 21.
- **`maven-failsafe-plugin`** (added by us): Maven has two test runners:

  | Plugin | Runs files named | Maven phase | Speed |
  |---|---|---|---|
  | Surefire | `*Test.java` | `./mvnw test` | fast, no Docker |
  | Failsafe | `*IT.java` | `./mvnw verify` | slower, real containers |

  This lets you run unit tests in seconds while coding, while CI runs everything.

### `mvnw`, `mvnw.cmd`, `.mvn/wrapper/`: Maven Wrapper
Pins Maven **3.9.16** for this project. Always use `./mvnw`, not `mvn`. CI uses it too, so
local and CI builds behave the same.

### `src/main/java/.../PayledgerApplication.java`
```java
@SpringBootApplication
public class PayledgerApplication {
    public static void main(String[] args) { SpringApplication.run(PayledgerApplication.class, args); }
}
```
`@SpringBootApplication` combines three things:
- `@Configuration`: this class can define beans.
- `@EnableAutoConfiguration`: set things up based on the jars on the classpath (Postgres driver
  present → create a DataSource).
- `@ComponentScan`: find our `@Service`, `@RestController`… in this package and below.

That's why **every class must live under `com.laeba.payledger`**.

### `src/main/resources/application.yml`: configuration
| Setting | Meaning |
|---|---|
| `datasource.url: ...localhost:5433/payledger` | Where Postgres is. Port **5433**, see the story below |
| `jpa.hibernate.ddl-auto: validate` | Hibernate must **never** create or alter tables; it only checks the entities match. Flyway owns the schema |
| `jpa.open-in-view: false` | Spring's default keeps a DB session open while the HTTP response is written, which hides slow lazy-loading queries. Turning it off is a well-known best practice |
| `data.redis.host/port` | Where Redis is |
| `management.endpoints...include: health,info` | Only expose safe Actuator endpoints. `/actuator/env` would leak secrets |
| `health.show-details: always` | Show the `db` + `redis` breakdown. We'll restrict this once auth exists (slice 8) |

### `src/main/resources/db/migration/V1__baseline.sql`: Flyway
Flyway runs SQL files **in version order**, exactly once each, and records them in a table called
`flyway_schema_history`:

```
 version | description | success
 1       | baseline    | t
```

Rules:
- Name format: `V<number>__<description>.sql` (two underscores).
- **Never edit a migration after it's applied.** Flyway stores a checksum and will refuse to start.
  To change something, add `V3__...sql`.
- On startup the app compares the files with the history table and applies only the new ones.

**Why not let Hibernate create tables (`ddl-auto: update`)?** It can't do safe renames or data
migrations, you can't review its SQL, and it might drop things. Banks review every schema change
in a PR, and Flyway files make that possible.

### `docker-compose.yml`: local infrastructure
- `postgres:16` and `redis:7`: pinned major versions, so behaviour doesn't change by surprise.
- `healthcheck`: Docker knows when Postgres is *ready*, not just started.
- `volumes: pgdata`: data survives `docker compose down` (use `down -v` to wipe it).
- `app` service with `profiles: ["app"]`: only starts when asked. Daily you run the app from
  the IDE or `./mvnw`; the containerised app proves the Dockerfile works.
- `depends_on ... condition: service_healthy`: the app waits until the DBs are healthy.
- Inside Docker's network the app reaches `postgres:5432` by **service name**, so the env vars
  override `localhost:5433`.

### `Dockerfile`: multi-stage build
```
Stage 1 (eclipse-temurin:21-jdk): copy pom → download deps → copy src → build jar
Stage 2 (eclipse-temurin:21-jre): copy only the jar, run as non-root user
```
- **Multi-stage:** the final image has no compiler, Maven or source code, so it's smaller and has
  fewer things to attack.
- **Copy `pom.xml` before `src`:** Docker caches each step. Dependencies only re-download when
  `pom.xml` changes, not on every code change.
- **Non-root user (`USER payledger`):** if someone breaks into the app, they aren't root in the
  container. Security teams check for this.
- `.dockerignore` keeps `target/`, `.git/` and docs out of the build context, which makes builds faster.

### `src/test/.../TestcontainersConfiguration.java`
Defines two beans that are **real containers**: `postgres:16` and `redis:7`, the same versions as
compose. `@ServiceConnection` tells Spring "take this container's random host and port and use them
as the datasource / Redis connection", so there are no hard-coded ports in tests.

### `src/test/.../PayledgerApplicationIT.java`: the first integration test
1. `healthIsUpWithDatabaseAndRedis`: boots the **whole app** on a random port, calls
   `/actuator/health` over real HTTP, and checks `UP` plus the presence of `db` and `redis`.
2. `flywayAppliedBaselineMigration`: queries `flyway_schema_history` to prove migrations ran.

It's named `*IT`, so Failsafe runs it during `verify`.

### `src/test/.../TestPayledgerApplication.java`
A handy extra: run its `main` from VS Code and the app starts **with Testcontainers** instead of
compose. It's useful when you don't want to run `docker compose up` yourself.

### `.github/workflows/ci.yml`: Continuous Integration
Runs on every **pull request** and every push to `main`:
1. Check out the code.
2. Install Java 21 (with a Maven cache so later runs are fast).
3. `./mvnw -B verify`: compile, unit tests, integration tests (GitHub's Ubuntu runners have
   Docker, so Testcontainers just works).
4. Upload test reports even if tests fail (`if: always()`), so you can download and read them.

Later we'll turn on **branch protection** so `main` can't receive code unless CI is green.

---

## 3. Problems we hit (and what they teach)

### Problem 1: `spring-boot-starter-parent:4.1.1.RELEASE` not found
start.spring.io's metadata says `4.1.1.RELEASE`, but Maven Central publishes it as `4.1.1`.
Spring stopped using the `.RELEASE` suffix years ago. **Fix:** `<version>4.1.1</version>`.
*Lesson:* when a dependency "can't be resolved", check the exact version on Maven Central.

### Problem 2: `role "payledger" does not exist`
The app connected to `localhost:5432`, but **a PostgreSQL installed directly on your Mac**
was already listening there, so the app talked to that one instead of our container. Proof:
```
lsof -nP -iTCP:5432 -sTCP:LISTEN
postgres  888 ... 127.0.0.1:5432   ← your Mac's Postgres
OrbStack  ...     *:5432           ← our container
```
**Fix:** map the container to **5433** on the Mac (`"5433:5432"`) and point `application.yml` there.
We didn't stop your local Postgres, since other projects may use it.
*Lesson:* "user does not exist" or "wrong password" errors with Docker often mean **port
conflicts**, and `lsof -i :PORT` shows who owns a port.

---

## 4. How to run it yourself

```bash
# 1. OrbStack must be running (menu bar icon)

# 2. start Postgres + Redis
docker compose up -d
docker compose ps                       # both should be "healthy"

# 3. run the app
./mvnw spring-boot:run

# 4. in another terminal
curl -s localhost:8080/actuator/health | python3 -m json.tool

# 5. look inside the database
docker compose exec postgres psql -U payledger -c "select * from flyway_schema_history"

# 6. run all tests (unit + integration)
./mvnw verify

# 7. optional: run the app as a container too
docker compose --profile app up -d --build

# 8. stop everything (add -v to also delete the database data)
docker compose --profile app down
```

---

## 5. Words to know

| Term | Meaning |
|---|---|
| Starter | A dependency bundle + auto-configuration |
| Auto-configuration | Spring creating beans for you based on classpath and properties |
| Bean | An object created and managed by Spring and injected where needed |
| JDBC | Java's standard API for talking to SQL databases |
| JPA / Hibernate | Maps Java objects to tables (ORM) |
| Migration | A versioned, one-time schema change script |
| Actuator | Spring's production endpoints (health, metrics) |
| Integration test | A test that uses real infrastructure (DB, Redis, HTTP) |
| CI | Automatically building and testing every change |
| Multi-stage build | A Dockerfile where a build image produces files for a smaller runtime image |

---

## 6. Interview questions this slice prepares you for

**Q: Why Flyway instead of `ddl-auto=update`?**
Versioned, reviewable SQL that runs the same in every environment. Hibernate's auto-DDL can't
handle renames or data migrations safely and isn't auditable. We use `validate` so Hibernate only
checks that the schema matches.

**Q: Why Testcontainers instead of H2 (in-memory DB) for tests?**
H2 isn't Postgres. Features like `CHECK` constraints, triggers, `jsonb` and locking behaviour differ.
A test that passes on H2 can fail in production. Testcontainers runs the *same* Postgres version
as production.

**Q: Unit vs integration tests: how do you organise them?**
`*Test` with mocks via Surefire (fast, `mvn test`); `*IT` with real containers via Failsafe
(`mvn verify`). Developers get fast feedback, and CI runs everything.

**Q: What does `open-in-view: false` do and why?**
It stops Hibernate keeping a session open during view rendering. Otherwise lazy-loading
silently fires extra queries outside the service layer, which causes N+1 problems and long-held DB
connections.

**Q: Why a multi-stage Dockerfile and a non-root user?**
Smaller image with no build tools inside, a smaller attack surface, and least privilege.

**Q: How do you know the app is healthy in production?**
Actuator's `/actuator/health` checks DB and Redis connectivity. Kubernetes (or a load balancer)
calls it to decide whether to send traffic to the app.

---

## Next: Slice 2, accounts + double-entry ledger
The heart of a payment system: an `accounts` table, an append-only `ledger_entries` table, and
**database-enforced** rules (no UPDATE/DELETE on the ledger, every transaction balances).
