# Architecture and design decisions

Converge is one Spring Boot deployable with explicit Spring Modulith modules. It serves the React console and API, accepts inventory facts, maintains a canonical event log and projection, and runs background reconciliation and sync workers. PostgreSQL holds the correctness boundary; Kafka-compatible transport moves work asynchronously; Redis supports rate limits and a non-authoritative duplicate hint.

![Runtime and container view of Converge and its infrastructure.](../diagrams/02-runtime-containers.svg)

The [diagram gallery](../diagrams/README.md) moves from system context through the event journey, convergence, sync recovery, and deployment target. The test suite verifies module boundaries and generates code-derived PlantUML under `build/spring-modulith-docs/`.

## How a fact moves

1. Shopify or Square signs a webhook. Converge verifies it, commits the raw payload to `raw_webhook`, and only then acknowledges it.
2. A Kafka-compatible message prompts normalization. External identifiers must resolve to a canonical SKU/location; otherwise the payload is quarantined for review.
3. A normalized fact is appended to `inventory_event`. PostgreSQL uniqueness on `(source_system, external_event_id)` is the durable idempotency authority.
4. In the same transaction, the incremental projector updates `inventory_position` and writes an outbound message to `outbox`.
5. A relay publishes the position change. The sync planner creates durable per-target attempts; workers use rate limits, a circuit breaker, retries, and exception escalation.

![The five stages from webhook receipt through outbound synchronization.](../diagrams/03-event-journey.svg)

Seven versioned [Flyway migrations](../../src/main/resources/db/migration) define the event log, projection, canonical mappings, quarantine, raw webhooks, drift samples, exceptions, outbox, sync attempts, and compensation records.

The warehouse CSV parser and ingestor are implemented as an internal service; the HTTP API does not currently expose a CSV upload route or schedule an automatic warehouse pull. Shopify and Square have signed HTTP webhook entry points.

## Why PostgreSQL is the event store

The log needs append-only facts, JSON payloads, global sequence numbers, uniqueness, locks, and atomic projection/outbox writes. PostgreSQL supplies all of them in one transaction. A separate event-store product would add an operational dependency without improving that atomic boundary. Redis can short-circuit duplicates, but its loss cannot violate durable idempotency.

The tradeoff is write amplification and table growth. Ordinary writes use an incremental `last_applied_seq` checkpoint, so a long-lived SKU does not trigger a full-history reduction on every append. Full replay remains available for repair, and a rotating shadow verifier checks incremental output against an independent reducer.

## Why a modular monolith

Ingestion, identity, ledger, outbox, and exceptions share invariants that are easier to keep inside one local transaction. Splitting them into services now would convert those invariants into distributed protocols without demonstrated team or throughput pressure. Spring Modulith makes coupling visible and fails the build when module dependencies break its rules.

The tradeoff is one scaling and failure domain. Webhook handling uses Java 21 virtual threads; normalization and workers run asynchronously, but they share the same deployable and can be scaled only as a unit.

## Why compensation is explicit

A local rollback cannot undo a write accepted by Shopify or Square. Converge records each outbound attempt. After an ambiguous result, retry reads the remote position before writing again. A terminal failure opens an operator exception. When one target has succeeded and another fails, the engine appends a causally linked zero-delta correction and starts a fresh sync cycle to re-push the desired position. It does not claim to reverse a remote write.

That choice accepts temporary external inconsistency and operator work for irrecoverable cases. It avoids silent divergence and unbounded retries. See [reliability and observability](reliability.md) for the tested failure paths.
