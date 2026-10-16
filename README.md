# Concurrent ledger

An in-memory double-entry ledger on Java 25. The core uses only the JDK; Javalin/Jetty handles HTTP, Gson handles JSON, and Jakarta Validation checks request DTOs.

## Run

Install **JDK 25**, set `JAVA_HOME` to it, then run from this directory:

```powershell
.\gradlew.bat run
```

On macOS/Linux: `./gradlew run`. The Gradle wrapper downloads build dependencies on first use; no Gradle installation is required.

The service listens at **http://127.0.0.1:8080**. Override `HOST` and `PORT` through environment variables. Stop with **Ctrl+C**; all state is discarded.

## API

POST bodies require `Content-Type: application/json` and are limited to 8 KiB. Money is integer minor units of one implicit currency, stored as signed 64-bit integers. Account IDs are UUIDs.

| Endpoint | Request / response |
| --- | --- |
| `POST /accounts` | `{ "initialBalance": 1000 }` → `201 { "id": "<uuid>", "balance": 1000 }`. Omitted balance defaults to zero. |
| `GET /accounts/{id}/balance` | `200 { "id": "<uuid>", "balance": 1000 }` |
| `POST /transfers` | Requires `Idempotency-Key`; example below. Returns `200` with the transfer ID, status and two balanced postings. |
| `GET /accounts/{id}/entries` | `200` with the account's journal transactions, oldest first. |

```http
POST /transfers
Content-Type: application/json
Idempotency-Key: payment-123

{"fromAccountId":"<source-uuid>","toAccountId":"<destination-uuid>","amount":250}
```

Use account IDs returned by account creation. [requests.http](requests.http) provides a complete executable walkthrough.

Malformed input, duplicate JSON fields, nonpositive amounts and self-transfers return `400`; missing accounts return `404`; insufficient funds or destination overflow return `422`; conflicting key reuse returns `409`. Unexpected server failures return a generic `500` without exposing internal details.

## Guarantees

- **Atomic transfers:** both balances and journal postings change together, or nothing changes. Balances never become negative; overflow is rejected.
- **Concurrency:** account locks prevent lost updates and double-spending. Locks are acquired in UUID order to avoid deadlocks; unrelated account pairs can proceed in parallel.
- **Idempotency:** send exactly one key containing 1–128 ASCII letters, digits or `._:-`. Matching retries return the original status and body, including business rejections. In-flight retries wait for the original outcome; different transfer details with the same key return `409`.
- **Failure recovery:** losing a response does not undo a transfer. Retry with the same key. An exceptional transfer attempt retains its key and fails again rather than executing again. Malformed requests rejected before ledger submission do not reserve keys. Account creation has no idempotency protection.
- **Reads:** each balance or journal read is a consistent snapshot of one account. Separate reads do not form a global snapshot.

## Trade-offs

- **Memory only:** accounts, journals and keys disappear on restart. Guarantees apply within one running process; there is no crash durability or coordination across instances.
- **Lifetime retention:** keys are service-wide and never evicted, preserving retry safety at the cost of unbounded memory. Journals are unpaginated.
- **Review scope:** no authentication, authorization, TLS termination or admission limits. Opening balances are an explicit funding mechanism recorded against external opening equity; a real wallet must authorize funding.
- **Business rules independent of storage:** `LedgerService` prepares decisions inside `LedgerStorage.apply(key, command, checkExistingCommand, decide)`. `InMemoryStorage` coordinates account locks and retained attempts. A replacement engine implements the atomic storage operation while retaining the same service rules.
- **Numeric precision:** JavaScript clients need lossless JSON handling above `9007199254740991`; quoted amounts are not accepted.

## Test and design notes

The dependency direction is `HTTP -> LedgerService -> LedgerStorage <- InMemoryStorage`. Domain values live in `dev.ledger.domain`; the service, domain and storage use only the JDK. The storage interface exposes no independent key reservation or balance setters: one transfer call coordinates the key, original command, result, balances and journals. A durable implementation would have to commit those together; this implementation remains memory-only.

Run `.\gradlew.bat test` (macOS/Linux: `./gradlew test`). Tests cover service rules with scripted storage, storage effects, contention, overlapping retries across two service instances sharing storage, independent transfers, rejection/failure paths and lost responses. Deadlock-sensitive scenarios run in child JVMs with externally enforced deadlines. A child-process smoke test boots the configured application on an ephemeral port and exercises account creation, transfer replay and validation over real HTTP. Startup coverage for the generated distribution launcher remains a follow-up.

See [SOLUTION.md](SOLUTION.md) for design reasoning and [REQUIREMENTS.md](REQUIREMENTS.md) for the detailed review and remaining test gaps.
