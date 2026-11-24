# Interview notes

Start here: the pitch, the architecture, a one-page cheat sheet and how AI was used. After that comes one
entry per stage: what we built, the thought process, a code walkthrough, what an interviewer would
challenge, and practice questions (answers hidden in `<details>`).

---

## The 2-minute pitch
> contra-api is a REST API for bank accounts and money transfers, built with Java 21, Spring Boot 4.1 and
> PostgreSQL 16. I built it to practise what matters in banking backends: correctness under failure and
> under concurrency.
>
> Every transfer is double-entry. It writes a debit and a credit to an append-only ledger in the same
> transaction as the balance updates, so an account's balance always equals the sum of its ledger
> entries. The tests check that invariant everywhere. Money is `BigDecimal` with exactly two decimals:
> anything with more is rejected rather than rounded.
>
> Two things make it safe in the real world. First, idempotency: every transfer needs an
> `Idempotency-Key`. A retry with the same key returns the original result instead of moving money
> twice, and a unique constraint in the database catches two retries racing each other. Second,
> concurrency: the service locks both accounts with `SELECT ... FOR UPDATE`, always in the same order, so
> concurrent transfers queue instead of losing updates or deadlocking. I chose pessimistic over
> optimistic locking because it needs no retry loop, and I can explain the trade-off.
>
> I tested it against real PostgreSQL with Testcontainers, including a test that fires 200 transfers
> from 16 threads at once and checks that no balance goes negative and no money is created or lost. I
> also checked that test catches real bugs: it fails when I remove the locks or the lock ordering. The
> project ships as a non-root Docker image with CI on GitHub Actions, and I have a costed AWS deployment
> plan with least-privilege IAM and no long-lived keys.
>
> I used Claude Code to write much of the code. My job was the design decisions, reviewing every line,
> and making sure each claim is backed by a test or a real run.

## Architecture: one request, from controller to commit
```
POST /transfers  (Idempotency-Key header, JSON body)
 │
 ├─ TransferController            HTTP only: @Valid body + @NotBlank @Size header → 400 if invalid
 │                                 (GlobalExceptionHandler turns every exception into ProblemDetail JSON)
 ▼
 TransferService.transfer         @Transactional: one DB transaction from here to the return
 │  1. normalise amount (scale 2), hash the request (SHA-256)
 │  2. amount > 0, from ≠ to                               → 422 AMOUNT_NOT_POSITIVE / SAME_ACCOUNT
 │  3. SELECT ... FOR NO KEY UPDATE both accounts, smaller UUID first   (waits if another transfer holds them)
 │  4. key already used?  same hash → return the original (201)  |  different hash → 422 IDEMPOTENCY_KEY_REUSED
 │  5. currencies match, source has funds                  → 422 CURRENCY_MISMATCH / INSUFFICIENT_FUNDS
 │  6. account.debit / account.credit                       (in memory; dirty checking writes them later)
 │  7. INSERT transfer + flush        unique key clash → rollback, 409
 │  8. INSERT 2 ledger entries        (−amount for the source, +amount for the target)
 ▼
 commit: Hibernate flushes the 2 account UPDATEs; PostgreSQL commits 5 statements atomically; locks released
 │
 ▼
 201 Created, Location: /transfers/{id}, body = TransferResponse (a record DTO, never the entity)
```
Layers: Controller → Service → Repository (Spring Data JPA) → PostgreSQL. The schema belongs to Flyway
(V1-V4), and Hibernate only validates it. Packages are by feature: `account`, `ledger`, `transfer`, `common`.

## Cheat sheet
**Transactions (`@Transactional`)**
- Applied by a Spring proxy: calling a `@Transactional` method from the same class skips it. Rolls back
  on `RuntimeException` by default (not on checked exceptions).
- Inside a transaction, loaded entities are *managed*. Changes are written at flush/commit (dirty
  checking), with no `save()` needed. `readOnly = true` skips dirty checking.
- Atomic in PostgreSQL: the commit record hits the WAL in one step, so a crash leaves all or nothing.
  The default isolation is READ COMMITTED: each statement sees what was committed before it started.

**Locking**
- Lost update = two read-modify-writes overlap. Fixes: pessimistic (`@Lock(PESSIMISTIC_WRITE)` → `FOR
  UPDATE`/`FOR NO KEY UPDATE`: others wait), optimistic (`@Version` → `UPDATE ... WHERE version = ?`,
  0 rows = conflict → retry), an atomic `UPDATE ... WHERE balance >= ?`, or `SERIALIZABLE`.
- Pessimistic: deterministic and queues under contention, but deadlock risk → **lock in a global order**.
  Optimistic: no DB locks and good for rare conflicts, but needs a retry loop and suffers retry storms.
- Locks live in the database, so they work across app instances. `synchronized` protects one JVM only.

**Idempotency**
- The client sends a unique key per intended operation, and the same key on retries. The server
  stores it *in the same transaction* as the effect.
- Same key + same payload → the original result. Same key + different payload → 422. A race →
  unique constraint, the loser rolls back → 409 (here: identical requests wait on the account locks and
  replay instead).
- The lookup is the fast path. The **unique constraint** is the guarantee (check-then-act races).

**Double-entry ledger**
- Every movement is two entries that sum to zero (debit −, credit +). Append-only: corrections are new
  entries, never updates.
- Invariant: balance = `SUM(entries)`. The stored balance is an O(1) cache of it, protected by tests and
  CHECK constraints (`balance >= 0`, sign matches type).

**BigDecimal**
- Never `double` (0.1 + 0.2 ≠ 0.3). Construct from a `String`, not a `double`.
- `equals` compares scale (`1.0` ≠ `1.00`), `compareTo` doesn't. Normalise to scale 2. Round only with
  an explicit `RoundingMode` (here `UNNECESSARY`: refuse to round). DB type `NUMERIC(19,2)`.

**Flyway**
- Versioned SQL migrations `V<n>__name.sql`, applied once, in order, recorded in `flyway_schema_history`,
  and checksummed (editing an applied file fails startup).
- With `ddl-auto: validate`, Hibernate checks but never changes the schema. Never `update` in shared environments.

**Testcontainers**
- Real PostgreSQL in Docker per test run. `@ServiceConnection` wires the DataSource automatically.
- Catches what H2 can't: real locking, `FOR UPDATE`, CHECK/unique constraints, `NUMERIC`, SQL dialect.
- Slices: `@WebMvcTest` (MVC only, service mocked), `@DataJpaTest` (JPA only, rolled back), `@SpringBootTest` (everything).

**ProblemDetail (RFC 9457)**
- A standard error body: `type`, `title`, `status`, `detail`, `instance`, plus custom properties
  (`errors`, `reason`). Content type `application/problem+json`.
- `@RestControllerAdvice extends ResponseEntityExceptionHandler` → Spring's own errors use it too. Map
  domain exceptions to statuses in one place: 400 bad input, 404 not found, 409 conflict/retry, 422 business rule.

## How I used AI on this project
**What Claude Code generated.**
- Stages 1-2: Claude wrote the setup, configuration, migrations, DTOs, the account code and the tests.
  I reviewed and approved each stage before its commit, and some fixes came from my review (for example
  the locale bug and a clear startup message for missing env vars).
- Stages 3-6: originally, I was going to write the transfer core, idempotency and locking myself, with
  Claude writing only failing tests and TODOs. When I learned AI tools are allowed in the interview, I
  changed the rule. Claude wrote all the code, each stage was committed after `./mvnw verify` passed,
  and I review it afterwards. Stage 7 (AWS) was written as a plan and templates only.

**How each part was verified.**
- **Tests on real PostgreSQL**: 67 tests, all passing at the final commit. They include rollback (every
  ledger save made to fail), constraints, idempotency races (8 threads, one key) and 200 concurrent
  transfers on 16 threads.
- **Testing the tests**: the concurrency test was run against two deliberately broken versions (no locks,
  and locks without ordering). It failed both times, with an optimistic-lock error and a deadlock.
  The idempotency race test was checked in the logs: 7 of 8 threads really hit the unique constraint.
- **Real runs**: the Docker image was built and run with compose. Both containers became healthy, and
  every API path was exercised with curl. The image runs as uid 10001. Springdoc was confirmed off with
  the env vars used in AWS.
- **Failures that review and tests caught along the way**: header validation silently changed the
  400 error format (caught by an existing test); `mvnw` wasn't executable in git, which would have
  broken CI on Linux; a stale build after a manual file restore produced a misleading deadlock; and
  App Runner had closed to new customers, which a plan written from memory would have missed.
- **Secrets scan**: `gitleaks` 8.30 over the full git history and the working tree found no leaks.
- **Not verified**: the GitHub Actions workflow (no remote yet) and anything in AWS (templates only
  pass `cfn-lint`).

**What I'd check before trusting AI-generated code at a bank.**
- **Money paths line by line**: rounding and scale, sign of every ledger entry, the order of check vs.
  mutation, what happens on every exception path, and that the transaction boundary covers every write.
- **Concurrency claims need a test that fails without the fix**: don't trust "this is thread-safe" or
  a green test that never actually raced.
- **The database as the last line of defence**: constraints, unique keys and FKs for every invariant
  the code claims.
- **Security**: no secrets in code, logs or images; least-privilege IAM (look for `Resource: "*"` and
  unscoped `iam:PassRole`); input validation at the boundary; error messages that don't leak internals.
- **Dependencies and facts**: every new library is justified and maintained; versions and service
  availability are checked against current sources, not the model's memory (App Runner is the example).
- **Claims match evidence**: every "it works" in the docs should point to a test or a run. If it
  can't, it says "not verified".
- **Ownership**: I can explain and rewrite every important method without the tool. That's what the
  rest of this file is for.

---

## Stage 1: Setup

**Built:** a Maven project (Spring Boot 4.1.3, Java 21) with the Maven wrapper; DB configuration only
from environment variables; Flyway with `ddl-auto: validate`; a `@RestControllerAdvice` that returns
ProblemDetail; PostgreSQL 16 in docker-compose; and one Testcontainers test that boots the whole app
against a real Postgres 16.

**Key concepts**
- **Starter parent + starters.** The parent pom pins compatible versions for the whole ecosystem, so
  dependencies have no `<version>`. Starters pull in a feature plus its auto-configuration.
- **Auto-configuration.** Boot sees Flyway and a DataSource on the classpath and wires them up itself.
  Flyway runs before Hibernate starts, so `validate` checks against an already-migrated schema.
- **Flyway vs `ddl-auto=update`.** Migrations are versioned, reviewed, repeatable SQL that every
  environment runs the same way. `update` can't rename or drop columns safely, isn't reviewable, and
  can differ between environments.
- **`open-in-view: false`.** Otherwise the persistence context stays open while the JSON is rendered,
  so a lazy relation could fire SQL from the controller layer. Turning it off keeps DB work inside
  `@Transactional` service methods.
- **ProblemDetail (RFC 9457).** A standard JSON error body (`type`, `title`, `status`, `detail`,
  `instance`, content type `application/problem+json`). Extending `ResponseEntityExceptionHandler`
  makes Spring MVC's own exceptions use it too.
- **Testcontainers + `@ServiceConnection`.** A real Postgres runs in Docker for the test, and
  `@ServiceConnection` points the DataSource at it, with no URL in a test properties file. H2 is not
  used because it isn't Postgres: `FOR UPDATE`, `NUMERIC` and `CHECK` behaviour can differ.
- **12-factor config.** Config comes from the environment, and a missing `DB_URL` stops startup (verified).
- **`FailureAnalyzer`.** Boot's hook for turning a startup exception into a readable "APPLICATION FAILED TO
  START" report. Ours names the missing `DB_*` variables. It's registered in `META-INF/spring.factories`
  and runs only after startup has already failed, so it costs nothing on the happy path.

**Spring Boot 4 vs 3.x (seen in this stage)**
| | Boot 3.x | Boot 4.x |
|---|---|---|
| Web starter | `spring-boot-starter-web` | `spring-boot-starter-webmvc` (as generated by start.spring.io) |
| Flyway | `flyway-core` alone triggers auto-config | `spring-boot-starter-flyway` (auto-config split into per-technology modules) |
| Test deps | one `spring-boot-starter-test` | a `-test` starter per technology, e.g. `spring-boot-starter-webmvc-test` |
| `@AutoConfigureMockMvc` package | `o.s.boot.test.autoconfigure.web.servlet` | `o.s.boot.webmvc.test.autoconfigure` |
| Testcontainers | 1.x: `org.testcontainers:postgresql`, `PostgreSQLContainer<?>` | 2.x: `testcontainers-postgresql`, `org.testcontainers.postgresql.PostgreSQLContainer`, no generic |
| springdoc | 2.x | 3.x |
| Hibernate | 6.x | 7.x |

**Practice questions**
1. Why use Flyway migrations with `ddl-auto: validate` instead of `ddl-auto: update`? What happens at
   startup if an entity doesn't match the schema?
   <details><summary>Answer</summary>Migrations are reviewed SQL, versioned in git, run in the same order
   everywhere and recorded in `flyway_schema_history`, so every environment has the same schema.
   `update` guesses the changes from the entities: it can't rename or drop safely, isn't reviewable,
   and can drift between environments. With `validate`, a mismatch (a missing column, a wrong type)
   makes Hibernate fail at startup with a schema-validation error, so the app never runs against the
   wrong schema.</details>
2. Your tests run against PostgreSQL in Testcontainers instead of H2. What's the trade-off, and what
   could an H2-based test miss in a banking app?
   <details><summary>Answer</summary>The cost is Docker as a requirement and a few seconds of container
   start-up. The benefit is testing the database you run in production. H2 differs in locking (`FOR
   UPDATE` / `FOR NO KEY UPDATE` semantics, deadlock detection), constraint behaviour, `NUMERIC`
   handling and SQL dialect. A concurrency or constraint test that passes on H2 proves little about
   PostgreSQL.</details>
3. What is ProblemDetail, and how does a `@RestControllerAdvice` that extends
   `ResponseEntityExceptionHandler` change what a client sees for a malformed JSON body?
   <details><summary>Answer</summary>ProblemDetail is the RFC 9457 standard error body (`type`,
   `title`, `status`, `detail`, `instance`, plus extensions), served as `application/problem+json`.
   Extending `ResponseEntityExceptionHandler` makes Spring MVC's own exceptions use that format. A
   malformed body then returns a 400 ProblemDetail (here with "Malformed JSON request", or an `errors`
   entry naming the bad field) instead of the default error page or a body that leaks Jackson internals.</details>

---

## Stage 2: Account

**Built:** `POST /accounts` (201 + `Location`) and `GET /accounts/{id}`. Migrations `V1__accounts` and
`V2__ledger_entries` with CHECK constraints. The `Account` and `LedgerEntry` entities, and
`AccountService`, which writes the OPENING ledger entry in the same transaction as the account. 400 errors
list each invalid field. Tests at every layer: Mockito unit, `@WebMvcTest`, `@DataJpaTest` on real
Postgres, and one full-stack test.

**Key concepts**
- **Layers and DTOs.** The controller only does HTTP (status, headers, `@Valid`). The service owns the
  transaction and the rules, and returns a record DTO, so the entity never reaches JSON. That keeps the
  API contract separate from the table layout, and no lazy loading happens during serialisation.
- **`@Transactional` on the service.** Account row + ledger row commit together or not at all. Reads
  use `readOnly = true`, which tells Hibernate to skip dirty checking. It only works through the Spring
  proxy, so calling a `@Transactional` method from inside the same class bypasses it.
- **`@Version` (optimistic locking).** Hibernate updates with `WHERE id = ? AND version = ?`. If 0 rows
  change, someone else updated first and it throws `OptimisticLockException`. It's a wrapper `Long`
  because Spring Data's `save()` decides persist (insert) vs merge (select + update) by `version == null`
  when the id is assigned by us. With a primitive `long` it would always merge.
- **`BigDecimal`.** `equals` compares scale too (`100` ≠ `100.00`), `compareTo` doesn't. Hence
  `isEqualByComparingTo` in tests, and normalising to scale 2 on the way in.
- **Bean Validation.** `@NotBlank`, `@Size`, `@PositiveOrZero`, `@Digits(fraction = 2)` on the request
  record, triggered by `@Valid` → `MethodArgumentNotValidException` → 400 with an `errors` map.
- **Constraints in the DB as well as in Java.** Validation gives a nice 400. The CHECK constraint is the
  guarantee: it holds whatever path the data takes (a bug, a manual SQL fix, a future second service).
- **Test slices.** `@WebMvcTest` loads only MVC (fast, the service is a `@MockitoBean`). `@DataJpaTest`
  loads only JPA, in a rolled-back transaction. `@SpringBootTest` loads everything. More slices means
  faster, more focused failures.
- **Entity design.** A protected no-arg constructor (JPA needs one), no setters, the UUID generated in
  Java (known before insert), `@Enumerated(STRING)` (never ORDINAL: reordering the enum would corrupt
  data), and ids instead of `@ManyToOne` (no lazy-loading or N+1 surprises).

**Spring Boot 4 vs 3.x (seen in this stage)**
| | Boot 3.x | Boot 4.x |
|---|---|---|
| Jackson | 2.x, `com.fasterxml.jackson` | 3.x, `tools.jackson` (annotations stay in `com.fasterxml.jackson.annotation`) |
| `@WebMvcTest` | `o.s.boot.test.autoconfigure.web.servlet` | `o.s.boot.webmvc.test.autoconfigure` |
| `@DataJpaTest` | `o.s.boot.test.autoconfigure.orm.jpa` | `o.s.boot.data.jpa.test.autoconfigure` |
| `TestEntityManager` | `o.s.boot.test.autoconfigure.orm.jpa` | `o.s.boot.jpa.test.autoconfigure` |
| Mocking a bean | `@MockBean` (deprecated since 3.4) | `@MockitoBean` from Spring Framework (`o.s.test.context.bean.override.mockito`) |

**Practice questions**
1. Why does `Account.version` use `Long` and not `long`? What would change in the SQL that Spring Data's
   `save()` runs?
   <details><summary>Answer</summary>Our ids are assigned in Java, so Spring Data can't use "id is
   null" to detect a new entity. With a `@Version` field it uses "version is null" instead. A `Long`
   is null before the first insert, so `save()` calls `persist`: a single `INSERT`. A primitive `long`
   is never null (it's 0), so `save()` would call `merge`: a `SELECT` by id first, then the
   `INSERT`.</details>
2. The service already rejects negative balances. Why also have `CHECK (balance >= 0)` in the database?
   What does each layer give you that the other doesn't?
   <details><summary>Answer</summary>The service gives a clear business error (422 with a reason) and
   knows the context. The constraint is a guarantee no code path can bypass: a bug, a race, a manual SQL
   fix or a second service writing to the same table. If the Java check is ever wrong, the database
   refuses the write and the transaction rolls back, so the bad data never exists.</details>
3. In a test, `new BigDecimal("100").equals(new BigDecimal("100.00"))` is false. Why, and how do you
   compare money safely in Java and in AssertJ?
   <details><summary>Answer</summary>`BigDecimal.equals` compares value *and* scale: 100 (scale 0) and
   100.00 (scale 2) are different objects to it. `compareTo` compares only the value. In Java use
   `a.compareTo(b) == 0`, or normalise both to scale 2 first. In AssertJ use `isEqualByComparingTo`, or
   `isEqualTo` when you deliberately want to assert the scale as well (this project does that for
   stored amounts).</details>

---

## Stage 3: Transfer + ledger

### What we built
`POST /transfers` moves money between two accounts in the same currency and returns 201 with a
`Location`. `GET /transfers/{id}` reads it back. `GET /accounts/{id}/transactions` is the statement:
ledger entries, newest first, paginated. Migration `V3__transfers` adds the `transfers` table and the
foreign key from `ledger_entries.transfer_id`. Every transfer writes one transfer row, one negative
`TRANSFER_DEBIT` entry and one positive `TRANSFER_CREDIT` entry, and changes both balances, all in one
transaction. Broken business rules return 422 with a machine-readable `reason`.

### Thought process
**Where do balance changes live?**
- Problem: something has to change `balance`, and nothing else should be able to set it to any value.
- Options: (a) a setter, with the service doing the maths; (b) `debit(amount)` / `credit(amount)` on
  `Account`; (c) recompute the balance from the ledger every time.
- Chosen: (b). There is no setter, so the only ways to change a balance are the two methods, and each
  guards its own invariant (amount > 0, a debit never goes negative).
- Trade-off: the guards throw `IllegalStateException`/`IllegalArgumentException`, not the 422 exception,
  because `account` must not depend on the `transfer` package (that would be a package cycle). So the
  service checks the rules first and throws the proper `TransferRejectedException`. The guards are a
  second line that should never fire. Option (c) is the purest double-entry design, but every read
  becomes a `SUM` over a growing table. A stored balance plus a tested invariant is the usual compromise.

**Who decides the sign of a ledger amount?**
- Problem: a debit must be stored negative. If every caller writes `amount.negate()`, someone will
  forget once.
- Chosen: static factories `LedgerEntry.transferDebit(...)` and `transferCredit(...)`. They take the
  positive transfer amount and set the sign. The CHECK constraint `ledger_entries_sign_matches_type` is
  the backstop. The public constructor stays because a repository test uses it to write a wrong sign on
  purpose and prove the constraint works.

**Check everything, then change anything.**
- Problem: if validation and mutation are interleaved, a late rejection leaves half-changed objects
  in memory.
- Chosen: all checks run first (amount, same account, existence, currency, funds), then the four
  writes. The transaction would roll back the database anyway, but with this order a rejection never
  even produces dirty entities. It's also easier to read and to test.

**Saving accounts without `save()`.**
Accounts loaded inside a `@Transactional` method are *managed*. Hibernate keeps a snapshot of each one,
compares at flush time (dirty checking) and issues the `UPDATE` itself. Calling `save()` on a managed
entity does nothing useful, and its presence suggests it's needed.

**422 vs 400.**
- 400 means "this request is malformed": a missing field, a wrong type, 3 decimals. The DTO's Bean
  Validation catches it before the service runs.
- 422 means "well-formed, but the business rules say no": not enough money, different currencies.
  Only the service can know, because it needs the database.
- `amount > 0` is checked in three places. The DTO gives a clean 400 at the HTTP edge. The service
  can't assume every caller came through HTTP, so it checks again (defence in depth). The DB CHECK is
  the third layer.

**Why the request carries a currency.** The client states what it thinks it's moving. If that doesn't
match the accounts, we reject it rather than move 100 USD when the client meant 100 EUR.

**FK ordering.** `ledger_entries.transfer_id` references `transfers(id)`, so the transfer row must be
inserted first. Hibernate executes inserts in the order they were registered, so `transferRepository.save`
comes before `ledgerEntryRepository.saveAll`.

**Integration tests for the service, not Mockito.** The service tests run against real Postgres and
check outcomes (balances, rows, ledger sums), not which repository methods were called. A Mockito test
would mostly restate the implementation, and it couldn't prove rollback or constraint behaviour. A small
plain unit test (`AccountTest`) covers the `Account` guards and the ledger factories' signs.

### Code walkthrough: `TransferService.transfer`
```java
BigDecimal amount = Money.normalize(request.amount());
```
Sets scale 2 with `RoundingMode.UNNECESSARY`, so `30` becomes `30.00` and the stored transfer, the
ledger and the response all agree. Without it, `30` and `30.00` are different `BigDecimal`s (`equals`
checks scale).
```java
if (amount.signum() <= 0) throw new TransferRejectedException(Reason.AMOUNT_NOT_POSITIVE);
if (request.fromAccountId().equals(request.toAccountId())) throw ... SAME_ACCOUNT;
```
The cheap checks run first, before any query. Without the amount check, a negative amount would
*reverse* the transfer (debit the target). Without the same-account check, the debit and credit cancel
out, two pointless entries get written, and the DB CHECK turns it into a 500 instead of a 422.
```java
Account source = findAccount(request.fromAccountId());
Account target = findAccount(request.toAccountId());
```
Loads both accounts; an unknown one is a 404. (Stage 5 replaces this with locked reads in a fixed order.)
```java
if (!source.getCurrency().equals(request.currency()) || !target.getCurrency().equals(request.currency())) ... CURRENCY_MISMATCH
if (!source.hasFundsFor(amount)) ... INSUFFICIENT_FUNDS
```
Rules that need the loaded data. `hasFundsFor` uses `compareTo >= 0`, so emptying the account exactly
is allowed. Remove it and `Account.debit` throws `IllegalStateException`: a 500 instead of a 422.
```java
source.debit(amount);
target.credit(amount);
```
In-memory changes on managed entities. The `UPDATE`s happen at flush/commit.
```java
Transfer transfer = transferRepository.save(new Transfer(...));
ledgerEntryRepository.saveAll(List.of(LedgerEntry.transferDebit(...), LedgerEntry.transferCredit(...)));
```
The transfer goes first because of the FK. Two entries whose amounts sum to zero: that's what makes it
double-entry. Remove either one and "sum of entries = balance" breaks for one account.
```java
return TransferResponse.from(transfer);
```
A record DTO; the entity never leaves the service.

The whole method is `@Transactional`: if anything throws a `RuntimeException`, Spring rolls back,
balance changes included (they were either never flushed or are undone by the DB rollback). The test
`rollsBackEverythingWhenTheLedgerWriteFails` proves it by making every save method on the ledger
repository throw.

### What an interviewer would challenge
- **"What if the DB crashes between the debit and the credit?"** In the database there is no
  "between": both `UPDATE`s and the three `INSERT`s are one transaction. Postgres writes the commit
  record to the WAL atomically. After a crash it replays committed transactions and discards the rest.
  Either all five statements happened or none did.
- **"What if two transfers from the same account run at once?"** At this stage both read the same
  balance. `@Version` on `Account` turns the second commit into an optimistic-lock failure (a 500 at
  this stage), so no money is lost, but the client gets an error. Stage 5 fixes this with row locks.
- **"Why store the balance at all if you have the ledger?"** Reading a balance is O(1). The cost is two
  sources of truth that must agree, which is why the invariant is tested. A reconciliation job could
  also check it in production.
- **"Spring Data `save` with an assigned UUID?"** For an entity with an assigned id and no `@Version`,
  `save` calls `merge`, which costs an extra `SELECT` per entity (Transfer and LedgerEntry here).
  That's acceptable now. Implementing `Persistable<UUID>` with an `isNew` flag removes it if it ever matters.
- **"Offset pagination at scale?"** `OFFSET 100000` still walks 100000 rows, and new entries shift
  pages while a client is paging. Keyset pagination (`WHERE (created_at, id) < (?, ?)`) fixes both, and
  the index `(account_id, created_at DESC)` already supports it.

### Spring Boot 4 vs 3.x (seen in this stage)
| | Boot 3.x | Boot 4.x |
|---|---|---|
| 422 constant | `HttpStatus.UNPROCESSABLE_ENTITY` | `HttpStatus.UNPROCESSABLE_CONTENT` (the RFC 9110 name) |
| Spy on a bean | `@SpyBean` | `@MockitoSpyBean` |
| Page JSON | `PageImpl` serialised directly (warned about since Spring Data 3.3) | `PagedModel`: `content` + `page` metadata |

### Practice questions
1. Why is "insufficient funds" a 422 and "amount has 3 decimals" a 400?
   <details><summary>Answer</summary>400 is for a request that is malformed in itself: the DTO's
   validation rejects it without touching the database. 422 is for a request that is well-formed but
   breaks a business rule that depends on current state (the balance). The split tells the client
   whether to fix the request or accept that the operation isn't possible right now. The `reason` field
   lets a client branch on the cause without parsing text.</details>
2. The service never calls `accountRepository.save(source)`. How does the new balance reach the database?
   <details><summary>Answer</summary>Entities loaded in a transaction are managed by the persistence
   context. At flush (before commit, or before a query that needs it), Hibernate compares each managed
   entity with the snapshot taken at load time and issues an `UPDATE` for the changed ones. That's dirty
   checking. It only works inside the transaction: with `open-in-view: false`, the entity is detached as
   soon as the service method returns.</details>
3. How does the test prove the transfer is atomic, and why can't a Mockito unit test prove it?
   <details><summary>Answer</summary>`rollsBackEverythingWhenTheLedgerWriteFails` uses `@MockitoSpyBean`
   on the ledger repository: real behaviour, except that every save method throws. After the call it
   reads the balances and the transfer count from Postgres and expects them unchanged. The rollback is
   done by Spring's transaction proxy and the database. A Mockito test has neither, so it could only
   check that methods were called, not that nothing was committed.</details>
4. Why does the ledger store a debit as a negative number rather than a positive number plus a type?
   <details><summary>Answer</summary>So the balance is simply `SUM(amount)` with no `CASE` on the
   type, and the debit and credit of one transfer sum to zero, which is easy to check. The type still
   carries the meaning, and a CHECK constraint ties the two together (debit < 0, credit > 0) so they
   can't disagree.</details>
5. Why do all the checks run before any change, if the transaction rolls back anyway?
   <details><summary>Answer</summary>Correctness doesn't depend on it, but it keeps the method easy to
   reason about: a rejection leaves no dirty state in memory, nothing is flushed, and the reader sees
   "validate, then act". It also avoids relying on rollback for normal control flow, which matters if
   someone later catches the exception inside the transaction.</details>

---

## Stage 4: Idempotency

### What we built
`POST /transfers` now requires an `Idempotency-Key` header (1-100 characters). Each transfer row stores
the key (unique in the database) and a SHA-256 hash of the request. A retry with the same key and the
same request returns the original transfer (201, same body) and moves no money. The same key with a
different request is a 422 with `reason: IDEMPOTENCY_KEY_REUSED`. If two requests with the same key run
at the same instant, the database lets exactly one through. The others get a 409, and a retry returns
the winner's result. Migration `V4__transfer_idempotency`.

### Thought process
**Why idempotency at all?**
A client sends a transfer, the network drops the response, and the client retries. Without
idempotency that's two transfers. The client can't know whether the first one happened, so the server
must make "send it again" safe. The client generates a key (usually a UUID) per *intended* operation
and reuses it on every retry.

**Where to store the key.**
- Options: (a) a separate `idempotency_keys` table storing the key, hash and serialised response;
  (b) columns on `transfers`.
- Chosen: (b). A transfer is the only idempotent operation, and the stored transfer *is* the original
  result, so there's nothing extra to serialise. One table, one insert, one unique constraint.
- Trade-off: only completed transfers are remembered. A rejected request (422) doesn't use up its key,
  so a retry after the client fixes the problem (for example after a deposit) is evaluated again. A
  separate table could store failures too (Stripe stores the response whatever it was). We accept
  this because a rejection changes nothing, so re-evaluating it can't double-spend. It's tested in
  `aRejectedRequestDoesNotUseUpItsKey`.

**Why a request hash?**
- Problem: the same key with a *different* body is a client bug (or an attack). Silently returning the
  old transfer would hide it.
- Options: compare the stored transfer's columns with the request, or store a hash.
- Chosen: SHA-256 over `from|to|amount|currency`, with the amount normalised first so `30` and
  `30.00` are the same request. The hash is fixed-size and would also cover fields that are not stored
  on the transfer (a future free-text reference, for example). Comparing columns would work as well
  for today's request; the hash is what was planned and it generalises.

**The race (two requests, same key, same instant).**
- Problem: both run `findByIdempotencyKey`, both see nothing, both go on to transfer.
- Options: (a) lock something before the lookup (a key table row, an advisory lock); (b) let the
  `UNIQUE` constraint decide and handle the loser.
- Chosen: (b). In PostgreSQL the second `INSERT` of the same key *waits* for the first transaction,
  then fails with a unique violation once the first commits. The loser's whole transaction rolls back,
  balance changes included, so money moves once. The loser gets 409 Conflict ("retry to get its
  result"), and the retry hits the lookup and returns the original transfer.
- Trade-off: the loser sees a 409 instead of the original result. A client that already retries on
  timeouts handles that easily. Stage 5 improves the same-payload case: the lookup moves after the
  account locks, so a concurrent duplicate waits and then replays the original instead of a 409.

**Flush early.** `saveAndFlush` sends the `INSERT` immediately instead of at commit, so the unique
violation is thrown inside the method, where it can be recognised and turned into a 409. At commit it
would surface from Spring's transaction interceptor, outside our code.

**Recognising *our* constraint.** Only a violation of `transfers_idempotency_key_unique` becomes a 409.
Any other integrity error is rethrown unchanged, because hiding a CHECK violation as "retry" would
mask a bug. The check reads Hibernate's `ConstraintViolationException.getConstraintName()`, not the
error text, which is localised.

**Method validation side effect (Spring MVC 6.1+).** Adding `@NotBlank @Size` on the header makes
Spring validate the whole controller method at once. Body errors then arrive as
`HandlerMethodValidationException` instead of `MethodArgumentNotValidException`, and the `errors` map
disappeared. A failing test caught this. The fix is one more override in `GlobalExceptionHandler`
that produces the same shape, with the header name (`Idempotency-Key`) or parameter name as the key.

### Code walkthrough: the idempotency path in `TransferService.transfer`
```java
BigDecimal amount = Money.normalize(request.amount());
String requestHash = requestHash(request, amount);
```
Normalise before hashing, otherwise `30` and `30.00` give different hashes and a harmless retry
becomes a 422.
```java
Optional<Transfer> earlier = transferRepository.findByIdempotencyKey(idempotencyKey);
if (earlier.isPresent()) return replay(earlier.get(), requestHash);
```
The fast path for retries, before any validation. A replay doesn't re-check funds: the money already
moved, so re-checking would wrongly reject a retry after the balance dropped. Remove this and every
retry is a second transfer (stopped only by the unique constraint, as a 409).
```java
private static TransferResponse replay(Transfer earlier, String requestHash) {
    if (!earlier.getRequestHash().equals(requestHash)) throw ... IDEMPOTENCY_KEY_REUSED;
    return TransferResponse.from(earlier);
}
```
Same key, different request → 422. Remove the comparison and a client bug that reuses keys gets a
wrong "success" for a transfer it never asked for.
```java
Transfer transfer = insertOnce(new Transfer(..., idempotencyKey, requestHash));
```
`insertOnce` = `saveAndFlush` + catch `DataIntegrityViolationException` → if it's our constraint,
`ConflictException` (409), else rethrow. Remove the `UNIQUE` constraint and the race above becomes a
double transfer; nothing in Java alone can prevent it without a lock.

### What an interviewer would challenge
- **"What if the DB crashes after the transfer commits but before the response reaches the client?"**
  That's exactly the case idempotency exists for: the client retries with the same key and gets the
  committed transfer back.
- **"Why not check the key in Redis/memory?"** It would be a second system that must agree with the
  database, and a crash between "money moved" and "key recorded" breaks it. Here the key is written in
  the same transaction as the money movement, so they can't disagree.
- **"Keys never expire. Isn't that a problem?"** The table grows with transfers anyway, so the extra
  column is small. Real APIs often expire keys (Stripe: 24 hours) so keys can be reused. That would
  be a scheduled cleanup or a `created_at` condition in the lookup. Not needed for this scope.
- **"Keys are global, not per client."** Correct. Without authentication there's no client identity.
  With auth, the unique constraint becomes `(client_id, idempotency_key)`, otherwise one client could
  guess another's key and receive their transfer details. It's listed in SECURITY.md.
- **"Why 422 for a key reused with a different body, and 409 for the race?"** 422: the request is
  well-formed but semantically invalid, and retrying won't help. 409: a temporary conflict with another
  request in flight, and retrying *will* help.
- **"Is the hash a security feature?"** No. It's a fingerprint for equality, not a secret. SHA-256 is
  used because it's standard and collision-resistant, not because anything is hidden.

### Spring Boot 4 vs 3.x (seen in this stage)
| | Boot 3.0-3.1 (Spring 6.0) | Boot 3.2+ and 4.x (Spring 6.1+ / 7) |
|---|---|---|
| Constraints on `@RequestHeader`/`@RequestParam` | needed `@Validated` on the class (AOP), threw `ConstraintViolationException` → 500 by default | built-in method validation, throws `HandlerMethodValidationException` → 400 via `ResponseEntityExceptionHandler` |
| `@Valid` body when the method also has parameter constraints | `MethodArgumentNotValidException` | `HandlerMethodValidationException` (with `ParameterErrors` for the body) |

### Practice questions
1. A client sends a transfer, times out and retries. Walk through what happens on the server.
   <details><summary>Answer</summary>Case 1: the first request committed. The retry's lookup finds
   the key, the hash matches, and it returns the stored transfer with 201. No money moves. Case 2: the
   first is still running. The retry passes the lookup, does the transfer in memory, and its insert
   waits on the unique index until the first commits. Then it fails, rolls back, and returns 409. The
   client retries again and gets case 1. Case 3: the first failed or was rejected. Nothing was stored,
   so the retry is evaluated as a new request. (Since Stage 5, case 2 for an identical request no longer
   gets a 409: it waits on the account locks and then replays the committed transfer.)</details>
2. Why can't `findByIdempotencyKey` alone guarantee at-most-once?
   <details><summary>Answer</summary>Check-then-act race: two transactions can both run the lookup
   before either commits, and both see nothing. Under READ COMMITTED (the PostgreSQL default) neither
   sees the other's uncommitted insert. Only something atomic in the database closes the gap: the
   unique constraint (or a lock). The lookup is the fast path for the common case, and the constraint is
   the guarantee.</details>
3. Why is the key stored in the same table and transaction as the transfer?
   <details><summary>Answer</summary>So "money moved" and "key used" are one atomic fact. If the key
   lived in another store, or another transaction, a crash between the two writes would leave money
   moved with no key (the retry double-spends) or a key with no money moved (the retry returns a
   transfer that doesn't exist).</details>
4. Why does a replay skip the balance check?
   <details><summary>Answer</summary>The replay doesn't move money; it reports what already happened.
   If the balance dropped since then, re-checking would answer "insufficient funds" to a retry of a
   transfer that actually succeeded, and the client would think it failed.</details>
5. What would you change to make keys per client?
   <details><summary>Answer</summary>Add authentication, store the client id on the transfer, and make
   the unique constraint `(client_id, idempotency_key)`. The lookup becomes
   `findByClientIdAndIdempotencyKey`. Otherwise one client can reuse another's key and, if it knows
   the payload, read their transfer.</details>

---

## Stage 5: Concurrency

### What we built
`TransferService.transfer` now reads both accounts with `SELECT ... FOR UPDATE`
(`@Lock(PESSIMISTIC_WRITE)` on `AccountRepository.findByIdForUpdate`), always locking the smaller UUID
first. The idempotency lookup moved to *after* the locks, so a concurrent duplicate of the same request
waits and then replays the original instead of getting a 409. Any database lock or version conflict that
still escapes (`ConcurrencyFailureException`) becomes a 409 instead of a 500. A concurrency test runs
200 random transfers on 16 threads, released together by a `CountDownLatch`, against real Postgres.

### Thought process
**The problem: lost updates.**
Two transfers out of account A (balance 100) run at once. Both read 100, both check "100 ≥ 60", and both
write `100 - 60 = 40`. One debit is lost, or with stricter checks the balance goes negative. It's the
classic read-modify-write race: the check and the write are not atomic with respect to each other.

**Options considered**
| | Pessimistic (`FOR UPDATE`) | Optimistic (`@Version` + retry) | Atomic SQL (`UPDATE ... SET balance = balance - ? WHERE balance >= ?`) | `SERIALIZABLE` isolation |
|---|---|---|---|---|
| How | Lock the rows when reading; others wait | Read freely; the `UPDATE ... WHERE version = ?` fails if someone changed the row; retry | Let one statement do check + write | Postgres aborts transactions that would be non-serialisable |
| On contention | Requests queue, all succeed | Losers fail and must retry (retry storms on hot accounts) | Fine for one row | Losers fail and must retry |
| Deadlocks | Possible, prevented by lock ordering | None | Possible with two rows unless ordered | Not deadlocks, but serialisation failures |
| Code | One annotation + ordering | Retry loop outside the transaction, backoff, max attempts | Custom queries; the entity no longer owns the rule | A config change, plus a retry loop |

- Chosen: **pessimistic locking**. For money movement it's deterministic: every request gets an answer
  without a retry loop, the balance check runs on a value nobody else can change until commit, and
  `SELECT ... FOR UPDATE` is what most banking systems people will recognise.
- Rejected: **optimistic + retry**. It's better when conflicts are rare and reads dominate, and it
  holds no DB locks while the application thinks. But a hot account (a merchant receiving many payments)
  turns into repeated failures and retries, and the retry must sit *outside* the transaction (a new
  transaction per attempt). That's more moving parts to get wrong. `@Version` stays on `Account` as a
  safety net: an update path that forgets the lock fails loudly instead of losing money.
- Trade-off accepted: throughput on one account is serialised (one transfer at a time per account),
  and a slow transaction holding a lock makes others wait. Transactions here are a few milliseconds
  and touch two rows, so that's fine at this scale.

**Lock ordering (deadlock prevention).**
- Problem: T1 is A→B and locks A, T2 is B→A and locks B. T1 waits for B, T2 waits for A: deadlock.
  Postgres detects it after `deadlock_timeout` (1 s by default) and kills one transaction.
- Chosen: always lock the smaller UUID first, whatever the direction. Both transactions then ask for
  the same first row: one gets it, the other waits, and there's no cycle.
- The order is Java's `UUID.compareTo`. It isn't the same as Postgres's uuid ordering, but it doesn't
  need to be: it only has to be the same rule in every code path that locks more than one account.

**Lookup after the locks.**
In Stage 4 the key lookup ran first, so two identical concurrent requests both passed it and the loser
got a 409. Now the lookup runs after both rows are locked. An identical duplicate locks the same rows,
so it waits until the first transfer commits. Its lookup (a new statement under READ COMMITTED) then
sees the committed transfer and replays it. The unique constraint is still the guarantee for the
remaining case: same key, *different* accounts.

**Proving the test has teeth.** A concurrency test that passes proves little unless it fails without
the fix. This was checked by hand with two temporary edits that were then reverted:
1. `findByIdForUpdate` → `findById` (no lock): the test fails with `ObjectOptimisticLockingFailureException`
   ("expected row count 1 but was 0"). That's `@Version` catching the lost updates.
2. Locks without ordering (always source first): the test fails, with Postgres reporting `deadlock detected`
   and Spring throwing `CannotAcquireLockException`.

A lesson from this check: restoring the original file with PowerShell's `Copy-Item` kept its *old*
timestamp, so Maven's incremental compiler thought the mutated class was still up to date, and the next
full build deadlocked. `./mvnw clean verify` fixed it. When a result makes no sense, rule out a stale
build before debugging the code.

### Code walkthrough: locking in `TransferService`
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT a FROM Account a WHERE a.id = :id")
Optional<Account> findByIdForUpdate(UUID id);
```
On PostgreSQL, Hibernate 7 turns `PESSIMISTIC_WRITE` into `select ... for no key update` (seen in the
SQL log). That's a slightly weaker row lock than `FOR UPDATE`: it still blocks every other writer and
locker of the row, but not the `FOR KEY SHARE` locks that foreign-key checks take when another table
inserts a row referencing this account. Since we never change an account's id, that's exactly enough,
and it means fewer waits. The lock lasts until
the transaction commits or rolls back, which is why this must run inside `@Transactional`. Without a
transaction, the lock would be released at once (autocommit).
```java
if (amount.signum() <= 0) ... ; if (from.equals(to)) ...
```
The stateless checks stay *before* the locks: no point locking rows for a request that's invalid anyway.
```java
LockedAccounts accounts = lockBothAccounts(request.fromAccountId(), request.toAccountId());
```
```java
private LockedAccounts lockBothAccounts(UUID sourceId, UUID targetId) {
    if (sourceId.compareTo(targetId) < 0) {
        Account source = lockAccount(sourceId);
        return new LockedAccounts(source, lockAccount(targetId));
    }
    Account target = lockAccount(targetId);
    return new LockedAccounts(lockAccount(sourceId), target);
}
```
The two branches differ only in which row is locked first. The small record keeps "which one is the
source" separate from "which one was locked first". Remove the ordering and opposite-direction transfers
deadlock (verified above). Remove the locks and you're back to lost updates (verified above).
```java
Optional<Transfer> earlier = transferRepository.findByIdempotencyKey(idempotencyKey);
```
Now after the locks (see above). Then come the currency and funds checks, on balances that nobody else
can change until we commit: that's the whole point. The balance check is only correct *because* it runs
on a locked row.

### What an interviewer would challenge
- **"Why not optimistic locking?"** See the table. Short version: under contention it fails instead
  of waiting, so it needs a retry loop outside the transaction, and hot accounts cause retry storms.
  Pessimistic locking queues requests and answers each one deterministically. Optimistic locking is the
  better fit for low-contention, read-heavy data (user profiles, settings).
- **"How does this scale?"** Per account, transfers are serialised: throughput is bounded by
  transaction time (a few ms → hundreds per second per account). Different account pairs run in
  parallel. Beyond that: keep transactions short (no HTTP calls inside them), use a bigger connection
  pool and more instances (locks live in Postgres, so this works across instances), and for very hot
  accounts consider sub-accounts or queueing transfers per account.
- **"What if a transaction holds the lock and hangs?"** Others wait. Mitigations not implemented
  here: `lock_timeout` or the JPA hint `jakarta.persistence.lock.timeout`, so waiters fail fast with a
  409 (already mapped via `ConcurrencyFailureException`), plus `idle_in_transaction_session_timeout`
  on the database.
- **"Does it work with several app instances?"** Yes. The locks are rows in PostgreSQL, not Java
  monitors, so every instance shares them. A `synchronized` block would only protect one JVM.
- **"Isolation level?"** The default READ COMMITTED. `FOR UPDATE` returns the latest committed version
  of the row after waiting, so the check runs on fresh data. REPEATABLE READ would instead fail the
  waiter with a serialisation error.
- **"Why keep `@Version` if you lock?"** Defence in depth. Any future code that updates an account
  without locking gets an optimistic-lock failure (409) instead of silently overwriting a balance.
- **"How do you know there are no deadlocks?"** The test runs opposite-direction transfers on 4
  accounts with 16 threads and expects zero unexpected exceptions, and it fails when the ordering is
  removed. That shows the ordering works for this code path. It doesn't prove the absence of deadlocks
  in future code, which is why the rule is in CLAUDE.md.

### Spring Boot 4 vs 3.x (seen in this stage)
Nothing new: `@Lock`, `LockModeType` (`jakarta.persistence`) and `ConcurrencyFailureException` are
the same as in Boot 3.x. `ExecutorService` in try-with-resources (Java 19+) is a Java feature, not a
Boot one: `close()` waits for the submitted tasks to finish.

### Practice questions
1. Explain a lost update and how `SELECT ... FOR UPDATE` prevents it.
   <details><summary>Answer</summary>Two transactions read the same balance, compute new values
   independently and write them. The second write overwrites the first, so one debit disappears.
   `FOR UPDATE` locks the row at read time: the second transaction's read blocks until the first commits,
   then returns the updated balance, so its check and its write are based on current data.</details>
2. How can two transfers deadlock, and how does the code prevent it?
   <details><summary>Answer</summary>A→B locks A then waits for B, while B→A locks B then waits for A.
   Each waits for the other forever, until Postgres detects the cycle and aborts one. The code always
   locks the smaller UUID first, whatever the direction, so both transactions request the same row first.
   One waits behind the other, and no cycle can form.</details>
3. When would you choose optimistic locking instead?
   <details><summary>Answer</summary>When conflicts are rare, reads dominate, and a failed attempt is
   cheap to retry or can be shown to the user ("someone else edited this, reload"). For example,
   editing a profile, or long user "think time" between read and write, where holding a DB lock would be
   unacceptable. For hot money movement, pessimistic locking avoids retry storms.</details>
4. Why does the concurrency test use a `CountDownLatch`, and what does it assert?
   <details><summary>Answer</summary>Without a start gate, threads start one by one and may barely
   overlap, so the race rarely happens. All tasks wait on `startGate.await()`, and `countDown()`
   releases them together. It asserts: no exception except INSUFFICIENT_FUNDS, some transfers succeed
   and some are rejected, no balance below zero, the total across accounts unchanged (400.00), and for
   every account, sum of ledger entries = balance.</details>
5. The idempotency lookup moved after the locks. What does that change for two identical concurrent requests?
   <details><summary>Answer</summary>Before, both passed the lookup, both did the transfer, and the
   unique constraint failed the second (409). Now the second blocks on the account lock until the first
   commits. Its lookup then sees the committed transfer and replays it, so both clients get 201 with the
   same transfer. The constraint still covers same-key requests on different accounts.</details>

---

## Stage 6: Docker + CI + docs

### What we built
A multi-stage `Dockerfile` (JDK build stage → JRE runtime stage, non-root uid 10001) and a
`.dockerignore` that keeps `.env` out of the image. `docker-compose.yml` now runs the app and PostgreSQL,
both with health checks, and the app waits for a healthy database. A GitHub Actions workflow runs
`./mvnw -B verify` and builds the image. Dependabot covers Maven, Actions and Docker. Mockito is loaded
as a Java agent, so the "self-attaching" warning is gone. Actuator exposes `/actuator/health` only. Final
README and SECURITY.md.

Verified by running it: `docker compose up -d --build --wait` brought both containers to `healthy`, and
curl over HTTP exercised open account → transfer → retry with the same key (201, no second debit) →
same key with a different body (422) → insufficient funds (422) → missing header (400) → balances and
statement → `/actuator/env` (404). The jar was also started with `SPRINGDOC_APIDOCS_ENABLED=false` and
`SPRINGDOC_SWAGGERUI_ENABLED=false`: both doc URLs returned 404. **Not verified:** the GitHub Actions
workflow itself, because the repository has no GitHub remote yet. The first push will be its first run.

### Thought process
**Multi-stage build.**
- Problem: building needs a JDK and Maven (hundreds of MB, plus a compiler an attacker could use).
  Running needs only a JRE and the jar.
- Chosen: stage 1 `eclipse-temurin:21-jdk` builds. Stage 2 `eclipse-temurin:21-jre` gets only the jar.
  `pom.xml` and `.mvn` are copied and `dependency:go-offline` runs *before* `src` is copied, so the
  dependency layer is cached and code-only changes rebuild quickly.
- Not chosen: Alpine/distroless images (smaller, but Alpine uses musl and distroless has no shell or
  curl for the health check), and Spring Boot layered jars or `bootBuildImage` (better layer caching, but
  more to explain). The image is ~590 MB; that's the trade-off accepted for a familiar Ubuntu-based image.

**Tests are not run inside `docker build`.** They need Docker themselves (Testcontainers), and Docker in
Docker inside a build is fragile. CI runs `./mvnw verify` first, then builds the image, so no image is
built from untested code in CI.

**Non-root.** `useradd --system --uid 10001` with `USER app`. The jar is owned by root, so the app
can't modify its own code. A fixed numeric uid is what Kubernetes `runAsNonRoot` and security scanners
check for.

**`-XX:MaxRAMPercentage=75`.** The JVM is container-aware, but by default it uses only 25% of the
container's memory for the heap. In a 1 GB task that's 256 MB of heap and 750 MB unused.

**Health check endpoint: Actuator.**
- Problem: compose (and later the AWS load balancer) needs an HTTP URL that says "the app is up and can
  reach its database".
- Options: an existing endpoint like `/v3/api-docs` (but it's turned off in AWS), a TCP check (says
  nothing about the DB), or Actuator.
- Chosen: `spring-boot-starter-actuator` with exposure limited to `health`. The response is just
  `{"status":"UP"}`, without component details, and it includes a database check. The trade-off is one
  new dependency. It's an official Spring Boot module, so the parent pom manages its version.

**Compose layout.** One file, two services. `depends_on: condition: service_healthy` delays the app
until `pg_isready` passes, otherwise Flyway would fail on startup. Inside the compose network the app
reaches the DB at `postgres:5432` (service name), while the host sees it at `127.0.0.1:${DB_PORT}`.
For dev mode, `docker compose up -d postgres` starts only the database.

**Mockito agent.** Since JDK 21, a library attaching an agent to the running JVM triggers a warning,
and a future JDK will block it (JEP 451). `maven-dependency-plugin:properties` exposes the
Mockito jar's path as `${org.mockito:mockito-core:jar}`, and Surefire passes `-javaagent:` with it.

**CI.** `permissions: contents: read` (the default token can't push or comment), no secrets, Java 21
with the Maven cache, `./mvnw -B verify`, then `docker build`. Actions are pinned to major versions
(checkout v7, setup-java v6, checked against their latest releases on 2026-11-24). Pinning to commit
SHAs would be stricter. Dependabot keeps them updated either way.

**A bug found while setting up CI:** `mvnw` was committed with mode `100644`, not executable. It worked
on Windows and in Docker Desktop (which marks every file executable), but `./mvnw` would fail on the
Linux runner with "permission denied". Fixed with `git update-index --chmod=+x mvnw`.

### Code walkthrough: the Dockerfile
```dockerfile
FROM eclipse-temurin:21-jdk AS build
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -q dependency:go-offline
```
Dependencies cached in their own layer. Copy `src` first and every code change re-downloads the internet.
```dockerfile
COPY src src
RUN ./mvnw -B -q package -DskipTests
```
Builds the jar. `-DskipTests` because the tests ran in CI (see above).
```dockerfile
FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app
COPY --from=build /workspace/target/contra-api-*.jar app.jar
USER app
```
A fresh image: nothing from stage 1 except the jar. Remove `USER app` and the process runs as root.
```dockerfile
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
```
Exec form (JSON array), so `java` is PID 1 and receives `SIGTERM` directly on `docker stop`. Spring
then shuts down gracefully. With the shell form, `sh` would be PID 1 and might not forward the signal.

### What an interviewer would challenge
- **"Why not run the tests in the Dockerfile?"** See above. The image is built from the same commit that
  CI tested, and Testcontainers needs a Docker daemon, which a build step doesn't have by default.
- **"How do secrets get into the container?"** As environment variables at runtime (compose reads
  `.env`; in AWS, ECS injects them from Secrets Manager). Never as `ENV` in the Dockerfile or a copied
  file: image layers are readable by anyone who can pull the image. `.dockerignore` excludes `.env` in
  case of a careless `COPY . .`.
- **"Liveness vs readiness?"** Our single health check includes the DB. As a *readiness* check that's
  right (don't send traffic if the DB is down). As a *liveness* check it would restart healthy containers
  during a DB outage. Boot provides `/actuator/health/liveness` and `/actuator/health/readiness` groups for
  that split (already listed under `groups` in the health response). The AWS plan uses the main endpoint
  for the load balancer only.
- **"The image is 590 MB."** True. The options are JRE Alpine (~200 MB), distroless, or jlink a custom
  runtime. Each saves pull time and attack surface but costs debuggability. This is listed as a possible
  improvement, not done.
- **"Is the CI workflow tested?"** Not yet. Honest answer: it's written to the documented action inputs,
  and the same commands run locally, but the first real run happens on the first push.

### Spring Boot 4 vs 3.x (seen in this stage)
| | Boot 3.x | Boot 4.x |
|---|---|---|
| Actuator | `spring-boot-starter-actuator`, `management.endpoints.web.exposure.include` | same starter and properties, as far as this project uses them |
| Mockito agent | same Surefire `-javaagent` approach | same; the self-attach warning comes from the JDK (21+), not from Boot |

### Practice questions
1. Why a multi-stage Dockerfile? What exactly ends up in the final image?
   <details><summary>Answer</summary>The build needs a JDK, Maven and the source. Running needs only a
   JRE and the jar. With two stages, the final image is the JRE base plus one `COPY --from=build` of the
   jar: no compiler, no Maven, no source, no local Maven repository. It's smaller, has a smaller attack
   surface, and secrets used only at build time can't leak through it.</details>
2. Why run the container as a non-root user if it's isolated anyway?
   <details><summary>Answer</summary>Container isolation isn't perfect: kernel or runtime bugs, mounted
   volumes and misconfigured capabilities have all allowed escapes, and root in the container makes all of
   them worse. As uid 10001 an exploited app can't install packages, change its own jar or bind
   privileged ports. Many platforms (Kubernetes policies, scanners) also require it.</details>
3. What does `depends_on: condition: service_healthy` solve, and what doesn't it solve?
   <details><summary>Answer</summary>It delays starting the app until Postgres passes `pg_isready`, so
   Flyway doesn't fail on a database that's still starting. It doesn't help if the database goes away
   later, and it only works within one compose project. In ECS/Kubernetes the app must tolerate the DB
   being unavailable (retries, a readiness check) instead.</details>
4. How does CI make sure a Dependabot PR doesn't break the app?
   <details><summary>Answer</summary>The workflow runs on every pull request: `./mvnw verify` runs all
   67 tests (including Testcontainers and concurrency tests on real PostgreSQL) and then builds the image.
   A PR that breaks either is red and shouldn't be merged. Branch protection requiring the check would
   enforce that. It isn't configured, because there's no remote yet.</details>
5. Why is the `ENTRYPOINT` in exec (JSON) form?
   <details><summary>Answer</summary>So `java` is PID 1 and receives `SIGTERM` on `docker stop` or an ECS
   task stop. Spring Boot then shuts down gracefully (finishes requests, closes the pool). In shell form,
   `/bin/sh -c` is PID 1, may not forward signals, and the container is killed after the timeout.</details>

---

## Stage 7: AWS (plan only, not deployed)

### What we built
A deployment plan in `docs/aws.md` and the infrastructure as CloudFormation in `infra/`: a budget
with alerts, the application stack (VPC, ALB, ECS Fargate, RDS PostgreSQL 16, ECR, logs, IAM), a GitHub
OIDC deploy role, and an inactive deploy workflow. The templates pass `cfn-lint`, but **nothing was
created in AWS**: there's no account behind this yet. The cost estimate is ≈ $61/month if left running,
≈ $0.08/hour, so a demo is well under $1 when torn down the same day.

### Thought process
**App Runner was the plan, and it's gone.** The original plan said "ECS Fargate or App Runner". The
service-status check found that App Runner stopped accepting new customers on 30 April 2026, and AWS now
recommends ECS Express Mode. Checking current status before writing anything is the point: a plan
built on memory would have targeted a closed service.

**ECS Fargate + ALB in plain CloudFormation, not Express Mode.**
- Express Mode: fastest path, HTTPS URL included, shared ALB. But it builds the network and load
  balancer for you, through a broad infrastructure role.
- Plain resources: more YAML, but every security group rule and IAM statement is visible, which is
  exactly what an interviewer at a bank will ask about. Express Mode is documented as the alternative.

**No NAT gateway.** Tasks need outbound access (ECR, Secrets Manager). A NAT gateway would cost more than
everything else combined. Instead tasks get a public IP in a public subnet, with a security group that
only accepts the ALB. The trade-off: production would use private subnets with a NAT gateway or
VPC endpoints, so the task has no public address at all.

**Security groups as a chain.** Caller (your /32) → ALB :80 → app :8080 → RDS :5432. Each group
references the previous one, not a CIDR, so only the right component can talk to the next one.

**Secrets without secrets.** `ManageMasterUserPassword: true` makes RDS generate the password and store
it in Secrets Manager. The task definition references `<secret-arn>:password::`, and ECS fetches it at
start-up. The app still reads `DB_PASSWORD`, so the code is identical to local.

**OIDC instead of access keys.** GitHub issues a signed token for each job. AWS verifies it against the
registered provider and the role's trust conditions (`aud`, and `sub` = this repo + environment `aws`),
and returns 1-hour credentials. There's nothing to leak or rotate in GitHub.

**Teardown designed in.** `DeletionPolicy: Delete` on RDS (no orphan snapshot), `EmptyOnDelete` on ECR,
log retention 7 days, no Elastic IPs. One `delete-stack` removes everything that costs money, and the
doc lists the checks to prove it.

### Walkthrough: the IAM pieces
```yaml
- Sid: ReadOnlyTheDatabaseSecret
  Action: secretsmanager:GetSecretValue
  Resource: !GetAtt Database.MasterUserSecret.SecretArn
```
The execution role can read exactly one secret. With `Resource: "*"` (what many tutorials show), a
compromised task definition could read every secret in the account.
```yaml
"token.actions.githubusercontent.com:sub": !Sub "repo:${GitHubRepository}:environment:aws"
```
Without the `sub` condition, *any* GitHub repository could assume the role: the provider alone only
proves "this token came from GitHub", not "from my repo".
```yaml
Action: iam:PassRole
Resource: arn:...:role/${AppStackName}-TaskExecutionRole-*
Condition: { StringEquals: { "iam:PassedToService": ecs-tasks.amazonaws.com } }
```
The deploy role can only hand the execution role to ECS. Without this limit it could register a task
definition with an admin role and run code as admin: privilege escalation through PassRole.

### What an interviewer would challenge
- **"Why not Kubernetes?"** One stateless service and one database: EKS adds a control plane fee,
  cluster upgrades and a lot of YAML for no benefit at this size.
- **"How would you make this highly available?"** `DesiredCount: 2` across both AZs (the ALB already
  spans them), Multi-AZ RDS, and a NAT gateway per AZ or VPC endpoints. Roughly 2-3x the cost.
- **"How does the app get the DB password? Can a developer see it?"** ECS injects it from Secrets
  Manager at start-up. It's in no template, stack or task definition. Reading it requires
  `GetSecretValue` on that ARN, which only the execution role (and admins) have. Access is logged in CloudTrail.
- **"What happens when the password rotates?"** Honest answer: running tasks keep the old value, and new
  connections fail after rotation until the service restarts. It's a known gap with two documented fixes.
- **"How do you deploy schema changes?"** Flyway runs at app start-up. With one task that's fine. With
  several tasks starting at once, Flyway's lock table serialises them, but the long-term fix is to run
  migrations as a separate one-off task before the rollout, and to keep each migration backward-compatible
  (expand/contract) so old and new versions can run side by side.
- **"What does it cost, and how do you stop surprises?"** ≈ $2/day running. The budget is created first,
  with alerts at 25% and 80% of actual spend and 100% of forecast. Alerts only send email: they don't stop
  anything, so teardown is a written checklist.

### Practice questions
1. Why OIDC instead of storing AWS access keys as GitHub secrets?
   <details><summary>Answer</summary>Access keys are long-lived: if they leak (logs, a malicious action,
   a compromised maintainer account), they work until someone notices and rotates them. With OIDC, GitHub
   issues a signed token per job, AWS checks the issuer, audience and subject (repo + environment), and
   returns credentials that expire in an hour. There's no stored secret to leak, and the trust policy
   decides exactly which workflows can deploy.</details>
2. What's the difference between the task execution role and the task role?
   <details><summary>Answer</summary>The execution role is used by the ECS agent *before and around* the
   container: pulling the image, writing logs, fetching secrets to inject. The task role is what the
   *application code* gets when it calls AWS APIs (for example S3). This app calls no AWS API, so it has
   no task role. Least privilege means zero permissions, not "some read access just in case".</details>
3. Why is `iam:PassRole` dangerous, and how is it limited here?
   <details><summary>Answer</summary>PassRole lets you attach a role to a service (an ECS task, a Lambda).
   If unrestricted, someone with deploy rights could attach an admin role to a task they control and run
   code with admin permissions. Here it's limited to the one execution role and only when passed to
   `ecs-tasks.amazonaws.com`.</details>
4. Where does the money go in this setup, and how would you cut it?
   <details><summary>Answer</summary>Roughly: Fargate $18, ALB $17, public IPv4 $11, RDS $14 per month.
   Cuts: ARM/Graviton Fargate (~20% cheaper compute), a smaller task, scaling to zero when not demoing
   (`DesiredCount: 0`), and deleting the stack between demos. Fewer public IPs means fewer subnets
   or IPv6. What I wouldn't cut for a real bank: Multi-AZ, private subnets, TLS.</details>
5. You run `delete-stack`. How do you prove nothing is still billing?
   <details><summary>Answer</summary>The template avoids leftovers by design (no final DB snapshot, ECR
   emptied on delete, no Elastic IPs). Then I list the billable resource types (RDS instances and manual
   snapshots, load balancers, addresses, secrets, ECR repositories), all expected empty. The next day I
   check Cost Explorer, and I keep the budget alert until a full billing period shows nothing.</details>
