# contra-api

A REST API for bank accounts and money transfers between them: a double-entry ledger, idempotent
transfers, and safe concurrent updates. Java 21, Spring Boot 4.1, PostgreSQL 16.

> Work in progress. Done: project setup and accounts. Next: transfers and the ledger history.

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

## API examples
Open an account. A non-zero initial deposit is recorded in the ledger as an `OPENING` entry.
```bash
curl -i -X POST localhost:8080/accounts -H 'Content-Type: application/json' \
  -d '{"ownerName":"Ana","currency":"EUR","initialDeposit":100}'
```
```
HTTP/1.1 201
Location: /accounts/b8bde457-b621-451c-b302-0ebe4c87704e
{"id":"b8bde457-b621-451c-b302-0ebe4c87704e","ownerName":"Ana","currency":"EUR","balance":100.00,"createdAt":"2026-11-24T19:49:24.881193Z"}
```
Read it back:
```bash
curl localhost:8080/accounts/b8bde457-b621-451c-b302-0ebe4c87704e
```
Errors are `application/problem+json`, with every invalid field listed:
```json
{"detail":"Request validation failed","instance":"/accounts","status":400,"title":"Bad Request",
 "errors":{"initialDeposit":"must be greater than or equal to 0","ownerName":"must not be blank"}}
```
| Status | When |
|---|---|
| 400 | Invalid field, more than 2 decimals, unknown currency code, malformed JSON or id |
| 404 | Unknown account |

## Run the tests
```bash
./mvnw verify
```
The tests start a throwaway PostgreSQL 16 container, so Docker must be running. No `.env` is needed.

## Design decisions
Filled in as each stage lands. Still to come: idempotency and the locking strategy.
- **Money is `BigDecimal`, always 2 decimals.** `double` can't represent 0.10 exactly. Amounts with more
  than 2 decimals are rejected (400) rather than rounded: the API never silently changes a customer's
  number. The database uses `NUMERIC(19,2)`.
- **Ledger with signed amounts.** Every money movement is an append-only `ledger_entries` row: credits
  positive, debits negative. So an account's balance must always equal `SUM(amount)` of its entries,
  which the tests check. The initial deposit is an `OPENING` entry, so that rule holds from the moment
  the account is opened. (Simplification: in a real bank the opening credit would be matched by a debit
  on an internal funding account.)
- **Constraints in the database too.** `CHECK (balance >= 0)`, a debit must be negative, and a credit
  must be positive. The service enforces these rules, and the database stops any bug that gets past it.
- **Currency is `java.util.Currency`.** Unknown ISO codes are rejected while the JSON is parsed. Known
  limitation: every currency uses 2 decimals, which is wrong for JPY (0) or KWD (3).
- **Schema owned by Flyway.** Hibernate runs with `ddl-auto: validate`: it checks the entities against
  the schema but never changes it. Every schema change is a versioned SQL migration in `src/main/resources/db/migration`.
- **Errors as ProblemDetail (RFC 9457).** Every error response is `application/problem+json` with
  `title`, `status`, `detail` and `instance`. `type` is left out because it's the default
  `about:blank`. Validation errors add an `errors` map of field → message.
