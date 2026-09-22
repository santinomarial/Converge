# HTTP API reference

The default local base URL is `http://localhost:8080`. Request and response bodies are JSON unless stated otherwise. Path `{sku}` and `{location}` identify the **numeric canonical SKU and location IDs**, not their human-readable codes.

In the local profile all endpoints are open for development. In `prod`, `/api/**` and `/actuator/prometheus` require HTTP Basic authentication. Every production API write (`POST`, `PUT`, `PATCH`, or `DELETE` under `/api/**`) also requires `X-Converge-Request: console`; the console sends it automatically. Webhook POSTs and health probes are publicly routable, while each webhook validates the provider HMAC. Use HTTPS in production.

## Inventory and identity

| Method | Path | Purpose / request | Response |
| --- | --- | --- | --- |
| `POST` | `/api/identity/skus` | Create `{sku, style, color, size, skuClass}` | `201 {"id":…}` |
| `POST` | `/api/identity/locations` | Create `{code, name, type}`; type is `STORE`, `WAREHOUSE`, `POP_UP`, or `ONLINE` | `201 {"id":…}` |
| `POST` | `/api/identity/sku-mappings` | Map `{canonicalSkuId, system, externalId}` | `204` |
| `POST` | `/api/identity/location-mappings` | Map `{locationId, system, externalId}` | `204` |
| `POST` | `/api/events` | Append a trusted, direct `AppendInventoryEvent` | `201` with sequence, insert flag, and position |
| `GET` | `/api/positions?sku=&location=` | List projected positions; both numeric filters are optional | Position array |
| `GET` | `/api/positions/{sku}/{location}` | Read one projected position | Position, or `404` |
| `GET` | `/api/positions/{sku}/{location}/history` | Events ordered by source time then sequence | Event array |
| `POST` | `/api/admin/replay` | Rebuild **all** positions from event history | `{"aggregatesRebuilt":…}` |

The direct event endpoint is useful for trusted internal use and the [local tutorial](../tutorials/quickstart.md). It is not a webhook replacement: its caller supplies canonical IDs and event semantics. For a snapshot, set `kind` to `SNAPSHOT` and supply `qtyAbsolute` only; for a delta, set `kind` to `DELTA` and supply `qtyDelta` only. Required fields include `canonicalSkuId`, `locationId`, `sourceSystem`, `externalEventId`, `eventType`, `kind`, and ISO-8601 `occurredAt`. `eventId`, `causationId`, and `payload` are optional. `eventType` is one of `SALE`, `RETURN`, `RESTOCK`, `ADJUSTMENT`, `TRANSFER`, or `COUNT`. Deduplication uses `(sourceSystem, externalEventId)`.

Replay truncates the position table within a transaction and may lock it for the duration; follow the [recovery procedure](../how-to/rebuild-projection.md) rather than using it as a routine read.

## Webhooks

| Method | Path | Required provider headers | Result |
| --- | --- | --- | --- |
| `POST` | `/webhooks/shopify` | `X-Shopify-Hmac-Sha256`, `X-Shopify-Webhook-Id`; `X-Shopify-Topic` defaults to `inventory_levels/update` | `200` after durable raw capture; `401` on bad HMAC, `400` on missing ID |
| `POST` | `/webhooks/square` | `X-Square-HmacSha256-Signature`, `X-Square-Event-Id` | `200` after durable raw capture; `401` on bad HMAC, `400` on missing ID |

The raw body bytes are used for signature verification. Set the configured webhook secrets and the exact Square notification URL before registering endpoints with commerce providers.

There is no warehouse CSV upload HTTP endpoint. Warehouse parsing and ingestion are internal application services; see [architecture](../explanation/architecture.md).

## Operations and repair

| Method | Path | Purpose / request | Response |
| --- | --- | --- | --- |
| `GET` | `/api/operations/positions` | Console view combining ledger and latest observed Shopify, Square, and warehouse quantities | Position view array |
| `GET` | `/api/operations/summary` | Today's event count and number of active locations | Summary object |
| `GET` | `/api/drift?system=&window=PT24H` | Drift samples, optionally filtered by system; `window` is an ISO-8601 duration | Sample array |
| `GET` | `/api/exceptions?state=&severity=` | Filtered exception queue | Exception array |
| `POST` | `/api/exceptions/{id}/claim` | `{ "actor": "operator-name" }`; only an open, unlocked exception can be claimed | Claimed exception, or `409` |
| `POST` | `/api/exceptions/{id}/resolve` | `{ "action": "ADJUST_TO", "qty": 12, "note": "…", "actor": "operator-name" }` or action `DISMISS` | Resolved/dismissed exception; claim required first |
| `POST` | `/api/sync/{sku}/{location}` | Force a new push of the current position to mapped targets | `{"targetsQueued":…}` |
| `GET` | `/api/connectors/health` | Connector status, breaker state, lag, and last sync | Connector array |

`ADJUST_TO` appends a ledger adjustment to reach the specified absolute quantity; `DISMISS` does not change inventory. Both require the exception to have been claimed. The server checks claim ownership when an actor is supplied.

## Health and metrics

`GET /actuator/health`, `/actuator/health/liveness`, and `/actuator/health/readiness` expose health probes. `GET /actuator/prometheus` exposes Micrometer metrics; unlike the health probes it requires Basic authentication in production. See [reliability and observability](../explanation/reliability.md).
