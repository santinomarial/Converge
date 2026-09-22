# Convergence and projection

For a SKU/location, the append-only `inventory_event` table is the source of truth. `inventory_position` is a checkpoint for fast reads and ordinary writes, not a competing source of facts.

![Snapshot and delta example with incremental and shadow reducers.](../diagrams/04-projection-convergence.svg)

## The invariant

Let `S*` be the winning snapshot with the greatest `(occurred_at, seq)` and `D_after` the delta facts whose `occurred_at` is strictly later than that snapshot. Then:

```text
position(E) = quantity(S*) + Σ quantity(d), d ∈ D_after
```

If there is no snapshot, the anchor is zero and all deltas participate. A late delta at or before the winning snapshot is retained as `absorbed`: it remains auditable, but does not alter the current position. Snapshot ties use sequence order to choose the anchor. As long as the same facts and source times are present, a permutation of delta delivery does not change the result:

```text
position(π(E)) = position(E)
```

The diagram's arrival order is snapshot 100, delta −3, late delta +10 dated before the anchor, then delta +2. Its projected result is 99, not 109.

## Fast path and independent check

The append transaction locks the aggregate key, inserts the event, applies the delta or winning snapshot to `inventory_position`, advances `last_applied_seq`, and writes the outbox row. Ordinary appends do not scan the aggregate's full history.

A rotating shadow verifier reads batches of aggregate keys, recomputes from the complete event log under a repeatable-read transaction, and compares the result with the incremental checkpoint. It increments verified/mismatch counters and logs a mismatch. It does **not** silently mutate the projection. The [replay operation](../how-to/rebuild-projection.md) is an explicit administrative rebuild from the full reducer.

The property is tested over 250 generated cases per property in [LedgerConvergenceProperties.java](../../src/test/java/io/converge/ledger/LedgerConvergenceProperties.java), against PostgreSQL replay in [LedgerServiceIntegrationTest.java](../../src/test/java/io/converge/ledger/LedgerServiceIntegrationTest.java), and under concurrent duplicates in [ConcurrentDuplicateWebhookChaosTest.java](../../src/test/java/io/converge/chaos/ConcurrentDuplicateWebhookChaosTest.java).
