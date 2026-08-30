import { useEffect, useMemo, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";

type Screen = "overview" | "positions" | "queue" | "sources" | "detail";
type Position = { id: string; sku: string; location: string; ledger: number; shopify: number | null; square: number | null; warehouse: number | null; updated: string };
type QueueItem = { id: string; sku: string; location: string; ledger: number; observed: number; system: string; severity: "CRITICAL" | "WARNING"; type: string; detected: string };
type SourceHealth = { system: string; status: string; breakerState: string; lag: number; lastSync: string };
type EventItem = { seq: number; source: string; type: string; change: string; result: number; time: string };

const drift = [
  { time: "00:00", shopify: 0, square: 0 }, { time: "04:00", shopify: -2, square: 0 },
  { time: "08:00", shopify: -4, square: -1 }, { time: "12:00", shopify: -4, square: -3 },
  { time: "16:00", shopify: -1, square: -2 }, { time: "20:00", shopify: 0, square: 0 },
];

const samplePositions: Position[] = [
  { id: "1:10", sku: "TSH-CRM-M", location: "Main square", ledger: 142, shopify: 142, square: 139, warehouse: 142, updated: "40 sec ago" },
  { id: "2:20", sku: "HOD-NVY-L", location: "Online", ledger: 88, shopify: 84, square: 88, warehouse: 88, updated: "1 min ago" },
  { id: "3:30", sku: "CAP-BLK-OS", location: "Warehouse", ledger: 64, shopify: 64, square: 61, warehouse: 64, updated: "2 min ago" },
  { id: "4:10", sku: "SWT-GRY-S", location: "Main square", ledger: 26, shopify: 26, square: 26, warehouse: 26, updated: "45 sec ago" },
  { id: "5:20", sku: "JKT-OLV-XL", location: "Online", ledger: 17, shopify: 17, square: null, warehouse: 17, updated: "3 min ago" },
  { id: "6:30", sku: "BAG-TAN-OS", location: "Warehouse", ledger: 203, shopify: 203, square: 203, warehouse: 203, updated: "55 sec ago" },
];

const sampleQueue: QueueItem[] = [
  { id: "9fe4a410-39c7-4ba8-8157-11681b03a1d1", sku: "TSH-CRM-M", location: "Main square", ledger: 142, observed: 139, system: "Square", severity: "CRITICAL", type: "Persistent drift", detected: "12 min ago" },
  { id: "a7e69424-43f7-4878-a218-e32579eb4401", sku: "HOD-NVY-L", location: "Online", ledger: 88, observed: 84, system: "Shopify", severity: "WARNING", type: "Persistent drift", detected: "18 min ago" },
  { id: "b8860033-9b0a-4db2-8cb4-e32044d43a7a", sku: "CAP-BLK-OS", location: "Warehouse", ledger: 64, observed: 61, system: "Square", severity: "WARNING", type: "Count mismatch", detected: "31 min ago" },
];

const sampleSources: SourceHealth[] = [
  { system: "Shopify", status: "Connected", breakerState: "Closed", lag: 0, lastSync: "18 sec ago" },
  { system: "Square", status: "Connected", breakerState: "Closed", lag: 2, lastSync: "24 sec ago" },
  { system: "Warehouse CSV", status: "Feed current", breakerState: "Not applicable", lag: 0, lastSync: "Today, 06:00" },
];

const sampleHistory: EventItem[] = [
  { seq: 84012, source: "Warehouse", type: "Count", change: "142 absolute", result: 142, time: "Today, 06:00" },
  { seq: 84229, source: "Shopify", type: "Sale", change: "−2", result: 140, time: "Today, 09:42" },
  { seq: 84241, source: "Square", type: "Return", change: "+1", result: 141, time: "Today, 09:48" },
  { seq: 84290, source: "Shopify", type: "Restock", change: "+1", result: 142, time: "Today, 10:14" },
];

async function fetchJson<T>(url: string): Promise<T> {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`Request failed: ${response.status}`);
  return response.json() as Promise<T>;
}

export default function App() {
  const [screen, setScreen] = useState<Screen>("overview");
  const [selectedPosition, setSelectedPosition] = useState<Position | null>(null);
  const [queueIndex, setQueueIndex] = useState(0);
  const [filter, setFilter] = useState("");
  const [locationFilter, setLocationFilter] = useState("All locations");
  const [resolutionOpen, setResolutionOpen] = useState(false);
  const [resolutionQty, setResolutionQty] = useState("");
  const [settled, setSettled] = useState<Set<string>>(new Set());
  const qtyRef = useRef<HTMLInputElement>(null);
  const queryClient = useQueryClient();

  const positionsQuery = useQuery({
    queryKey: ["positions"],
    queryFn: async () => {
      const rows = await fetchJson<Array<{ canonicalSkuId: number; locationId: number; qty: number; updatedAt: string }>>("/api/positions");
      return rows.map((row) => ({ id: `${row.canonicalSkuId}:${row.locationId}`, sku: `SKU-${row.canonicalSkuId}`, location: `Location ${row.locationId}`, ledger: row.qty, shopify: null, square: null, warehouse: null, updated: new Date(row.updatedAt).toLocaleTimeString() }));
    },
    initialData: samplePositions,
  });
  const sourcesQuery = useQuery({
    queryKey: ["sources"],
    queryFn: async () => {
      const rows = await fetchJson<Array<{ system: string; status: string; breakerState: string; lag: number }>>("/api/connectors/health");
      return rows.map((row) => ({ ...row, system: titleCase(row.system), lastSync: "Live" }));
    },
    initialData: sampleSources,
  });

  const visibleQueue = sampleQueue.filter((item) => !settled.has(item.id));
  const currentQueue = visibleQueue[Math.min(queueIndex, Math.max(visibleQueue.length - 1, 0))];
  const filteredPositions = useMemo(() => positionsQuery.data.filter((position) =>
    (position.sku.toLowerCase().includes(filter.toLowerCase()) || position.location.toLowerCase().includes(filter.toLowerCase()))
    && (locationFilter === "All locations" || position.location === locationFilter)), [positionsQuery.data, filter, locationFilter]);

  useEffect(() => {
    if (screen !== "queue") return;
    const onKey = (event: KeyboardEvent) => {
      if (["INPUT", "TEXTAREA"].includes((event.target as HTMLElement).tagName)) return;
      if (event.key === "j") setQueueIndex((value) => Math.min(value + 1, visibleQueue.length - 1));
      if (event.key === "k") setQueueIndex((value) => Math.max(value - 1, 0));
      if (event.key === "r" && currentQueue) {
        setResolutionOpen(true); setResolutionQty(String(currentQueue.ledger));
        requestAnimationFrame(() => qtyRef.current?.focus());
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [screen, currentQueue, visibleQueue.length]);

  const navigate = (next: Screen) => { setScreen(next); setResolutionOpen(false); };
  const showDetail = (position: Position) => { setSelectedPosition(position); setScreen("detail"); };
  async function resolveCurrent(action: "ADJUST_TO" | "DISMISS") {
    if (!currentQueue) return;
    try {
      await fetch(`/api/exceptions/${currentQueue.id}/claim`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ actor: "ops-console" }) });
      await fetch(`/api/exceptions/${currentQueue.id}/resolve`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ action, qty: action === "ADJUST_TO" ? Number(resolutionQty) : null, note: "Resolved in operations console", actor: "ops-console" }) });
      await queryClient.invalidateQueries();
    } catch { /* Preview data remains interactive without the backend. */ }
    setSettled((items) => new Set(items).add(currentQueue.id));
    setQueueIndex(0); setResolutionOpen(false);
  }

  return <div className="app-shell"><Sidebar screen={screen} queueCount={visibleQueue.length} navigate={navigate}/><main>
    {screen === "overview" && <Overview positions={positionsQuery.data} queue={visibleQueue} sources={sourcesQuery.data} openQueue={() => navigate("queue")} showDetail={showDetail}/>} 
    {screen === "positions" && <Positions positions={filteredPositions} filter={filter} setFilter={setFilter} locationFilter={locationFilter} setLocationFilter={setLocationFilter} showDetail={showDetail}/>} 
    {screen === "queue" && <QueueScreen items={visibleQueue} current={currentQueue} index={queueIndex} setIndex={setQueueIndex} resolutionOpen={resolutionOpen} setResolutionOpen={setResolutionOpen} resolutionQty={resolutionQty} setResolutionQty={setResolutionQty} qtyRef={qtyRef} resolve={resolveCurrent}/>} 
    {screen === "sources" && <Sources sources={sourcesQuery.data}/>} 
    {screen === "detail" && selectedPosition && <Detail position={selectedPosition} back={() => navigate("positions")}/>} 
  </main></div>;
}

function Sidebar({ screen, queueCount, navigate }: { screen: Screen; queueCount: number; navigate: (screen: Screen) => void }) {
  const items: Array<[Screen, string]> = [["overview", "Overview"], ["positions", "Positions"], ["queue", "Queue"], ["sources", "Sources"]];
  return <aside className="sidebar"><div className="brand"><span className="brand-mark">C</span><span>Converge</span></div><nav aria-label="Primary">{items.map(([key, label]) => <button key={key} className={`nav-item ${screen === key || (screen === "detail" && key === "positions") ? "active" : ""}`} onClick={() => navigate(key)}>{label}{key === "queue" && <span className="nav-count">{queueCount}</span>}</button>)}</nav><div className="sidebar-status"><span className="status-dot"/>All sources connected</div></aside>;
}

function PageHeader({ context, title, aside }: { context: string; title: string; aside?: React.ReactNode }) {
  return <header className="page-header"><div><p className="context-label">{context}</p><h1>{title}</h1></div>{aside ?? <div className="last-check"><span className="status-dot"/>Last checked 40 seconds ago</div>}</header>;
}

function Overview({ positions, queue, sources, openQueue, showDetail }: { positions: Position[]; queue: QueueItem[]; sources: SourceHealth[]; openQueue: () => void; showDetail: (position: Position) => void }) {
  return <><PageHeader context="Inventory control" title="System overview"/><section className="summary-strip" aria-label="Inventory summary"><div><span>Open exceptions</span><strong>{queue.length}</strong><small>{queue.filter((item) => item.severity === "CRITICAL").length} critical</small></div><div><span>Tracked positions</span><strong>12,480</strong><small>Across 4 locations</small></div><div><span>Largest drift</span><strong className="critical-number">−12</strong><small>Square · TSH-CRM-M</small></div><div><span>Events today</span><strong>38,291</strong><small>99.98% processed</small></div></section>
    <section className="panel chart-panel"><div className="panel-heading"><div><h2>Drift across systems</h2><p>Difference from the canonical ledger</p></div><span>Last 24 hours</span></div><div className="chart-wrap"><ResponsiveContainer width="100%" height="100%"><AreaChart data={drift} margin={{ top: 8, right: 12, left: -24, bottom: 0 }}><defs><linearGradient id="shopify" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stopColor="#A51C30" stopOpacity={0.2}/><stop offset="1" stopColor="#A51C30" stopOpacity={0}/></linearGradient></defs><CartesianGrid stroke="#E3DEDF" vertical={false}/><XAxis dataKey="time" tickLine={false}/><YAxis tickLine={false} axisLine={false}/><Tooltip/><Area type="monotone" dataKey="shopify" stroke="#A51C30" fill="url(#shopify)" strokeWidth={2}/><Area type="monotone" dataKey="square" stroke="#B8760B" fill="transparent" strokeWidth={2}/></AreaChart></ResponsiveContainer></div></section>
    <section className="panel"><div className="panel-heading"><div><h2>Needs attention</h2><p>Persistent discrepancies, highest severity first</p></div><button className="text-action" onClick={openQueue}>Open queue →</button></div><div className="table-wrap"><PositionTable positions={positions.slice(0, 3)} showDetail={showDetail}/></div></section><section className="source-strip" aria-label="Connector status">{sources.map((source) => <div key={source.system}><span className="status-dot"/><strong>{source.system}</strong><span>{source.lastSync}</span></div>)}</section></>;
}

function Positions({ positions, filter, setFilter, locationFilter, setLocationFilter, showDetail }: { positions: Position[]; filter: string; setFilter: (value: string) => void; locationFilter: string; setLocationFilter: (value: string) => void; showDetail: (position: Position) => void }) {
  return <><PageHeader context="Canonical ledger" title="Inventory positions" aside={<div className="record-count number">{positions.length} shown</div>}/><div className="toolbar"><label>Find SKU or location<input value={filter} onChange={(event) => setFilter(event.target.value)} placeholder="TSH-CRM-M"/></label><label>Location<select value={locationFilter} onChange={(event) => setLocationFilter(event.target.value)}><option>All locations</option><option>Main square</option><option>Online</option><option>Warehouse</option></select></label><button className="secondary-button">Export CSV</button></div><div className="table-wrap positions-table"><PositionTable positions={positions} showDetail={showDetail}/></div>{positions.length === 0 && <div className="empty-state"><strong>No positions match these filters.</strong><span>Clear the SKU or location filter to continue.</span></div>}</>;
}

function PositionTable({ positions, showDetail }: { positions: Position[]; showDetail: (position: Position) => void }) {
  return <table><thead><tr><th>SKU</th><th>Location</th><th className="number">Ledger</th><th className="number">Shopify</th><th className="number">Square</th><th className="number">Warehouse</th><th>Updated</th></tr></thead><tbody>{positions.map((row) => <tr key={row.id}><td><button className="sku-link" onClick={() => showDetail(row)}>{row.sku}</button></td><td>{row.location}</td><Quantity value={row.ledger}/><Quantity value={row.shopify} drift={row.shopify !== null && row.shopify !== row.ledger}/><Quantity value={row.square} drift={row.square !== null && row.square !== row.ledger}/><Quantity value={row.warehouse} drift={row.warehouse !== null && row.warehouse !== row.ledger}/><td className="muted-cell">{row.updated}</td></tr>)}</tbody></table>;
}

function Quantity({ value, drift: deviates = false }: { value: number | null; drift?: boolean }) { return <td className={`number ${deviates ? "drift-cell" : ""}`}>{value ?? "—"}</td>; }

function QueueScreen({ items, current, index, setIndex, resolutionOpen, setResolutionOpen, resolutionQty, setResolutionQty, qtyRef, resolve }: { items: QueueItem[]; current?: QueueItem; index: number; setIndex: (value: number) => void; resolutionOpen: boolean; setResolutionOpen: (value: boolean) => void; resolutionQty: string; setResolutionQty: (value: string) => void; qtyRef: React.RefObject<HTMLInputElement | null>; resolve: (action: "ADJUST_TO" | "DISMISS") => void }) {
  if (!current) return <><PageHeader context="Exception triage" title="Queue"/><div className="empty-state large"><strong>No open exceptions.</strong><span>Drift last checked 40 seconds ago.</span></div></>;
  return <><PageHeader context="Exception triage" title="Resolve discrepancies" aside={<div className="keyboard-help"><kbd>j</kbd><kbd>k</kbd> move <kbd>r</kbd> resolve</div>}/><div className="triage-layout"><aside className="queue-list" aria-label="Open exceptions">{items.map((item, itemIndex) => <button key={item.id} className={`queue-row ${index === itemIndex ? "selected" : ""}`} onClick={() => { setIndex(itemIndex); setResolutionOpen(false); }}><span className={`severity-bar ${item.severity.toLowerCase()}`}/><span><strong>{item.sku}</strong><small>{item.location} · {item.system}</small></span><span className="number">{signed(item.observed - item.ledger)}</span></button>)}</aside><article className="case-file"><div className="case-heading"><div><span className={`severity ${current.severity.toLowerCase()}`}>{titleCase(current.severity)}</span><h2>{current.sku} · {current.location}</h2><p>{current.type} detected {current.detected}</p></div><button className="primary-button" onClick={() => { setResolutionOpen(true); setResolutionQty(String(current.ledger)); requestAnimationFrame(() => qtyRef.current?.focus()); }}>Resolve</button></div><div className="quantity-comparison"><div><span>Canonical ledger</span><strong>{current.ledger}</strong></div><div><span>{current.system} reports</span><strong className="critical-number">{current.observed}</strong></div><div><span>Difference</span><strong>{signed(current.observed - current.ledger)}</strong></div></div><h3>Event timeline</h3><Timeline events={sampleHistory}/>{resolutionOpen && <div className="resolution-panel"><h3>Resolve this exception</h3><p>Append an adjustment to the ledger, or dismiss this as expected lag. Existing events are never changed.</p><label>Final quantity<input ref={qtyRef} className="number-input" type="number" value={resolutionQty} onChange={(event) => setResolutionQty(event.target.value)}/></label><label>Resolution note<textarea defaultValue="Verified against the physical count."/></label><div className="resolution-actions"><button className="secondary-button" onClick={() => resolve("DISMISS")}>Dismiss as expected</button><button className="primary-button" onClick={() => resolve("ADJUST_TO")}>Append adjustment</button></div></div>}</article></div></>;
}

function Detail({ position, back }: { position: Position; back: () => void }) {
  return <><PageHeader context="Position audit trail" title={position.sku} aside={<button className="text-action" onClick={back}>← Back to positions</button>}/><div className="detail-meta"><div><span>Location</span><strong>{position.location}</strong></div><div><span>Current position</span><strong className="number">{position.ledger}</strong></div><div><span>Last event</span><strong>{position.updated}</strong></div><div><span>Anchor</span><strong>Physical count</strong></div></div><section className="panel"><div className="panel-heading"><div><h2>Event history</h2><p>Every change in source time order; sequence preserves receipt order</p></div><button className="secondary-button">Force sync</button></div><Timeline events={sampleHistory}/></section></>;
}

function Timeline({ events }: { events: EventItem[] }) { return <div className="timeline">{events.map((event) => <div className="timeline-row" key={event.seq}><span className="timeline-dot"/><div><strong>{event.type}</strong><span>{event.source}</span></div><div className="number event-change">{event.change}</div><div><span>Result</span><strong className="number">{event.result}</strong></div><time>{event.time}</time></div>)}</div>; }

function Sources({ sources }: { sources: SourceHealth[] }) {
  return <><PageHeader context="Connector operations" title="Sources"/><div className="source-list">{sources.map((source) => <article key={source.system}><div className="source-name"><span className="status-dot"/><div><h2>{source.system}</h2><p>{source.status}</p></div></div><dl><div><dt>Breaker</dt><dd>{source.breakerState}</dd></div><div><dt>Queue lag</dt><dd className="number">{source.lag}</dd></div><div><dt>Last sync</dt><dd>{source.lastSync}</dd></div></dl><button className="secondary-button">Check now</button></article>)}</div><div className="instruction-note"><strong>When a connector is unavailable</strong><span>Writes stay queued. They resume automatically when its circuit breaker enters half-open state.</span></div></>;
}

function titleCase(value: string) { return value.charAt(0).toUpperCase() + value.slice(1).toLowerCase(); }
function signed(value: number) { return value > 0 ? `+${value}` : String(value).replace("-", "−"); }

