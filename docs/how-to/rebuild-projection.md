# Rebuild the position projection

Use this operation when the incremental position checkpoint disagrees with the append-only event log. A shadow mismatch raises `inventory_projection_shadow_mismatches_total` and writes an error containing the SKU/location and actual/expected positions; it does not repair data automatically.

`POST /api/admin/replay` truncates **all** rows in `inventory_position` and reconstructs them from `inventory_event` in one database transaction. It is a global operation, not a single-SKU repair. Plan a maintenance window for a large log: the projection table is locked while replay runs, and the full-history reducer is intentionally more expensive than an incremental append. A failure rolls that transaction back.

## Procedure

1. Record the mismatch details and inspect the affected [event history](../reference/api.md). Confirm that the event log is intact; replay cannot recover missing source facts.
2. Take a PostgreSQL backup or snapshot according to your infrastructure procedure. Coordinate with operators and pause traffic if required by your service-level objective.
3. Call the replay endpoint. Local development needs no authentication. Production requires HTTP Basic credentials **and** `X-Converge-Request: console`:

   ```bash
   curl -sS -X POST http://localhost:8080/api/admin/replay
   ```

   For production, substitute your HTTPS base URL and add `-u 'OPERATOR:SECRET' -H 'X-Converge-Request: console'`; do not paste real credentials into shared logs or a checked-in script.
4. Check the response's `aggregatesRebuilt` count, query the affected `GET /api/positions/{sku}/{location}`, and compare it with event history. Observe the next shadow-verifier cycle and confirm that the mismatch counter stops increasing.
5. Review any pending outbound attempts or exceptions separately. Replay rebuilds the local position table; it does not by itself undo remote writes or create a new outbound sync request. Use the operator console's force-sync control only if an external target needs a new push.

For the underlying rule and independent verifier, see [convergence and projection](../explanation/convergence.md). For endpoint security, see the [API reference](../reference/api.md).
