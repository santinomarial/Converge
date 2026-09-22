"""Render the source-controlled Converge architecture diagrams as accessible SVG.

Run: python3 docs/diagrams/render.py
Only Python's standard library is required. The SVGs are committed so GitHub
readers do not need a diagram renderer or this script to view the docs.
"""

from html import escape
from math import atan2, cos, sin
from pathlib import Path

OUT = Path(__file__).parent
W, H = 1440, 900
NAVY = "#152B52"
BLUE = "#1746A2"
TEAL = "#178277"
AMBER = "#A66514"
RED = "#A51C30"
PURPLE = "#6946A0"
INK = "#1D2D47"
MUTED = "#5C6B80"
RULE = "#CBD5E4"
PALE = {"core": "#F1F5FD", "data": "#EEF8F6", "event": "#F6F1FB",
        "control": "#FFF7E9", "alert": "#FDF0F1", "external": "#F8FAFD"}
ACCENT = {"core": BLUE, "data": TEAL, "event": PURPLE,
          "control": AMBER, "alert": RED, "external": MUTED}


class Diagram:
    def __init__(self, number: str, title: str, subtitle: str, description: str):
        self.parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" '
                      f'viewBox="0 0 {W} {H}" role="img" aria-labelledby="title description">',
                      f'<title id="title">{escape(title)}</title>',
                      f'<desc id="description">{escape(description)}</desc>',
                      '<defs><marker id="arrow" markerWidth="12" markerHeight="12" '
                      'refX="10" refY="6" orient="auto"><path d="M1 1 L11 6 L1 11" '
                      f'fill="none" stroke="{BLUE}" stroke-width="2"/></marker>'
                      '<marker id="arrow-muted" markerWidth="12" markerHeight="12" '
                      'refX="10" refY="6" orient="auto"><path d="M1 1 L11 6 L1 11" '
                      f'fill="none" stroke="{MUTED}" stroke-width="2"/></marker></defs>']
        self.rect(0, 0, W, H, "#FFFFFF")
        self.rect(32, 32, W - 64, H - 64, "#FFFFFF", RULE, 2, 16)
        self.rect(52, 52, 82, 92, NAVY, radius=10)
        self.text(93, 108, [number], 38, "#FFFFFF", 800, "middle")
        self.text(158, 91, [title], 34, NAVY, 800)
        self.text(158, 126, [subtitle], 18, MUTED)
        self.line(52, 162, 1388, 162, RULE, 2)

    def rect(self, x, y, w, h, fill, stroke="none", sw=1, radius=0):
        self.parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{radius}" '
                          f'fill="{fill}" stroke="{stroke}" stroke-width="{sw}"/>')

    def text(self, x, y, lines, size=18, color=INK, weight=400, anchor="start", gap=None):
        gap = gap or int(size * 1.35)
        for index, line in enumerate(lines):
            self.parts.append(f'<text x="{x}" y="{y + index * gap}" text-anchor="{anchor}" '
                              f'font-family="Inter, Arial, sans-serif" font-size="{size}" '
                              f'font-weight="{weight}" fill="{color}">{escape(str(line))}</text>')

    def arrowhead(self, x1, y1, x2, y2, color):
        angle = atan2(y2 - y1, x2 - x1)
        points = [(x2, y2),
                  (x2 - 13 * cos(angle) + 6 * sin(angle), y2 - 13 * sin(angle) - 6 * cos(angle)),
                  (x2 - 13 * cos(angle) - 6 * sin(angle), y2 - 13 * sin(angle) + 6 * cos(angle))]
        coords = " ".join(f"{x:.1f},{y:.1f}" for x, y in points)
        self.parts.append(f'<polygon points="{coords}" fill="{color}"/>')

    def line(self, x1, y1, x2, y2, color=BLUE, width=3, dashed=False, arrow=False):
        dash = ' stroke-dasharray="8 7"' if dashed else ''
        self.parts.append(f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" '
                          f'stroke="{color}" stroke-width="{width}"{dash}/>')
        if arrow:
            self.arrowhead(x1, y1, x2, y2, color)

    def path(self, points, color=BLUE, width=3, dashed=False, arrow=True):
        d = "M " + " L ".join(f"{x} {y}" for x, y in points)
        dash = ' stroke-dasharray="8 7"' if dashed else ''
        self.parts.append(f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{width}" '
                          f'stroke-linejoin="round"{dash}/>')
        if arrow:
            self.arrowhead(*points[-2], *points[-1], color)

    def section(self, x, y, w, label):
        self.rect(x, y, w, 40, BLUE, radius=6)
        self.text(x + 16, y + 27, [label.upper()], 17, "#FFFFFF", 700)

    def card(self, x, y, w, h, title, body, kind="core", tag=None):
        accent = ACCENT[kind]
        self.rect(x, y, w, h, PALE[kind], RULE, 1.6, 11)
        self.rect(x, y, 8, h, accent, radius=4)
        self.text(x + 24, y + 34, [title], 21, NAVY, 700)
        self.text(x + 24, y + 64, body, 16, INK, gap=22)
        if tag:
            self.rect(x + w - 65, y + 14, 48, 25, accent, radius=12)
            self.text(x + w - 41, y + 32, [tag], 14, "#FFFFFF", 700, "middle")

    def label(self, x, y, value, color=BLUE):
        self.rect(x - 8, y - 18, len(value) * 9 + 16, 27, "#FFFFFF")
        self.text(x, y, [value], 15, color, 700)

    def footer(self, note: str, keys=("core", "data", "event", "control", "alert")):
        self.line(52, 788, 1388, 788, RULE, 2)
        self.text(68, 819, ["KEY"], 15, NAVY, 800)
        labels = {"core": "Application / core", "data": "Durable data", "event": "Event transport",
                  "control": "Control / review", "alert": "Failure / exception", "external": "External system"}
        x = 128
        for key in keys:
            self.rect(x, 802, 18, 18, ACCENT[key], radius=3)
            self.text(x + 27, 817, [labels[key]], 14, INK)
            x += len(labels[key]) * 8 + 58
        self.text(68, 855, [note], 15, MUTED)

    def save(self, filename):
        self.parts.append('</svg>')
        (OUT / filename).write_text("\n".join(self.parts) + "\n", encoding="utf-8")


def system_context():
    d = Diagram("01", "System context", "Who sends facts, who acts on disagreement, and where repairs go",
                "Shopify, Square, and a warehouse feed provide inventory facts to Converge. Operators use the console. Converge writes desired positions back to mapped commerce systems.")
    d.section(68, 190, 350, "External inventory sources")
    d.section(510, 190, 420, "Converge boundary")
    d.section(1022, 190, 350, "Operations")
    d.line(418, 299, 500, 299, arrow=True)
    d.line(500, 331, 418, 331, arrow=True)
    d.line(418, 450, 500, 450, arrow=True)
    d.line(500, 482, 418, 482, arrow=True)
    d.line(418, 600, 500, 600, arrow=True)
    d.line(940, 355, 1012, 355, arrow=True)
    d.line(1012, 390, 940, 390, arrow=True)
    d.card(68, 248, 350, 116, "Shopify", ["Signed webhooks in", "Position updates out"], "external")
    d.card(68, 400, 350, 116, "Square", ["Signed webhooks in", "Position updates out"], "external")
    d.card(68, 552, 350, 108, "Warehouse CSV", ["Physical counts and feed rows in"], "external")
    d.card(510, 248, 420, 412, "Inventory control engine",
           ["One canonical SKU/location position", "Append-only event history", "Persistent drift and exception queue", "Auditable outbound repair"], "core", "ONE")
    d.card(1022, 292, 350, 140, "Operations console", ["Inspect positions and history", "Claim and resolve exceptions"], "control")
    d.card(1022, 504, 350, 108, "Operator", ["Decides when to adjust or dismiss"], "control")
    d.line(1197, 494, 1197, 442, arrow=True)
    d.label(434, 277, "facts")
    d.label(431, 363, "sync")
    d.footer("The warehouse is an inbound feed; Shopify and Square are both sources and sync targets.",
             ("external", "core", "control"))
    d.save("01-system-context.svg")


def runtime_containers():
    d = Diagram("02", "Runtime and containers", "One deployable modular monolith with durable infrastructure",
                "The Spring Boot application serves the React console and API and runs asynchronous workers. PostgreSQL stores facts and work, Kafka transports messages, Redis supports rate limits, and telemetry flows to observability services.")
    d.section(66, 186, 250, "Edge")
    d.section(354, 186, 670, "Converge · one process")
    d.section(1062, 186, 310, "State & transport")
    d.line(316, 307, 344, 307, arrow=True)
    d.line(316, 460, 344, 460, arrow=True)
    d.line(1024, 300, 1052, 300, arrow=True)
    d.line(1024, 439, 1052, 439, arrow=True)
    d.line(1024, 575, 1052, 575, arrow=True)
    d.card(66, 245, 250, 130, "Commerce APIs", ["Shopify + Square", "webhooks and sync"], "external")
    d.card(66, 411, 250, 112, "Warehouse feed", ["CSV counts"], "external")
    d.card(354, 245, 670, 118, "HTTP edge + React console",
           ["Webhook verification, operator API, live read model"], "core")
    d.card(354, 382, 670, 118, "Domain modules",
           ["Identity · ledger · reconciliation · exceptions"], "core")
    d.card(354, 519, 670, 118, "Background workers",
           ["Kafka consumers · shadow verifier · outbox relay · sync saga"], "core")
    d.card(1062, 245, 310, 110, "PostgreSQL 16", ["Log, projection, outbox", "attempts, exceptions"], "data")
    d.card(1062, 382, 310, 110, "Kafka / Redpanda", ["Raw and position-change", "topics"], "event")
    d.card(1062, 519, 310, 110, "Redis", ["Deduplication hint", "and token buckets"], "data")
    d.section(354, 685, 1018, "Observability · Prometheus metrics · OTLP traces · Grafana dashboard")
    d.path([(690, 647), (690, 675)], dashed=True, color=MUTED)
    d.footer("Module boundaries are build-verified; the workers share the same deployable and transaction boundary.",
             ("external", "core", "data", "event"))
    d.save("02-runtime-containers.svg")


def event_journey():
    d = Diagram("03", "Inventory event journey", "From a signed webhook to a durable position and outbound work",
                "Webhooks are verified and stored before acknowledgment. Kafka triggers normalization, identity mapping, atomic ledger projection and outbox creation, then outbound sync attempts.")
    d.section(62, 188, 1316, "Primary flow · durable capture before acknowledgement")
    xs = [62, 332, 602, 872, 1142]
    for x in xs[:-1]:
        d.line(x + 236, 326, x + 260, 326, arrow=True)
    d.card(xs[0], 252, 236, 175, "01 · Receive", ["Verify HMAC", "Shopify / Square", "webhook"], "external")
    d.card(xs[1], 252, 236, 175, "02 · Capture", ["Commit raw_webhook", "in PostgreSQL", "then return 200"], "data")
    d.card(xs[2], 252, 236, 175, "03 · Normalize", ["inventory.raw", "Kafka consumer", "resolves identity"], "event")
    d.card(xs[3], 252, 236, 175, "04 · Project", ["Append event, update", "position and outbox", "in one transaction"], "core")
    d.card(xs[4], 252, 236, 175, "05 · Sync", ["Relay change event", "plan attempts, push", "mapped targets"], "core")
    d.section(62, 487, 1316, "Visible branches · never silently invent inventory")
    d.card(62, 550, 395, 128, "Duplicate delivery", ["PostgreSQL uniqueness keeps one", "raw record and one ledger fact."], "control")
    d.card(522, 550, 395, 128, "Missing mapping", ["Quarantine the payload; an", "operator resolves the identity."], "control")
    d.card(982, 550, 396, 128, "Persistent drift", ["Second non-zero observation", "opens an exception."], "alert")
    d.footer("The source event is preserved; replay uses the same append-only log as the live projection.",
             ("external", "data", "event", "core", "control", "alert"))
    d.save("03-event-journey.svg")


def convergence():
    d = Diagram("04", "Projection and convergence", "Fast incremental writes, independent full-history verification",
                "A winning source-time snapshot anchors the position. Only later deltas count. Earlier late arrivals are retained as absorbed. The incremental checkpoint is compared to an independent full replay; repair is explicit.")
    d.section(62, 186, 1316, "One SKU / location · arrival order, source-time semantics")
    d.card(62, 252, 284, 130, "Snapshot · 100", ["Latest (occurred_at, seq)", "anchor wins"], "data")
    d.card(396, 252, 284, 130, "Delta · −3", ["After anchor", "included"], "core")
    d.card(730, 252, 284, 130, "Late delta · +10", ["Before anchor", "retained, absorbed"], "control")
    d.card(1064, 252, 284, 130, "Delta · +2", ["After anchor", "included"], "core")
    d.line(346, 318, 386, 318, arrow=True)
    d.line(680, 318, 720, 318, arrow=True)
    d.line(1014, 318, 1054, 318, arrow=True)
    d.rect(278, 430, 884, 102, "#F1F5FD", BLUE, 2, 12)
    d.text(720, 471, ["100 − 3 + 2 = 99"], 34, NAVY, 800, "middle")
    d.text(720, 505, ["The absorbed +10 remains auditable but does not change the position."], 17, MUTED, 400, "middle")
    d.section(62, 580, 1316, "Two reducers, one invariant")
    d.card(62, 638, 390, 112, "Incremental checkpoint", ["Ordinary writes update qty and", "last_applied_seq atomically."], "core")
    d.card(524, 638, 390, 112, "Shadow full reducer", ["Rotating batches recompute from", "the complete event history."], "data")
    d.card(986, 638, 392, 112, "Mismatch → explicit replay", ["Metric + error log surface drift;", "operator rebuilds projection."], "alert")
    d.line(452, 694, 514, 694, arrow=True)
    d.line(914, 694, 976, 694, dashed=True, arrow=True)
    d.footer("No automatic mutation follows a shadow mismatch; replay is an explicit administrative operation.",
             ("core", "data", "control", "alert"))
    d.save("04-projection-convergence.svg")


def sync_recovery():
    d = Diagram("05", "Outbound sync and recovery", "A remote write cannot be rolled back by a local transaction",
                "The transactional outbox creates durable sync attempts. Workers rate-limit and circuit-break calls. Retry probes remote state after ambiguity. Terminal failures open exceptions; partial success starts a causally linked compensation cycle.")
    d.section(62, 186, 1316, "Normal path")
    xs = [62, 340, 618, 896, 1174]
    for x in xs[:-1]:
        d.line(x + 230, 311, x + 268, 311, arrow=True)
    d.card(62, 246, 230, 127, "Outbox", ["Committed with", "ledger position"], "data")
    d.card(340, 246, 230, 127, "Kafka relay", ["Publish position", "change"], "event")
    d.card(618, 246, 230, 127, "Sync planner", ["One durable attempt", "per mapped target"], "core")
    d.card(896, 246, 230, 127, "Worker", ["Rate limit, breaker", "and retry policy"], "core")
    d.card(1174, 246, 204, 127, "Remote API", ["Shopify or", "Square write"], "external")
    d.section(62, 440, 1316, "Outcomes and recovery")
    d.card(62, 506, 390, 124, "Confirmed success", ["Mark attempt SUCCEEDED;", "do not write again."], "data")
    d.card(524, 506, 390, 124, "Transient / ambiguous result", ["Requeue with backoff. Retry", "reads remote state first."], "control")
    d.card(986, 506, 392, 124, "Terminal failure", ["Mark FAILED and open a", "critical operator exception."], "alert")
    d.path([(1098, 640), (1098, 697), (968, 697)], dashed=True, color=MUTED)
    d.card(524, 672, 444, 94, "Partial success → compensation", ["Append zero-delta correction; start fresh sync cycle."], "control")
    d.footer("Compensation records intent and re-pushes; it does not pretend to undo an accepted remote write.",
             ("data", "event", "core", "external", "control", "alert"))
    d.save("05-sync-recovery.svg")


def deployment():
    d = Diagram("06", "Production topology", "Deployment target · infrastructure and secrets still need provisioning",
                "A continuously running Fly machine serves the console and API and runs workers. Managed PostgreSQL, Kafka and Redis are required. Commerce platforms call webhooks and receive outbound syncs. Health checks and metrics support operations.")
    d.section(62, 186, 300, "Commerce platforms")
    d.section(426, 186, 590, "Fly.io · one always-on machine")
    d.section(1080, 186, 298, "Managed services")
    d.card(62, 246, 300, 128, "Shopify + Square", ["HTTPS webhooks in", "HTTPS inventory writes out"], "external")
    d.card(62, 432, 300, 112, "Operators", ["HTTPS console and API"], "control")
    d.card(426, 246, 590, 298, "Converge Spring Boot + React",
           ["HTTP edge and live operations console", "Kafka consumers, projector, drift poller", "outbox relay, sync worker, shadow verifier", "Production profile + required secrets"], "core")
    d.card(1080, 246, 298, 96, "PostgreSQL", ["Durable facts and work"], "data")
    d.card(1080, 360, 298, 96, "Kafka", ["Asynchronous transport"], "event")
    d.card(1080, 474, 298, 96, "Redis", ["Rate-limit tokens"], "data")
    d.line(362, 310, 416, 310, arrow=True)
    d.line(416, 340, 362, 340, arrow=True)
    d.line(362, 482, 416, 482, arrow=True)
    d.line(1016, 296, 1070, 296, arrow=True)
    d.line(1016, 410, 1070, 410, arrow=True)
    d.line(1016, 524, 1070, 524, arrow=True)
    d.section(426, 630, 952, "Operational signals")
    d.card(426, 690, 290, 76, "Readiness / liveness", ["Fly health probes"], "control")
    d.card(745, 690, 290, 76, "Prometheus", ["Metrics endpoint"], "control")
    d.card(1064, 690, 314, 76, "OTLP / Grafana", ["Traces and dashboards"], "control")
    d.footer("This is the intended production topology, not a claim that the Fly app or managed services are live.",
             ("external", "core", "data", "event", "control"))
    d.save("06-production-topology.svg")


if __name__ == "__main__":
    system_context()
    runtime_containers()
    event_journey()
    convergence()
    sync_recovery()
    deployment()
    print("Rendered 6 diagrams in", OUT)
