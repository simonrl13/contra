# Security

contra-api is a learning and portfolio project, not a production banking system. This file says what
is covered, what isn't, and how secrets are handled. It's updated at the end of each stage.

## Reporting an issue
Open a GitHub issue (no secrets or personal data in it), or contact the maintainer privately through
their GitHub profile for anything sensitive.

## Secrets
- No secrets in the code or in git history. The app reads `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`
  from environment variables only, and `application.yml` has no default values for them.
- Locally they live in `.env`, which is git-ignored. `.env.example` holds placeholders only.
- Tests use a throwaway Testcontainers database with generated credentials.
- In AWS (Stage 7), the database password will live in Secrets Manager and reach the app through the
  ECS task's IAM role. Deploys will use GitHub OIDC, so there are no long-lived AWS access keys.

## Authentication and authorisation
**Not implemented.** Anyone who can reach the API can create accounts and move money between them.
That's a deliberate v1 scope limit, and it's the main reason this must not be exposed publicly with
real data. A real deployment would need, at minimum, OAuth2/JWT authentication and checks that the
caller owns the source account.

## Threat model
| Threat | Mitigation | Status |
|---|---|---|
| Malformed or hostile input | Bean Validation on request DTOs (lengths, signs, at most 2 decimals, ISO currency); errors returned as ProblemDetail without stack traces or Jackson internals | Stages 2 and 3 |
| SQL injection | JPA, Spring Data derived queries and parameterised JPQL only; no string-built SQL | Stage 2, re-checked each stage |
| Invalid data reaching the DB through a bug | CHECK constraints: balance >= 0, debit/credit signs, ISO currency format, transfer amount > 0 and different accounts; FK from ledger entries to transfers | Stages 2 and 3 |
| Double spending through retries | Required `Idempotency-Key` with a unique constraint; a key reused with a different payload is rejected (422) | Stage 4 |
| Overdraft through concurrent requests | Row locking plus a `CHECK (balance >= 0)` constraint in the database | Stages 2 and 5 |
| Idempotency key abuse (guessing or reusing someone else's key) | Keys are scoped to the request payload. Without authentication they can't be scoped per client, a known gap | Partial, Stage 4 |
| Leaked credentials | Env vars only, `.env` git-ignored, IAM roles in AWS | Stage 1 (local), Stage 7 (AWS) |
| Vulnerable dependencies | Versions managed by the Spring Boot parent; Dependabot alerts | Dependabot: Stage 6 |
| Database exposed on the network | Local Postgres port bound to `127.0.0.1` only; RDS in private subnets in AWS | Stage 1 (local), Stage 7 (AWS) |

## Known gaps
- No authentication or authorisation (see above).
- No rate limiting.
- Swagger UI and `/v3/api-docs` are enabled in every environment. Decide in Stage 6/7 whether to
  turn them off outside local development (`springdoc.api-docs.enabled=false`).
- Supported framework line: Spring Boot 4.1.x, whose open-source support ends 2027-07-31.

## Dependency policy
- Spring Boot's parent pom manages versions. Anything it doesn't manage (springdoc) is pinned explicitly in `pom.xml`.
- New dependencies need a reason and a check of their maintenance status before they're added.

## AI features and AI-assisted development
- The application has **no AI features**: no model calls, so no prompt-injection surface and no data sent to a model at runtime.
- The code was written with an AI assistant (Claude Code). Stages 1-2 were reviewed by the maintainer
  before each commit. From Stage 3 on, the assistant wrote all the code and committed each stage once
  `./mvnw verify` passed; the maintainer reviews those commits afterwards, and the code is treated as
  untrusted until that review is done. No secrets are ever pasted into prompts.
