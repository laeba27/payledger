# Phase 0: Setting up the machine

**Goal:** have everything needed to build and run PayLedger on a MacBook (Apple M1, 8 GB RAM).

## What this phase did

| Step | Result |
|---|---|
| Checked the machine | M1, 8 cores, 8 GB RAM, 74 GB free disk, macOS 14.4 |
| Checked existing tools | Java 21, Maven 3.9, Git, Homebrew, VS Code + Java extensions already installed |
| Installed **OrbStack** | Gives us the `docker` and `docker compose` commands |
| `git init` | Turned the folder into a Git repository |
| Added `.gitignore` | Tells Git which files never to commit |
| Added `CLAUDE.md` | The project's rules, in one place |

---

## 1. Tools we already had

### Java 21 (JDK)
- **What it is:** the Java Development Kit: the compiler (`javac`) plus the runtime (`java`).
- **Why we use it:** Spring Boot is written for Java. Java 21 is the current *LTS* (Long-Term
  Support) version, which is what banks run in production.
- **Why 21 and not 17:** it was already installed, Spring Boot fully supports it, and it adds
  **virtual threads**, which are worth mentioning in interviews (lightweight threads that make blocking I/O cheap).
- **Check:** `java -version` should print `21.x`.

### Maven
- **What it is:** a build tool. It downloads libraries ("dependencies"), compiles the code, runs
  the tests and packages a `.jar`.
- **Why we use it:** everything is declared in one file, `pom.xml`. Anyone can clone the repo
  and run `./mvnw verify` to get the same result.
- **Commands you'll use:**
  - `./mvnw test`: compile and run unit tests
  - `./mvnw verify`: unit + integration tests
  - `./mvnw spring-boot:run`: start the app
- **`mvnw` vs `mvn`:** `mvnw` is the *Maven Wrapper*, a small script committed to the repo that
  downloads the exact Maven version the project needs. CI and other machines then don't depend on
  whatever Maven happens to be installed.

### Git
- **What it is:** version control. It records every change as a *commit*.
- **Why we use it:** history, branches (one per slice), and pull requests on GitHub, which run CI.

### VS Code + Extension Pack for Java
- **Why:** autocomplete, running and debugging tests, and the Spring Boot dashboard. It's lighter than IntelliJ
  on 8 GB of RAM.

---

## 2. What we installed: OrbStack

### What is Docker, first?
Docker runs software in **containers**: isolated boxes that hold a program plus everything it
needs. Instead of installing PostgreSQL and Redis on your Mac, we run them as containers:

```
docker compose up -d      → Postgres + Redis start in seconds
docker compose down       → they're gone, and your Mac stays clean
```

Why this matters:
1. **Same everywhere:** your laptop, a teammate's laptop and GitHub Actions all use the same `postgres:16` image.
2. **Testcontainers:** our integration tests start a *real* Postgres and Redis inside Docker for
   each test run, then throw them away. Real databases catch bugs that fake in-memory ones miss.

### Why do we need OrbStack on a Mac?
Containers need a **Linux** kernel, and macOS isn't Linux. So on a Mac, something has to run a
tiny Linux virtual machine in the background. That's what Docker Desktop or OrbStack does.

### Why OrbStack and not Docker Desktop?

| | Docker Desktop | OrbStack |
|---|---|---|
| Background RAM use | ~2–4 GB | much lower, frees memory when idle |
| Startup | slow | ~2 seconds |
| Price | Free for personal use | Free for personal use |
| Commands | `docker`, `docker compose` | the same `docker`, `docker compose` |

On an 8 GB Mac, the saved memory matters. OrbStack uses the same `docker` commands, so
everything in this project (and Testcontainers) works unchanged.

**Install command used:** `brew install --cask orbstack`

### How to check it works
```bash
docker --version             # Docker version 29.x
docker compose version       # Docker Compose version v5.x
docker run --rm hello-world  # prints "Hello from Docker!"
```

### Daily use
- OrbStack must be **running** (menu bar icon) before `docker compose up` or `./mvnw verify`.
- To free RAM when you're not coding, quit OrbStack from the menu bar.

---

## 3. Files created

### `.gitignore`
Lists files Git should never commit:
- `target/`: build output, regenerated on every build
- `.idea/`, `.vscode/`, `.DS_Store`: editor and OS junk
- `.env`: local secrets
- `review.md`: personal planning notes, kept out of the public repo

### `CLAUDE.md`
The project rules in one page: money in cents, append-only ledger, never store card numbers,
and so on. Both people and AI tools read it, so every session follows the same rules.

---

## 4. Words to know

| Term | Meaning |
|---|---|
| JDK | Java Development Kit: compiler + runtime |
| LTS | Long-Term Support: a Java version that gets years of security fixes |
| Dependency | A library our code uses, e.g. Spring Web |
| Image | A read-only template for a container, e.g. `postgres:16` |
| Container | A running instance of an image |
| Docker Compose | Starts several containers together from one `docker-compose.yml` |
| Testcontainers | A Java library that starts real containers from inside tests |

---

## 5. Interview questions this phase prepares you for

**Q: Why run databases in Docker instead of installing them?**
The environment is the same across dev, CI and teammates, there's nothing to set up by hand, and you can
start or reset it in seconds. Testcontainers reuses the same images in tests.

**Q: Why Java 21?**
It's the current LTS version, so it's supported in enterprises for years. It also has virtual threads, records and
pattern matching.

**Q: What's the Maven Wrapper for?**
Reproducible builds. The project pins its own Maven version, so "works on my machine" problems
from different tool versions go away.

---

## Next: Slice 1, project skeleton
Spring Boot app, `docker-compose.yml` (Postgres + Redis), Flyway, a health endpoint, and a
GitHub Actions CI pipeline from day one.
