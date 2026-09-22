# Run Converge locally

This tutorial starts the whole local stack and creates a first canonical position. It needs Docker with Compose, `curl`, and (for the test command) JDK 21. The stack starts Converge, PostgreSQL 16, Redpanda, Redis, Prometheus, an OpenTelemetry Collector, and Grafana.

## 1. Start the stack

From the repository root:

```bash
docker compose up -d --build
docker compose ps
curl http://localhost:8080/actuator/health
```

The [console](http://localhost:8080) initially has no inventory; the application does not seed fake commerce data. [Grafana](http://localhost:3000) is anonymously accessible in this **local-only** stack and provisions the `Converge inventory drift` dashboard. [Prometheus](http://localhost:9090) scrapes application metrics.

## 2. Create a SKU and location

The local profile permits API requests without credentials. The commands below assume a new database, where the first IDs are `1`. If you already have data, use the IDs returned by the first two calls in the event command.

```bash
curl -sS -X POST http://localhost:8080/api/identity/skus \
  -H 'Content-Type: application/json' \
  -d '{"sku":"DEMO-001","style":"Demo","color":"Blue","size":"M","skuClass":"STANDARD"}'

curl -sS -X POST http://localhost:8080/api/identity/locations \
  -H 'Content-Type: application/json' \
  -d '{"code":"DEMO-WH","name":"Demo warehouse","type":"WAREHOUSE"}'
```

Each call returns an `id`. This example uses the direct event endpoint to create a position without configuring an external webhook. It is convenient for local learning; the endpoint also exists in production and is protected there, so restrict its use to trusted operators:

```bash
curl -sS -X POST http://localhost:8080/api/events \
  -H 'Content-Type: application/json' \
  -d '{"canonicalSkuId":1,"locationId":1,"sourceSystem":"tutorial","externalEventId":"demo-snapshot-1","eventType":"ADJUSTMENT","kind":"SNAPSHOT","qtyAbsolute":12,"occurredAt":"2026-01-01T00:00:00Z"}'

curl -sS http://localhost:8080/api/positions/1/1
```

The position quantity should be `12`. Refresh the console to see it. The `externalEventId` makes repeated submissions of the same event idempotent; use a new value for a genuinely new fact. To exercise Shopify/Square end-to-end, register canonical mappings and configure real connector credentials; see the [API reference](../reference/api.md) and [configuration reference](../reference/configuration.md).

## 3. Run the verification suite

```bash
./gradlew test
```

The integration and chaos tests start PostgreSQL, Redpanda, and Redis Testcontainers. The suite includes an intentional 60-second throttling scenario, so it is not an instant smoke test.

## Ports and shutdown

Every published port can be overridden if another local service occupies a default:

```bash
APP_PORT=18080 POSTGRES_PORT=15432 REDPANDA_PORT=19092 \
REDPANDA_ADMIN_PORT=19644 REDIS_PORT=16379 PROMETHEUS_PORT=19090 \
GRAFANA_PORT=13000 OTEL_HTTP_PORT=14318 docker compose up -d --build
```

Stop with `docker compose down`. Named PostgreSQL and Redis volumes remain, so the tutorial data is still there on the next start. Removing those volumes requires a separate, destructive action; this guide does not do it.
