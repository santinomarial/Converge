# Performance measurement

This is a recorded development-machine result, **not** a production capacity guarantee. The checked-in [k6 scenario](../../load/webhook.js) creates canonical mappings, signs each Shopify payload, and drives the raw-first HTTP ingestion path at a constant arrival rate. The endpoint acknowledges only after the raw webhook is durable; normalization and projection happen asynchronously.

## Reproduce the scenario

With a running local stack, from the repository root:

```bash
docker run --rm -v "$PWD/load:/scripts:ro" grafana/k6:2.2.0 run \
  -e BASE_URL=http://host.docker.internal:8080 \
  -e RATE=500 -e DURATION=30s -e VUS=500 -e MAX_VUS=500 \
  /scripts/webhook.js
```

The result below was measured on 2026-08-31 from a clean database and broker on an Apple Silicon development machine, with the JVM on the host and PostgreSQL, Redpanda, and Redis in Docker:

| Arrival rate | Completed | Failures | Average ack | p95 | p99 | Max |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 500/s for 30s | 15,001 | 0% | 7.87 ms | 37.01 ms | 118.81 ms | 298.95 ms |

The hostile shape sent all 15,001 facts to one SKU/location. The aggregate lock serialized that key; unrelated keys remained independently processable. Projection caught up after the burst. Ordinary writes use an incremental checkpoint rather than a full-history reduction, while rotating shadow verification and explicit replay retain an independent correctness check. See [convergence and projection](../explanation/convergence.md).

For a production sizing decision, rerun against the intended topology and representative cardinality, webhook distribution, connector rate limits, payload sizes, and retention. Monitor end-to-end projection/sync lag as well as acknowledgment latency.
