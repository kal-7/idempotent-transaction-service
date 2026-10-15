# Idempotent Background Transaction Processing Service

A Java 17 / Spring Boot implementation of the FDE L2 assessment. It demonstrates idempotent ingestion, sequence-based ordering, retry-safe simulated transient failure, atomic ledger/balance updates, status reporting, validation, and tests.

## Design decisions
- `transactionId` is the business idempotency key; `requestId` detects request replays.
- Same ID + same business payload returns the original transaction. Same ID + different payload returns HTTP 409.
- Sequence ordering is per `(accountId, currency)`: the worker expects `lastProcessedSequence + 1`; future events stay `PENDING_SEQUENCE`.
- Account balance, ledger insert, sequence advancement, and transaction status update share one JPA transaction.
- Ledger has a unique constraint on `transactionId` as a second line of defense.
- `simulateTransientFailure=true` deliberately fails before any balance or ledger write, once, to demonstrate retry safety.
- PostgreSQL is used for persistence; Docker Compose provides the local database.

## Run
Requirements: Docker Compose.

```bash
docker compose up --build -d
```

Compose builds the Spring Boot JAR in a Maven build stage, packages it into the application image, and starts it after PostgreSQL is healthy. The app listens on `http://localhost:8080`. To stop the services, run `docker compose down`.

## Submit a transaction
```bash
curl -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -d '{"transactionId":"TXN-1","requestId":"REQ-1","accountId":"ACC-1","transactionType":"CREDIT","amount":100,"currency":"INR","sequenceNumber":1}'
```
Expected: HTTP 202 and status `RECEIVED`; the scheduled worker processes it within about one second.

## Exercise a transient failure
```bash
curl -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -d '{"transactionId":"TXN-2","requestId":"REQ-2","accountId":"ACC-2","transactionType":"CREDIT","amount":500,"currency":"INR","sequenceNumber":1,"simulateTransientFailure":true}'
```
First attempt becomes `RETRY_PENDING`; a later attempt becomes `PROCESSED`.
Retry timing is exponential and bounded by `app.worker.retry.initial-delay-ms` (default 1 second) and `app.worker.retry.max-delay-ms` (default 60 seconds). `app.worker.max-attempts` (default 3) limits attempts; exhausted transient failures become `RETRY_EXHAUSTED` and expose `nextAttemptAt` in transaction responses. Worker errors are recorded in a separate transaction so a failed processing transaction does not erase the retry count.

## Endpoints
- `POST /api/transactions` — accept transaction
- `GET /api/transactions` — list statuses
- `GET /api/transactions/{transactionId}` — inspect one transaction
- `GET /api/accounts/{accountId}/balance?currency=INR` — balance and last sequence
- `GET /api/ledger` — ledger entries
- `GET /api/summary` — counts by status

## Tests
```bash
mvn test
```
Tests cover duplicate business IDs, concurrent duplicate submissions, concurrent processing attempts, sequence gaps, retry backoff, retry exhaustion, and transient retry without duplicate ledger effects.

## Example input file
`sample-transactions.json` mirrors the assessment's suggested data. Submit its records individually to the POST endpoint in any order.

## Production hardening / known limitations
This is a practical assessment implementation, not a fully distributed banking ledger:
1. Pessimistic database row locks serialize processing of the same transaction and updates to the same account across application instances. Workers may still select the same candidate before locking it, causing lock contention; `SELECT ... FOR UPDATE SKIP LOCKED` or a broker can improve throughput at larger scale.
2. Persist every duplicate delivery as a separate request-attempt record if the audit requires `DUPLICATE` status per delivery. This starter returns the original transaction on duplicate delivery to avoid a second business effect.
3. Define whether sequences are contiguous and how an account's initial sequence is provisioned. This implementation assumes sequence starts at 1.
4. Add currency/scale rules, reversal semantics, debit authorization, and reconciliation.
5. Add an authenticated recovery workflow for `RETRY_EXHAUSTED` transactions, plus metrics, correlation IDs, authentication, rate limits, and structured JSON logs.
6. This starter relies on Hibernate schema updates. For production, use Flyway migrations, PostgreSQL-backed integration tests with Testcontainers, and an outbox for external side effects.

## AI-assisted development note
If using this project as an assessment submission, review every class, run the tests, adapt the design, and be ready to explain the trade-offs. Do not claim test execution unless you run `mvn test` locally.
