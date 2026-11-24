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
- AWS (planned in docs/aws.md, not deployed): RDS generates the database password and keeps it in
  Secrets Manager. ECS injects it at task start using the task execution role, which may read only that
  one secret. The template and task definition hold only the secret's ARN. The app has no task role
  (no AWS permissions). Deploys use GitHub OIDC with a role limited to one repository and environment,
  so there are no long-lived AWS access keys anywhere.

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
| Double spending through retries | Required `Idempotency-Key` with a unique constraint, written in the same transaction as the money movement; a key reused with a different payload is rejected (422); concurrent duplicates: one wins, the rest get 409 (tested with 8 threads) | Stage 4 |
| Overdraft through concurrent requests | `SELECT ... FOR UPDATE` on both accounts, in a fixed order; `CHECK (balance >= 0)` in the database; tested with 200 concurrent transfers | Stages 2 and 5 |
| Idempotency key abuse (guessing or reusing someone else's key) | A reused key only replays if the payload hash matches. Keys are global: without authentication they can't be scoped per client, so a client who knows another's key and payload can read that transfer. Keys never expire | Partial (known gap) |
| Leaked credentials | Env vars only, `.env` git-ignored and docker-ignored; in AWS: RDS-managed secret, IAM roles, GitHub OIDC (planned) | Stage 1 (local), Stage 7 (AWS plan) |
| Vulnerable dependencies | Versions managed by the Spring Boot parent; Dependabot weekly PRs for Maven, GitHub Actions and Docker base images | Stage 6 |
| Compromised container | Runtime image has only the JRE and the jar, runs as uid 10001 (not root), jar owned by root and read-only to the app; no `.env` in the build context (`.dockerignore`) | Stage 6 |
| Information leak through operational endpoints | Actuator exposes `health` only, without component details; `/actuator/env` etc. return 404 (tested) | Stage 6 |
| CI supply chain | Workflow token is `contents: read`; no secrets in CI; actions pinned to a major version (Dependabot keeps them current). Not pinned to commit SHAs, a known gap | Stage 6 |
| Database exposed on the network | Local Postgres port bound to `127.0.0.1` only; in AWS: RDS in private subnets, reachable only from the app's security group (planned) | Stage 1 (local), Stage 7 (AWS plan) |

## Known gaps
- No authentication or authorisation (see above).
- No rate limiting.
- Swagger UI and `/v3/api-docs` are enabled by default (useful locally). The AWS plan turns them off
  with environment variables (`SPRINGDOC_APIDOCS_ENABLED=false`, `SPRINGDOC_SWAGGERUI_ENABLED=false`);
  see docs/aws.md.
- Idempotency keys are global and never expire (see the threat model).
- No lock timeout: a transaction stuck while holding an account lock makes other transfers on that
  account wait. Mitigation if needed: `lock_timeout` / `idle_in_transaction_session_timeout` in PostgreSQL.
- Supported framework line: Spring Boot 4.1.x, whose open-source support ends 2027-07-31.

## Dependency policy
- Spring Boot's parent pom manages versions. Anything it doesn't manage (springdoc) is pinned explicitly in `pom.xml`.
- New dependencies need a reason and a check of their maintenance status before they're added.
  Stage 6 added `spring-boot-starter-actuator` (official Spring Boot module, version managed by the
  parent) for the container and load-balancer health check.
- Dependabot (`.github/dependabot.yml`) opens weekly PRs; CI must pass before one is merged.

## AI features and AI-assisted development
- The application has **no AI features**: no model calls, so no prompt-injection surface and no data sent to a model at runtime.
- The code was written with an AI assistant (Claude Code). Stages 1-2 were reviewed by the maintainer
  before each commit. From Stage 3 on, the assistant wrote all the code and committed each stage once
  `./mvnw verify` passed; the maintainer reviews those commits afterwards, and the code is treated as
  untrusted until that review is done. No secrets are ever pasted into prompts.
