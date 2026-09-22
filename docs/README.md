# Converge documentation

Choose the path that matches what you need to do. The structure separates learning, operations, exact reference material, and design rationale, following the [Diátaxis documentation framework](https://diataxis.fr/start-here/).

## Recommended reading path

1. [Run Converge locally](tutorials/quickstart.md) to see the console and create a first position.
2. [Architecture](explanation/architecture.md) and the [diagram gallery](diagrams/README.md) to locate each boundary.
3. [Convergence and replay](explanation/convergence.md) to understand the inventory guarantee.
4. [Reliability and observability](explanation/reliability.md) to see the failure behavior and evidence.
5. [Deploy to Fly.io](how-to/deploy-to-fly.md) when infrastructure and credentials are ready.

## By task

| Learn by doing | Operate and deploy |
| --- | --- |
| [Local quickstart](tutorials/quickstart.md) | [Rebuild a projection](how-to/rebuild-projection.md) |
| | [Deploy to Fly.io](how-to/deploy-to-fly.md) |

| Look up exact behavior | Understand why |
| --- | --- |
| [HTTP API](reference/api.md) | [Architecture decisions](explanation/architecture.md) |
| [Configuration](reference/configuration.md) | [Convergence and replay](explanation/convergence.md) |
| [Performance measurements](reference/performance.md) | [Reliability and observability](explanation/reliability.md) |
| [Diagram gallery](diagrams/README.md) | |

The diagrams are authored as accessible, checked-in SVGs. The build also generates code-derived Spring Modulith PlantUML documentation under `build/spring-modulith-docs/` when the test suite runs; those generated files are not a substitute for the curated system views.
