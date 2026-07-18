# Portfolio Service

Combined transactions + portfolio Quarkus service. The per-user transaction ledger is the single source of truth; positions and quant history are derived on read. Market data is store-first (Postgres) to avoid repeat provider costs.

## Stack

- Quarkus **3.33.2.1** LTS, Java 21
- Hibernate Reactive + Panache, PostgreSQL, Liquibase
- TwelveData REST client (store-first read-through)
- Native + JVM Docker images

## Multi-user

Every API request (except `/q/*`) requires header:

```
X-User-Id: <trusted-user-id>
```

Identity is assumed already authenticated by the VPC edge. Ledger rows are scoped by `user_id`. Market data is global/shared.

## Key endpoints

| Area | Paths |
|------|--------|
| Transactions | `/api/transactions` CRUD + search + count |
| Positions | `/api/positions`, `/active`, `/ticker/{ticker}`, `/ticker/{ticker}/price` |
| Portfolio | `/api/portfolio/summary`, `/summary/active` |
| Daily history | `/api/daily-history/positions`, `/prices`, `/portfolio/valuation`, `/capital-flows`, `/performance-inputs` |
| Ingestion | `/api/price-ingestion/runs`, `/runs/latest` |

## Full stack (Docker Compose)

From this directory, native build is the default:

```bash
cd portfolio-service
cp .env.example .env   # optional; set TWELVE_DATA_API_KEY if needed
docker compose up --build
```

| Service | URL |
|---------|-----|
| API | http://localhost:8087 |
| Swagger | http://localhost:8087/q/swagger-ui |
| Health | http://localhost:8087/q/health |
| Adminer | http://localhost:8094 (server: `postgresql`) |
| Postgres | `localhost:5436` / `portfolio_service_db` |

Faster JVM image (skips GraalVM native compile):

```bash
DOCKERFILE=Dockerfile.jvm docker compose up --build
```

All API calls need `X-User-Id`:

```bash
curl -H "X-User-Id: demo" http://localhost:8087/api/transactions
```

## Local run (dev mode)

```bash
docker compose up -d postgresql adminer
./gradlew quarkusDev
```

Default HTTP port: **8087**. DB: `localhost:5436/portfolio_service_db`.

## Tests

Test and JaCoCo configuration lives in `gradle/testing.gradle`.

Unit tests (JaCoCo gate: **≥90% line and branch** coverage):

```bash
./gradlew test
./gradlew jacocoTestReport jacocoTestCoverageVerification
# HTML report: build/reports/jacoco/test/html/index.html
```

`check` runs unit tests and fails if coverage is below 90%. DTOs, entities, ports, models, bootstrap, REST clients, and Panache persistence adapters are excluded from the unit gate (adapters are covered by DBRider integration tests).

Integration tests (Docker required — Testcontainers Postgres + WireMock):

```bash
./gradlew integrationTest
./gradlew integrationTest --tests com.portfolio.integration.rest.PositionApiIT
```

`./gradlew build` runs unit tests, JaCoCo verification, then integration tests, and prints a summary to the console (also written to `build/reports/build-summary.txt`). ITs use DBRider datasets under `src/integrationTest/resources/datasets/` and programmatic WireMock for Twelve Data (`/price`, `/time_series`, `/exchange_rate`).

## Docker images only

```bash
docker build -t portfolio-service .                 # native
docker build -f Dockerfile.jvm -t portfolio-service:jvm .
```
