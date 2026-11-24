# contra-api: instructions for Claude

REST API for bank accounts and money transfers. Two goals: interview prep for a bank (Java, Spring,
SQL, testing, Docker, AWS, live coding) and a portfolio project the owner can explain line by line.
Learning matters more than speed.

## Stack
- Java 21, Spring Boot 4.1.x (chosen over 4.0.x: 4.0 OSS support ends 2026-12-31), Maven via `./mvnw`
- Spring Web MVC, Spring Data JPA, Bean Validation, PostgreSQL 16, Flyway (`ddl-auto: validate`, never `update`)
- springdoc-openapi 3.x (Swagger UI at `/swagger-ui.html`)
- Spring Boot Actuator (`/actuator/health` only, for health checks)
- Tests: JUnit 5, Mockito (loaded as a `-javaagent` by Surefire), AssertJ, `@WebMvcTest` / `@DataJpaTest` slices, Testcontainers (`postgres:16-alpine`)

## Domain rules
- Money is `BigDecimal`, scale 2, explicit `RoundingMode`. Never `double`. DB type `NUMERIC(19,2)`.
- Transfer: amount > 0, different accounts, same currency (no FX), no negative balance. Atomic.
- Double-entry ledger: entry types `OPENING`, `TRANSFER_DEBIT`, `TRANSFER_CREDIT`. An initial deposit is an
  `OPENING` credit with no transfer. Amounts are signed (debit < 0, credit > 0, enforced by CHECK
  constraints). Invariant: for every account, `SUM(amount)` of its entries = its balance.
- `common/Money.normalize` sets scale 2 with `RoundingMode.UNNECESSARY`; `@Digits(fraction = 2)` rejects more decimals.
- `POST /transfers` requires `Idempotency-Key`. Same key + same payload returns the original result;
  same key + different payload returns 422.
- Locks are taken in a consistent order (smaller UUID first) to avoid deadlocks.
- Errors: `ProblemDetail` via `common/GlobalExceptionHandler`. 400 bad input, 404 not found,
  409 concurrency conflict, 422 business rule broken.

## Code conventions
- Package by feature: `account`, `ledger`, `transfer`, `common`. Controller → Service → Repository.
- Balance changes go through `Account.debit/credit` (guards only). Ledger entries for transfers come from
  `LedgerEntry.transferDebit/transferCredit`, which set the sign. Business rejections are
  `TransferRejectedException(Reason)` → 422 with a `reason` property.
- Migrations so far: V1 accounts, V2 ledger_entries, V3 transfers (+ FK ledger_entries.transfer_id),
  V4 idempotency_key (unique) + request_hash on transfers.
- Transfers lock both accounts with `AccountRepository.findByIdForUpdate`, smaller UUID first, and look up
  the Idempotency-Key only after locking. 409: `ConflictException` or any `ConcurrencyFailureException`.
- Entities reference other aggregates by id (UUID), not `@ManyToOne`.
- API error messages are English (`spring.web.locale: en`, fixed resolver).
- DTOs are Java records at the API boundary. Entities never leave the service layer.
- Constructor injection only. `@Transactional` on service methods only.
- Clear names, small methods, no clever tricks: code the owner could rewrite live under time pressure.
- Keep it simple: no hexagonal architecture, no microservices.
- No secrets in code or commits. Config comes from environment variables; `.env` is git-ignored.

## Working rules (strict)
Updated 2026-11-24: the owner now lets Claude write all the code (AI tools are allowed in the interview)
and reviews everything afterwards. The job is to make that review easy and complete.
1. Claude writes all the code, including the transfer core, idempotency and locking.
2. Stages run back to back without stopping for approval. Stop only if a test fails and can't be fixed
   cleanly, or if a decision would change an earlier approved design.
3. One commit per stage, with a clear message, only after `./mvnw verify` passes.
4. For each stage, `NOTES.md` gets: what we built, the thought process behind each non-trivial decision
   (problem, options, choice, trade-off), a code walkthrough of the key methods, what an interviewer
   would challenge, and 3-5 practice questions with answers in `<details>` blocks.
5. Only claim what a test or a run has verified. Never call anything "production-ready".
6. No secrets in code or commits. Simple, readable code.
7. Note Spring Boot 4 vs 3.x API differences as they come up (interviewers may know 3.x better).
8. Stage 7 (AWS) is preparation only: docs and config, no AWS resources created, no credentials asked for.

## Stages
Done through Stage 6 (Docker: multi-stage, non-root; compose: app + postgres with health checks;
CI: `.github/workflows/ci.yml`; Dependabot; Actuator `health` only). Stage 7 is prepared, not deployed: `docs/aws.md` and `infra/` (CloudFormation, checked with cfn-lint).

1. Setup  2. Account  3. Transfer + ledger  4. Idempotency  5. Concurrency  6. Docker + CI + docs  7. AWS (plan only)
