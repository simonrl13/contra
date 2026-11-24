# contra-api

A REST API for bank accounts and money transfers between them: a double-entry ledger, idempotent
transfers, and safe concurrent updates. Java 21, Spring Boot 4.1, PostgreSQL 16.

> Work in progress. Stage 1 (project setup) is done; the API endpoints arrive in the next stages.

## Requirements
- JDK 21
- Docker (for the local database and for the tests, which use Testcontainers)

Maven itself is not needed: the project ships the Maven wrapper (`./mvnw`, or `mvnw.cmd` on Windows).

## Run locally
```bash
cp .env.example .env          # then set your own DB_PASSWORD
docker compose up -d --wait   # starts PostgreSQL 16 on localhost only
./mvnw spring-boot:run
```
- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs

The app reads its database settings from `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`. Locally they come
from `.env`; anywhere else, set them as environment variables. If port 5432 is already in use, change
`DB_PORT` and the port in `DB_URL` in `.env`.

Stop the database with `docker compose down`. Add `-v` to also delete its data.

## Run the tests
```bash
./mvnw verify
```
The tests start a throwaway PostgreSQL 16 container, so Docker must be running. No `.env` is needed.

## Design decisions
Filled in as each stage lands: BigDecimal money, the double-entry ledger, idempotency, and the locking strategy.
- **Schema owned by Flyway.** Hibernate runs with `ddl-auto: validate`: it checks the entities against
  the schema but never changes it. Every schema change is a versioned SQL migration in `src/main/resources/db/migration`.
- **Errors as ProblemDetail (RFC 9457).** Every error response is `application/problem+json` with
  `type`, `title`, `status`, `detail` and `instance`.
