# contra-api: instructions for Claude

REST API for bank accounts and money transfers. Two goals: interview prep for a bank (Java, Spring,
SQL, testing, Docker, AWS, live coding) and a portfolio project the owner can explain line by line.
Learning matters more than speed.

## Stack
- Java 21, Spring Boot 4.1.x (chosen over 4.0.x: 4.0 OSS support ends 2026-12-31), Maven via `./mvnw`
- Spring Web MVC, Spring Data JPA, Bean Validation, PostgreSQL 16, Flyway (`ddl-auto: validate`, never `update`)
- springdoc-openapi 3.x (Swagger UI at `/swagger-ui.html`)
- Tests: JUnit 5, Mockito, AssertJ, `@WebMvcTest` / `@DataJpaTest` slices, Testcontainers (`postgres:16-alpine`)

## Domain rules
- Money is `BigDecimal`, scale 2, explicit `RoundingMode`. Never `double`. DB type `NUMERIC(19,2)`.
- Transfer: amount > 0, different accounts, same currency (no FX), no negative balance. Atomic.
- Double-entry ledger: entry types `OPENING`, `TRANSFER_DEBIT`, `TRANSFER_CREDIT`. An initial deposit is an
  `OPENING` credit with no transfer. Invariant: for every account, sum of its entries = its balance.
- `POST /transfers` requires `Idempotency-Key`. Same key + same payload returns the original result;
  same key + different payload returns 422.
- Locks are taken in a consistent order (smaller UUID first) to avoid deadlocks.
- Errors: `ProblemDetail` via `common/GlobalExceptionHandler`. 400 bad input, 404 not found,
  409 concurrency conflict, 422 business rule broken.

## Code conventions
- Package by feature: `account`, `transfer`, `common`. Controller → Service → Repository.
- DTOs are Java records at the API boundary. Entities never leave the service layer.
- Constructor injection only. `@Transactional` on service methods only.
- Clear names, small methods, no clever tricks: code the owner could rewrite live under time pressure.
- Keep it simple: no hexagonal architecture, no microservices.
- No secrets in code or commits. Config comes from environment variables; `.env` is git-ignored.

## Working rules (strict)
1. Plan first; get approval before writing code.
2. One stage at a time. After each stage: run `./mvnw verify`, show the diff summary, explain the key
   Spring/Java concepts as needed for an interview, then STOP and wait.
3. The owner writes the core logic: transfer validation, balance update, ledger entries, locking and
   idempotency. Claude writes signatures, failing tests and TODO comments, then reviews critically.
   Claude writes boilerplate (config, DTOs, migrations, Docker, CI).
4. In stages 3-5, never offer to write the owner's logic, even when they're stuck. Give hints first,
   escalating only when asked.
5. End each stage with 3 interview-style questions, and add a short entry to `NOTES.md`
   (what we built, key concepts, the questions).
6. Only claim what a test or a run has verified. Never call anything "production-ready".
7. One commit per stage, only after the owner approves the stage, with a clear message.
8. Note Spring Boot 4 vs 3.x API differences as they come up (interviewers may know 3.x better).

## Stages
1. Setup  2. Account  3. Transfer + ledger  4. Idempotency  5. Concurrency  6. Docker + CI + docs  7. AWS
