# API contract

Base URLs in local development:

```text
Gateway: http://localhost:8080
Account: http://account-service:8081 inside Compose
```

Account is not published to the host in the final Compose unless a `debug` profile is used.

## 1. Event request

```json
{
  "eventId": "evt-001",
  "accountId": "acct-123",
  "type": "CREDIT",
  "amount": 150.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T14:02:11Z",
  "metadata": {
    "source": "batch",
    "batchId": "B-9042"
  }
}
```

Java transport shape:

```java
import tools.jackson.databind.JsonNode;

public record EventRequest(
    String eventId,
    String accountId,
    EventType type,
    BigDecimal amount,
    String currency,
    Instant eventTimestamp,
    JsonNode metadata
) {}
```

Spring Boot 4.1 defaults to Jackson 3. Use `tools.jackson.*` (`JsonNode`,
`JsonMapper`) in new code. Do not accidentally copy deprecated Jackson 2
`com.fasterxml.jackson.databind.*` imports; Jackson annotations remain under
`com.fasterxml.jackson.annotation` by design.

Use Jakarta validation annotations on record components where appropriate and add a validator for metadata object/currency normalization.

Exact boundary normalization:

- require both IDs to match `^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$`; preserve case and reject whitespace/path/query delimiters rather than depending on ambiguous URI decoding;
- accept exactly three ASCII currency letters, uppercase them, and reject codes
  not recognized by Java 17 `Currency.getInstance`;
- reject an amount that cannot fit `decimal(38,18)` without rounding (20 integer digits, 18 fractional digits after insignificant trailing zeroes are removed);
- normalize missing/`null` metadata to `{}`;
- normalize missing/`null` metadata to `{}` and compare parsed metadata with
  ordinary `JsonNode.equals`; object member order is ignored, while array order
  and numeric node representation remain significant.

This behavior must appear in validation and idempotency tests. It is not acceptable to let H2 throw a generic `500` for an oversized amount.

## 2. Gateway `POST /events`

### Success representation

```json
{
  "eventId": "evt-001",
  "accountId": "acct-123",
  "type": "CREDIT",
  "amount": 150.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T14:02:11Z",
  "metadata": {"source": "batch", "batchId": "B-9042"},
  "applicationStatus": "APPLIED",
  "receivedAt": "2026-07-14T16:00:00Z",
  "appliedAt": "2026-07-14T16:00:00.120Z"
}
```

| Status | Meaning | Headers |
|---|---|---|
| `201 Created` | new Gateway event and confirmed Account application | `Location: /events/evt-001`, optional `X-Trace-Id` |
| `200 OK` | identical existing event; already applied or safe recovery completed | `X-Idempotent-Replay: true` |
| `400 Bad Request` | validation failed | problem JSON |
| `409 Conflict` | event ID reused with different payload, or Account reports a deterministic currency/idempotency conflict | problem JSON |
| `502 Bad Gateway` | Account returns a defensive validation/invalid-response result that means the internal service contract is broken | problem JSON |
| `503 Service Unavailable` | Account outcome cannot be confirmed after bounded policy | `Retry-After`, problem JSON |

### `503` wording

```json
{
  "type": "urn:event-ledger:problem:account-unavailable",
  "title": "Account Service unavailable",
  "status": 503,
  "detail": "Application of event evt-002 could not be confirmed. Retrying the same eventId is safe.",
  "instance": "/events",
  "traceId": "4f2d...",
  "eventId": "evt-002",
  "applicationStatus": "APPLY_FAILED"
}
```

Do not echo arbitrary metadata or downstream stack traces.

For a terminal Account conflict, Gateway stores `APPLY_FAILED` with a terminal failure code. An identical client retry returns the same `409` without blindly calling Account again. Timeout/connect/open-circuit/retryable-`5xx` failures use a retryable-unconfirmed code and remain eligible for same-ID recovery.

## 3. Gateway event reads

### `GET /events/{eventId}`

- `200` with the stored event representation, including `RECEIVED`, `APPLIED`, or `APPLY_FAILED`.
- `404` ProblemDetail when absent.

### `GET /events?account={accountId}`

Core response is a JSON array to avoid spending time on invented pagination:

```json
[
  {"eventId":"evt-early","eventTimestamp":"2026-05-01T00:00:00Z","applicationStatus":"APPLIED"},
  {"eventId":"evt-late","eventTimestamp":"2026-05-02T00:00:00Z","applicationStatus":"APPLIED"}
]
```

Order is `eventTimestamp ASC, eventId ASC`.

Required query parameter absent/blank -> `400`.

## 4. Gateway balance proxy

### `GET /accounts/{accountId}/balance`

This required interpretation makes client balance-degradation behavior
observable while Account remains an internal service.

- `200`: proxied Account balance.
- `404`: Account has no transaction/account.
- `503`: Account call unavailable/timeout/open circuit.

```json
{
  "accountId": "acct-123",
  "currency": "USD",
  "balance": 125.50,
  "asOf": "2026-07-14T16:05:00Z"
}
```

`asOf` is response generation time, not a stored ledger snapshot time.

## 5. Account apply contract

### `POST /accounts/{accountId}/transactions`

Internal body:

```json
{
  "eventId": "evt-001",
  "type": "CREDIT",
  "amount": 150.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T14:02:11Z"
}
```

The account comes from the path and is included in semantic idempotency comparison.

| Status | Meaning |
|---|---|
| `201 Created` | first insertion |
| `200 OK` | identical transaction already exists |
| `400 Bad Request` | defensive input validation |
| `409 Conflict` | same ID/different transaction or account-currency mismatch |

Response:

```json
{
  "eventId": "evt-001",
  "accountId": "acct-123",
  "type": "CREDIT",
  "amount": 150.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T14:02:11Z",
  "appliedAt": "2026-07-14T16:00:00.100Z"
}
```

Do not return `balanceAfter` as the “original” duplicate response unless it is actually stored. A later transaction changes the current balance, so recomputing it would not reproduce the original response.

## 6. Account queries

### `GET /accounts/{accountId}/balance`

- `200` with scalar balance and established currency.
- `404` when no account/transaction exists.

### `GET /accounts/{accountId}`

```json
{
  "accountId": "acct-123",
  "currency": "USD",
  "balance": 125.50,
  "recentTransactions": [
    {
      "eventId": "evt-002",
      "type": "DEBIT",
      "amount": 24.50,
      "eventTimestamp": "2026-05-16T10:00:00Z"
    }
  ]
}
```

Recent list is at most 20, newest first with event ID tie-break.

## 7. Health

### Account `GET /health`

```json
{
  "status": "UP",
  "service": "account-service",
  "checks": {"database": "UP"}
}
```

DB diagnostic failure -> `503` and `status: DOWN`.

### Gateway `GET /health`

```json
{
  "status": "DEGRADED",
  "service": "event-gateway",
  "checks": {
    "database": "UP",
    "accountService": "UNAVAILABLE",
    "circuitBreaker": "OPEN"
  }
}
```

Before a successful/failing dependency observation, `accountService` may be `UNKNOWN`. Do not claim `UP` from a closed breaker alone.

## 8. Problem format

Use Spring `ProblemDetail`:

```json
{
  "type": "urn:event-ledger:problem:idempotency-conflict",
  "title": "Event identifier conflict",
  "status": 409,
  "detail": "eventId evt-001 already belongs to a different event payload",
  "instance": "/events",
  "traceId": "4f2d...",
  "errors": []
}
```

Stable types:

```text
urn:event-ledger:problem:validation
urn:event-ledger:problem:not-found
urn:event-ledger:problem:idempotency-conflict
urn:event-ledger:problem:currency-conflict
urn:event-ledger:problem:downstream-contract
urn:event-ledger:problem:account-unavailable
urn:event-ledger:problem:internal
```

## 9. Trace contract

Primary request propagation:

```http
traceparent: 00-<32 hex trace id>-<16 hex span id>-01
```

Do not manually generate a separate trace ID when a valid upstream `traceparent` exists. Let the tracing library continue it. Build the Account client from Spring's injected auto-configured `RestClient.Builder`; creating a bare client can bypass instrumentation.

## 10. Contract files in this repository

Create:

```text
contracts/event-gateway-openapi.yaml
contracts/account-service-openapi.yaml
```

The YAML must match implemented responses before it is called complete. Static OpenAPI is useful even if no code generator is added.
