# Failure model

This is the most important design document for the walkthrough.

## 1. Why HTTP success/failure is not the same as business success/failure

If Gateway receives `201` from Account, it knows Account committed. If it receives a connection error or timeout, it does **not** know whether Account committed. The request may never have arrived, may still be running, or may have committed before the response was lost.

Therefore:

- retrying a non-idempotent financial POST is dangerous;
- retrying this POST becomes safe only after Account deduplicates by `eventId`;
- the client error must say “outcome not confirmed,” not “nothing happened.”

## 2. Crash-point matrix

“Durable” in this matrix assumes the default file-backed H2 runtime reopens the same database file/Compose volume. In-memory test profiles deliberately do not survive a process restart.

| Point | Durable Gateway state | Durable Account state | Client sees | Recovery |
|---|---|---|---|---|
| Before Gateway insert | none | none | `400/500` depending cause | client can submit normally |
| After Gateway insert, before HTTP | `RECEIVED` | none | connection may drop/`503` | same ID retry attempts Account |
| HTTP never reaches Account | `APPLY_FAILED` | none | `503` | same ID retry |
| Account validates/rejects 4xx | `APPLY_FAILED` with terminal/contract code | none | mapped `409` or `502` contract error | return retained terminal conflict or fix contract; do not blind-retry |
| Account commits, response succeeds | `APPLIED` after update | one transaction | `201/200` | complete |
| Account commits, response lost | `APPLY_FAILED` may be recorded | one transaction | `503` | retry; Account returns replay; mark APPLIED |
| Gateway crashes after Account response but before status update | `RECEIVED` | one transaction | client connection fails | retry; Account returns replay; mark APPLIED |
| Gateway status APPLIED, response to client lost | `APPLIED` | one transaction | client connection fails | duplicate returns stored event without Account call |
| Two same-ID requests race | one Gateway row | one Account transaction | `201/200/503` depending timing | monotonic status + downstream dedupe |
| Account down after prior events | prior rows intact | prior transactions intact but unreachable | writes/balance `503`; event reads work | restart Account; retry failed IDs |

## 3. Failure classification

| Failure | Retry automatically? | Circuit failure? | Client result |
|---|---|---|---|
| connect refused | only if the gated retry bonus is selected | yes | `503` if exhausted/no automatic retry |
| read timeout | only if the gated retry bonus is selected | yes | `503` outcome unconfirmed |
| Account 500/502/503/504 | only if the gated retry bonus and status policy select it | yes | `503` if exhausted/no automatic retry |
| Account 400 validation | no | no | `502` contract bug or mapped `400` only if client-caused and safe |
| Account 409 ID/payload conflict | no | no | `409`; investigate disagreement |
| Account 404 on balance | no | no | `404` |
| open circuit | no immediate retry storm | already open | fast `503` |

The Gateway stores a bounded failure classification. An identical replay reattempts only `RECEIVED` or `RETRYABLE_UNCONFIRMED`; it returns a retained terminal conflict without calling Account again. `APPLY_FAILED` never by itself means “Account definitely rolled back.”

## 4. Timeout budget

The exact numbers must be tested on the local demo rather than presented as production truth. A reasonable demo starting point:

```text
connect timeout: 300 ms
response timeout: 800 ms
max attempts: 2 total attempts if retry bonus is enabled
backoff: 100 ms then bounded exponential/jitter
client-visible worst case: comfortably below 3 seconds
```

Do not make a fragile test assert a microsecond latency. Assert that failure is within a generous upper bound and that the downstream request count/state is correct.

## 5. Circuit behavior

Suggested demo configuration:

```text
sliding window: count-based 6 calls
minimum calls: 4
failure rate threshold: 50%
open wait: 5 seconds
half-open permitted calls: 2
```

These small values make the state observable in a demo. Production thresholds require measured latency/error distributions and traffic volume.

Decorator/order decision:

- timeout is enforced by the HTTP client;
- optional retry executes the idempotent raw call;
- the circuit should count one exhausted user operation rather than every internal attempt when possible;
- record/document the actual Spring Cloud composition and prove it with a request-count test instead of assuming annotation order.

## 6. Concurrent-request edge case

Two same-ID Gateway requests can both observe a non-terminal event and call Account. Account's unique event constraint makes only one insertion succeed; the other returns replay.

Possible response ordering:

1. request A succeeds and marks `APPLIED`;
2. request B times out late;
3. request B tries to mark `APPLY_FAILED`;
4. conditional update refuses because state is already `APPLIED`.

Without that conditional rule, an applied transaction could be shown as failed.

## 7. What the core does not solve

`APPLY_FAILED` events require a client retry. The core does not run a background reconciler. A production or bonus design could:

1. claim retryable rows with a lease;
2. call Account using the same event ID;
3. mark applied on new/replay success;
4. back off with a maximum age/attempt policy;
5. expose backlog age/count metrics;
6. send permanently failing events to manual review.

That is store-and-forward/reconciliation. It is not automatically a transactional outbox unless a local outbox row is atomically published to a broker.

## 8. Failure-demo script outline

```text
1. start both services
2. submit evt-001 -> 201/APPLIED
3. submit it again -> 200 replay; balance unchanged
4. stop Account
5. submit evt-002 -> bounded 503; Gateway GET shows APPLY_FAILED
6. prove Gateway list still returns evt-001 and evt-002 in event-time order
7. restart Account
8. retry evt-002 -> 200/201 and APPLIED
9. query balance -> exactly one effect from each ID
10. inspect matching trace IDs / metrics
```
