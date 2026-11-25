# contra-api

[![CI](https://github.com/simonrl13/contra/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/simonrl13/contra/actions/workflows/ci.yml)

A REST API for bank accounts and money transfers between them: a double-entry ledger, idempotent
transfers, and safe concurrent updates. Java 21, Spring Boot 4.1, PostgreSQL 16.

A learning and portfolio project, not a production system: there is no authentication (see
[SECURITY.md](SECURITY.md)). Study notes for every stage are in [NOTES.md](NOTES.md), and the AWS
deployment plan is in [docs/aws.md](docs/aws.md) (prepared, not deployed).

## Highlights
Each point is backed by a test in `src/test` or a real run:
- **Double-entry ledger.** Every transfer writes a debit and a credit in the same transaction as the
  balance updates. Tests check that each account's balance equals the sum of its ledger entries, and
  that a failed ledger write rolls everything back.
- **Idempotent transfers.** A retry with the same `Idempotency-Key` returns the original transfer and
  moves no money. 8 concurrent requests with one key still move money exactly once.
- **Safe under concurrency.** 200 transfers from 16 threads against real PostgreSQL: no negative
  balance, total money unchanged. The test fails if the row locks or the lock ordering are removed
  (checked by hand).
- **Tested on the real database.** 67 tests (unit, web and JPA slices, full-stack) run against
  PostgreSQL 16 in Testcontainers, on every push via GitHub Actions.
- **Container-ready.** Multi-stage Docker image running as a non-root user; `docker compose` brings up
  the app and the database with health checks.

## Requirements
- JDK 21
- Docker (for the local database and for the tests, which use Testcontainers)

Maven itself is not needed: the project ships the Maven wrapper (`./mvnw`, or `mvnw.cmd` on Windows).

## Run locally
First `cp .env.example .env` and set your own `DB_PASSWORD`. Then either:

**Everything in Docker** (app + PostgreSQL 16, both with health checks):
```bash
docker compose up -d --build --wait
```
**Or the database in Docker and the app from your IDE/terminal:**
```bash
docker compose up -d --wait postgres
./mvnw spring-boot:run
```
- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs
- Health: http://localhost:8080/actuator/health

The app reads its database settings from `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`. Locally they come
from `.env`; anywhere else, set them as environment variables. If port 5432 or 8080 is already in use,
change `DB_PORT` (and the port in `DB_URL`) or `APP_PORT` in `.env`. Both ports are bound to
`127.0.0.1` only.

Stop with `docker compose down`. Add `-v` to also delete the database data.

The image is a multi-stage build: a JDK stage builds the jar, and the runtime stage has only the JRE
and the jar, and runs as an unprivileged user (uid 10001).

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
Move money (same currency only). The response is the transfer, and both accounts get a ledger entry.
`Idempotency-Key` is required: generate one per intended transfer and send the same one on every retry.
A retry with the same key and body returns the original transfer and moves no money.
```bash
curl -i -X POST localhost:8080/transfers -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"fromAccountId":"<ana-id>","toAccountId":"<bob-id>","amount":30,"currency":"EUR"}'
curl localhost:8080/transfers/<transfer-id>
curl 'localhost:8080/accounts/<ana-id>/transactions?page=0&size=20'   # newest first, max size 100
```
A transfer that breaks a business rule is a 422 with a machine-readable `reason`
(`AMOUNT_NOT_POSITIVE`, `SAME_ACCOUNT`, `CURRENCY_MISMATCH`, `INSUFFICIENT_FUNDS`):
```json
{"detail":"Insufficient funds in the source account","instance":"/transfers","status":422,
 "title":"Unprocessable Content","reason":"INSUFFICIENT_FUNDS"}
```

| Status | When |
|---|---|
| 400 | Invalid field, more than 2 decimals, unknown currency code, malformed JSON or id, bad page/size |
| 404 | Unknown account or transfer |
| 409 | Another request with the same `Idempotency-Key` (and different accounts) was processed at the same moment, or a database lock conflict; retry |
| 422 | Transfer breaks a business rule, or the `Idempotency-Key` was already used for a different request (see `reason`) |

## Run the tests
```bash
./mvnw verify
```
The tests start a throwaway PostgreSQL 16 container, so Docker must be running. No `.env` is needed.
They include unit tests, `@WebMvcTest` and `@DataJpaTest` slices, full-stack HTTP tests, and
concurrency tests (200 transfers on 16 threads, races on one `Idempotency-Key`) against real PostgreSQL.

CI (GitHub Actions, `.github/workflows/ci.yml`) runs `./mvnw verify` and builds the Docker image on every
push to `main` and every pull request. Dependabot opens weekly update PRs for Maven, GitHub Actions and
the Docker base images.

Known build noise: Maven warns `Could not transfer metadata ... HTTP Status: 401` for two Redgate/Flyway
repositories declared in Flyway's own pom. Nothing is downloaded from them, and the build is unaffected.

## Design decisions
The reasoning behind each one (options, trade-offs, interview angles) is in [NOTES.md](NOTES.md).
- **Money is `BigDecimal`, always 2 decimals.** `double` can't represent 0.10 exactly. Amounts with more
  than 2 decimals are rejected (400) rather than rounded: the API never silently changes a customer's
  number. The database uses `NUMERIC(19,2)`.
- **Ledger with signed amounts.** Every money movement is an append-only `ledger_entries` row: credits
  positive, debits negative. So an account's balance must always equal `SUM(amount)` of its entries,
  which the tests check. The initial deposit is an `OPENING` entry, so that rule holds from the moment
  the account is opened. (Simplification: in a real bank the opening credit would be matched by a debit
  on an internal funding account.)
- **Transfers: check everything, then change anything.** All rules run before any write; then one
  transaction debits, credits, inserts the transfer and its two ledger entries. A failure anywhere rolls
  all of it back (tested by forcing the ledger write to fail). Balance changes only go through
  `Account.debit/credit`, and ledger signs only through `LedgerEntry.transferDebit/transferCredit`.
- **Idempotency in the same transaction as the money.** The key and a SHA-256 of the request are
  columns on `transfers`, with a `UNIQUE` constraint on the key. The lookup catches ordinary retries;
  the constraint catches two requests racing with the same key (the loser rolls back and gets 409).
  Only completed transfers are stored, so a rejected request doesn't use up its key.
- **Pessimistic locking, smaller id first.** A transfer reads both accounts with `SELECT ... FOR UPDATE`,
  always locking the smaller UUID first, so concurrent transfers queue instead of losing updates, and
  A→B / B→A can't deadlock. Optimistic locking with retries was rejected: under contention it fails
  instead of waiting and needs a retry loop. `@Version` stays on `Account` as a safety net. A test runs
  200 concurrent transfers on 16 threads and checks that no balance goes negative, the total is unchanged,
  and every ledger matches its balance. It fails if the locks or the ordering are removed (checked by hand).
- **Constraints in the database too.** `CHECK (balance >= 0)`, a debit must be negative, and a credit
  must be positive. The service enforces these rules, and the database stops any bug that gets past it.
- **Currency is `java.util.Currency`.** Unknown ISO codes are rejected while the JSON is parsed. Known
  limitation: every currency uses 2 decimals, which is wrong for JPY (0) or KWD (3).
- **Schema owned by Flyway.** Hibernate runs with `ddl-auto: validate`: it checks the entities against
  the schema but never changes it. Every schema change is a versioned SQL migration in `src/main/resources/db/migration`.
- **Errors as ProblemDetail (RFC 9457).** Every error response is `application/problem+json` with
  `title`, `status`, `detail` and `instance`. `type` is left out because it's the default
  `about:blank`. Validation errors add an `errors` map of field (or header/parameter) → message.
- **Config only from the environment.** No secrets in files that are committed or baked into the
  image (`.env` is in both `.gitignore` and `.dockerignore`). A missing `DB_*` variable stops startup
  with a one-line message naming it.
