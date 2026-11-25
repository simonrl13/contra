# contra: portfolio summary

**One sentence:** A bank-transfers REST API in Java 21 and Spring Boot 4 with a double-entry ledger,
idempotent transfers and pessimistic locking, tested against real PostgreSQL.

**CV bullets**
- Built a money-transfer REST API (Java 21, Spring Boot 4, PostgreSQL 16) with a double-entry ledger
  where every account's balance is checked against the sum of its ledger entries, and a failed write rolls
  back the whole transfer.
- Made transfers safe to retry with a required `Idempotency-Key` stored in the same transaction as the
  money movement; 8 concurrent requests with one key move money exactly once.
- Prevented lost updates and deadlocks with `SELECT ... FOR UPDATE` row locks in a fixed order, proven
  by a 200-transfer, 16-thread test on real PostgreSQL that fails when the locks or the ordering are removed.
- Wrote 67 automated tests (unit, web and JPA slices, full-stack, concurrency) on PostgreSQL via
  Testcontainers, run on every push by GitHub Actions, and packaged the app as a non-root multi-stage
  Docker image with docker compose and health checks.
<!-- Enable after deploying to AWS (and replace the bracketed part with what you actually ran):
- Deployed to AWS on ECS Fargate behind an Application Load Balancer with RDS PostgreSQL, defined in
  CloudFormation, with least-privilege IAM, GitHub OIDC deploys (no long-lived keys) and a cost budget
  [running cost about $X/day, torn down after the demo].
-->

**Stack:** Java 21 · Spring Boot 4.2 (Web MVC, Data JPA, Validation, Actuator) · PostgreSQL 16 · Flyway ·
JUnit 5 · Mockito · AssertJ · Testcontainers · Docker · GitHub Actions · CloudFormation (AWS plan)

**Repo:** https://github.com/simonrl13/contra
