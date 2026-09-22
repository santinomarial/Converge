# Configuration reference

Converge reads environment variables through [application.yml](../../src/main/resources/application.yml). Defaults are for local development, not production recommendations. The checked-in [Compose file](../../compose.yaml) wires the local services; [fly.toml](../../fly.toml) selects the production profile and an always-on Machine.

## Database and transport

| Variable | Local default | Meaning |
| --- | --- | --- |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/converge` | PostgreSQL JDBC URL; required in production. |
| `DATABASE_USERNAME`, `DATABASE_PASSWORD` | `converge`, `converge` | Database credentials; both required in production. |
| `DATABASE_POOL_MAX_SIZE`, `DATABASE_POOL_MIN_IDLE` | `30`, `5` | Hikari connection pool bounds. |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka-compatible brokers; required in production. |
| `KAFKA_SECURITY_PROTOCOL` | `PLAINTEXT` | Set `SASL_SSL` for a TLS/SASL provider. |
| `KAFKA_SASL_MECHANISM`, `KAFKA_SASL_JAAS_CONFIG` | Empty | Both required when using a `SASL_` protocol. |
| `REDIS_HOST`, `REDIS_PORT` | `localhost`, `6379` | Redis endpoint; host required in production. |
| `REDIS_SSL_ENABLED` | `false` | Enable TLS for a TLS Redis provider. |

## Commerce connectors and operator access

| Variable | Local default | Meaning |
| --- | --- | --- |
| `SHOPIFY_BASE_URL` | `http://localhost:9999` | Shopify API URL; must be HTTPS in production. |
| `SHOPIFY_ACCESS_TOKEN` | `development-token` | Outbound API token. |
| `SHOPIFY_WEBHOOK_SECRET` | `development-secret` | Inbound HMAC secret. |
| `SQUARE_BASE_URL` | `http://localhost:9998` | Square API URL; must be HTTPS in production. |
| `SQUARE_ACCESS_TOKEN` | `development-token` | Outbound API token. |
| `SQUARE_WEBHOOK_SIGNATURE_KEY` | `development-square-secret` | Inbound HMAC key. |
| `SQUARE_NOTIFICATION_URL` | `http://localhost:8080/webhooks/square` | Exact public webhook URL used for Square signature checks; HTTPS in production. |
| `CONSOLE_USERNAME`, `CONSOLE_PASSWORD` | Not set | Production Basic-auth account; password must be at least 20 characters. |

`prod` startup validation requires all connector values and console credentials above, plus database URL/credentials, Kafka brokers, and Redis host. It rejects blank values, `...`, `development-` placeholders, and localhost infrastructure addresses. API reads and writes use Basic auth in production, and API writes additionally require `X-Converge-Request: console`; see the [API reference](api.md). Keep secrets out of Git and `fly.toml`.

## Worker schedules and resilience

| Variable | Default | Meaning |
| --- | --- | --- |
| `RECONCILIATION_POLL_DELAY`, `RECONCILIATION_INITIAL_DELAY` | `60s`, `60s` | Drift polling cadence and startup delay. |
| `LEDGER_SHADOW_VERIFICATION_DELAY`, `LEDGER_SHADOW_VERIFICATION_INITIAL_DELAY` | `60s`, `60s` | Full-history shadow verification cadence and startup delay. |
| `LEDGER_SHADOW_VERIFICATION_BATCH_SIZE` | `100` | Aggregates checked in one verifier pass. |
| `OUTBOX_RELAY_DELAY`, `OUTBOX_RELAY_INITIAL_DELAY` | `1s`, `60s` | Outbox publish schedule. |
| `SYNC_WORKER_DELAY`, `SYNC_WORKER_INITIAL_DELAY` | `500ms`, `60s` | Outbound attempt worker schedule. |
| `SYNC_MAX_ATTEMPTS` | `5` | Configured retry limit before a terminal failure. |
| `SYNC_RUNNING_LEASE` | `30s` | Lease after which an incomplete running attempt can be reclaimed. |
| `SYNC_RATE_CAPACITY`, `SYNC_RATE_REFILL_PER_SECOND` | `20`, `10` | Outbound token-bucket capacity and refill rate. |

## Telemetry

| Variable | Local default | Meaning |
| --- | --- | --- |
| `TRACING_SAMPLING_PROBABILITY` | `1.0` | Trace sample probability; `fly.toml` sets `0.1`. |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://localhost:4318/v1/traces` | OTLP HTTP trace receiver. |

Prometheus metrics are exposed at `/actuator/prometheus`. The local [scrape configuration](../../monitoring/prometheus.yml) collects every five seconds, and the [dashboard JSON](../../monitoring/grafana/dashboards/inventory-drift.json) is provisioned by Compose. Production scraping must supply Basic credentials.
