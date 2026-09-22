import { useEffect, useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";

type Screen = "overview" | "positions" | "queue" | "sources" | "detail";
type Position = { canonicalSkuId: number; locationId: number; sku: string; location: string; ledger: number; shopify: number | null; square: number | null; warehouse: number | null; updatedAt: string };
type QueueItem = { id: string; canonicalSkuId: number; locationId: number; ledgerQty: number; observed: number | null; targetSystem: string | null; severity: string; type: string; detectedAt: string; state: string };
type SourceHealth = { system: string; status: string; breakerState: string; lag: number; lastSync: string | null };
type EventItem = { seq: number; sourceSystem: string; eventType: string; kind: string; qtyDelta: number | null; qtyAbsolute: number | null; occurredAt: string; absorbed: boolean };
type DriftSample = { system: string; drift: number; sampledAt: string };
type Summary = { eventsToday: number; locations: number };

class HttpError extends Error { constructor(public status: number, message: string) { super(message); } }
async function requestJson<T>(url: string, auth: string | null, init?: RequestInit): Promise<T> {
  const method = init?.method?.toUpperCase() ?? "GET";
  const response = await fetch(url, { ...init, headers: { ...(init?.headers ?? {}), ...(auth ? { Authorization: `Basic ${auth}` } : {}), ...(method !== "GET" && url.startsWith("/api/") ? { "X-Converge-Request": "console" } : {}) } });
  if (!response.ok) throw new HttpError(response.status, `Request failed (${response.status})`);
  return response.json() as Promise<T>;
}
function relativeTime(value: string | null): string {
  if (!value) return "Never";
  const seconds = Math.max(0, Math.round((Date.now() - new Date(value).getTime()) / 1000));
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
  return new Date(value).toLocaleString();
}
function titleCase(value: string) { return value.charAt(0).toUpperCase() + value.slice(1).toLowerCase().replaceAll("_", " "); }
function signed(value: number) { return value > 0 ? `+${value}` : String(value).replace("-", "−"); }
function exportCsv(rows: Position[]) {
  const columns: Array<keyof Position> = ["sku", "location", "ledger", "shopify", "square", "warehouse", "updatedAt"];
  const cell = (value: string | number | null) => `"${String(value ?? "").replaceAll('"', '""')}"`;
  const csv = [columns.join(","), ...rows.map((row) => columns.map((column) => cell(row[column])).join(","))].join("\r\n");
  const url = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
  const link = document.createElement("a"); link.href = url; link.download = "converge-positions.csv"; link.click();
  URL.revokeObjectURL(url);
}

export default function App() {
  const [screen, setScreen] = useState<Screen>("overview");
  const [selected, setSelected] = useState<Position | null>(null);
  const [auth, setAuth] = useState<string | null>(null);
  const [operator, setOperator] = useState("ops-console");
  const [filter, setFilter] = useState("");
  const [locationFilter, setLocationFilter] = useState("All locations");
  const [queueIndex, setQueueIndex] = useState(0);
  const [resolutionOpen, setResolutionOpen] = useState(false);
  const [resolutionQty, setResolutionQty] = useState("");
  const [resolutionNote, setResolutionNote] = useState("");
  const [actionError, setActionError] = useState("");
  const [actionMessage, setActionMessage] = useState("");
  const client = useQueryClient();
  const positionsQuery = useQuery({ queryKey: ["positions", auth], queryFn: () => requestJson<Position[]>("/api/operations/positions", auth), retry: false, refetchInterval: 15000 });
  const queueQuery = useQuery({ queryKey: ["queue", auth], queryFn: () => requestJson<QueueItem[]>("/api/exceptions", auth), retry: false, refetchInterval: 15000 });
  const sourcesQuery = useQuery({ queryKey: ["sources", auth], queryFn: () => requestJson<SourceHealth[]>("/api/connectors/health", auth), retry: false, refetchInterval: 15000 });
  const summaryQuery = useQuery({ queryKey: ["summary", auth], queryFn: () => requestJson<Summary>("/api/operations/summary", auth), retry: false, refetchInterval: 15000 });
  const driftQuery = useQuery({ queryKey: ["drift", auth], queryFn: () => requestJson<DriftSample[]>("/api/drift?window=PT24H", auth), retry: false, refetchInterval: 30000 });
  const positions = positionsQuery.data ?? [];
  const queue = (queueQuery.data ?? []).filter((item) => item.state === "OPEN" || item.state === "CLAIMED");
  const sources = sourcesQuery.data ?? [];
  const current = queue[Math.min(queueIndex, Math.max(0, queue.length - 1))];
  const selectedKey = selected && `${selected.canonicalSkuId}:${selected.locationId}`;
  const positionByKey = useMemo(() => new Map(positions.map((row) => [`${row.canonicalSkuId}:${row.locationId}`, row])), [positions]);
  const visible = positions.filter((row) => (row.sku.toLowerCase().includes(filter.toLowerCase()) || row.location.toLowerCase().includes(filter.toLowerCase())) && (locationFilter === "All locations" || row.location === locationFilter));
  const historyKey = screen === "queue" && current ? current : screen === "detail" ? selected : null;
  const historyQuery = useQuery({ queryKey: ["history", historyKey?.canonicalSkuId, historyKey?.locationId, auth],
    queryFn: () => requestJson<EventItem[]>(`/api/positions/${historyKey!.canonicalSkuId}/${historyKey!.locationId}/history`, auth), enabled: Boolean(historyKey), retry: false });
  const queries = [positionsQuery, queueQuery, sourcesQuery, summaryQuery, driftQuery];
  const unauthorized = queries.some((query) => query.error instanceof HttpError && query.error.status === 401);
  const dataError = queries.find((query) => query.error && !(query.error instanceof HttpError && query.error.status === 401))?.error;

  useEffect(() => {
    if (screen !== "queue") return;
    const onKey = (event: KeyboardEvent) => {
      if (["INPUT", "TEXTAREA"].includes((event.target as HTMLElement).tagName)) return;
      if (event.key === "j") setQueueIndex((index) => Math.min(index + 1, Math.max(0, queue.length - 1)));
      if (event.key === "k") setQueueIndex((index) => Math.max(index - 1, 0));
      if (event.key === "r" && current) { setResolutionOpen(true); setResolutionQty(String(current.ledgerQty)); }
    };
    window.addEventListener("keydown", onKey); return () => window.removeEventListener("keydown", onKey);
  }, [screen, queue.length, current]);

  async function resolve(action: "ADJUST_TO" | "DISMISS") {
    if (!current) return;
    if (action === "ADJUST_TO" && (resolutionQty.trim() === "" || !Number.isInteger(Number(resolutionQty)))) { setActionError("Enter a whole-number final quantity."); return; }
    setActionError("");
    try {
      if (current.state === "OPEN") await requestJson(`/api/exceptions/${current.id}/claim`, auth, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ actor: operator }) });
      await requestJson(`/api/exceptions/${current.id}/resolve`, auth, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ action, qty: action === "ADJUST_TO" ? Number(resolutionQty) : null, note: resolutionNote, actor: operator }) });
      await client.invalidateQueries(); setQueueIndex(0); setResolutionOpen(false); setResolutionNote("");
    } catch (error) { setActionError(error instanceof Error ? error.message : "Resolution failed"); }
  }
  async function forceSync(position: Position) {
    setActionMessage("");
    try { const result = await requestJson<{ targetsQueued: number }>(`/api/sync/${position.canonicalSkuId}/${position.locationId}`, auth, { method: "POST" }); setActionMessage(`${result.targetsQueued} sync target(s) queued.`); }
    catch (error) { setActionMessage(error instanceof Error ? error.message : "Sync request failed"); }
  }
  const navigate = (next: Screen) => { setScreen(next); setResolutionOpen(false); setActionError(""); setActionMessage(""); };
  if (unauthorized) return <SignIn error={auth ? "Invalid credentials. Try again." : "Sign in to load live operations data."} onSignIn={(username, password) => { setOperator(username); setAuth(btoa(`${username}:${password}`)); }}/>;
  const healthy = sources.length > 0 && sources.every((source) => source.status === "READY" && source.lag === 0);
  const latestSelected = selectedKey ? positionByKey.get(selectedKey) ?? selected : null;

  return <div className="app-shell"><aside className="sidebar"><div className="brand"><span className="brand-mark">C</span><span>Converge</span></div><nav aria-label="Primary">{([["overview", "Overview"], ["positions", "Positions"], ["queue", "Queue"], ["sources", "Sources"]] as Array<[Screen, string]>).map(([key, label]) => <button key={key} className={`nav-item ${screen === key || (screen === "detail" && key === "positions") ? "active" : ""}`} onClick={() => navigate(key)}>{label}{key === "queue" && <span className="nav-count">{queue.length}</span>}</button>)}</nav><div className="sidebar-status"><span className={`status-dot ${healthy ? "" : "status-warn"}`}/>{healthy ? "Workers ready" : "Check source status"}</div></aside><main>
    {dataError && <div className="error-banner" role="alert">Live data unavailable: {dataError.message} <button onClick={() => client.invalidateQueries()}>Retry</button></div>}
    {screen === "overview" && <Overview positions={positions} queue={queue} sources={sources} summary={summaryQuery.data} drift={driftQuery.data ?? []} positionByKey={positionByKey} openQueue={() => navigate("queue")} showDetail={(row) => { setSelected(row); navigate("detail"); }}/>}
    {screen === "positions" && <><PageHeader context="Canonical ledger" title="Inventory positions" aside={<div className="record-count number">{visible.length} shown</div>}/><div className="toolbar"><label>Find SKU or location<input value={filter} onChange={(event) => setFilter(event.target.value)} placeholder="Search positions"/></label><label>Location<select value={locationFilter} onChange={(event) => setLocationFilter(event.target.value)}><option>All locations</option>{[...new Set(positions.map((row) => row.location))].sort().map((location) => <option key={location}>{location}</option>)}</select></label><button className="secondary-button" onClick={() => exportCsv(visible)} disabled={visible.length === 0}>Export CSV</button></div><div className="table-wrap positions-table"><PositionTable positions={visible} showDetail={(row) => { setSelected(row); navigate("detail"); }}/></div>{visible.length === 0 && <div className="empty-state"><strong>No positions found.</strong><span>Clear filters or ingest inventory events.</span></div>}</>}
    {screen === "queue" && <><PageHeader context="Exception triage" title="Resolve discrepancies" aside={<div className="keyboard-help"><kbd>j</kbd><kbd>k</kbd> move <kbd>r</kbd> resolve</div>}/>{!current ? <div className="empty-state large"><strong>No open exceptions.</strong><span>New persistent discrepancies will appear here.</span></div> : <div className="triage-layout"><aside className="queue-list" aria-label="Open exceptions">{queue.map((item, index) => { const row = positionByKey.get(`${item.canonicalSkuId}:${item.locationId}`); return <button key={item.id} className={`queue-row ${queueIndex === index ? "selected" : ""}`} onClick={() => { setQueueIndex(index); setResolutionOpen(false); }}><span className={`severity-bar ${item.severity.toLowerCase()}`}/><span><strong>{row?.sku ?? `SKU ${item.canonicalSkuId}`}</strong><small>{row?.location ?? `Location ${item.locationId}`} · {titleCase(item.targetSystem ?? "unknown")}</small></span><span className="number">{item.observed === null ? "—" : signed(item.observed - item.ledgerQty)}</span></button>; })}</aside><article className="case-file"><div className="case-heading"><div><span className={`severity ${current.severity.toLowerCase()}`}>{titleCase(current.severity)}</span><h2>{positionByKey.get(`${current.canonicalSkuId}:${current.locationId}`)?.sku ?? `SKU ${current.canonicalSkuId}`}</h2><p>{current.type} detected {relativeTime(current.detectedAt)}</p></div><button className="primary-button" onClick={() => { setResolutionOpen(true); setResolutionQty(String(current.ledgerQty)); }}>Resolve</button></div><div className="quantity-comparison"><div><span>Canonical ledger</span><strong>{current.ledgerQty}</strong></div><div><span>{titleCase(current.targetSystem ?? "Source")} reports</span><strong className="critical-number">{current.observed ?? "—"}</strong></div><div><span>Difference</span><strong>{current.observed === null ? "—" : signed(current.observed - current.ledgerQty)}</strong></div></div><h3>Event timeline</h3>{historyQuery.error && <p className="action-error">History unavailable</p>}<Timeline events={historyQuery.data ?? []}/>{resolutionOpen && <div className="resolution-panel"><h3>Resolve this exception</h3><p>Append an adjustment or dismiss this discrepancy. Existing events are never changed.</p><label>Final quantity<input className="number-input" type="number" step="1" value={resolutionQty} onChange={(event) => setResolutionQty(event.target.value)}/></label><label>Resolution note<textarea value={resolutionNote} onChange={(event) => setResolutionNote(event.target.value)} placeholder="Reason for this decision"/></label>{actionError && <p className="action-error" role="alert">{actionError}</p>}<div className="resolution-actions"><button className="secondary-button" onClick={() => resolve("DISMISS")}>Dismiss</button><button className="primary-button" onClick={() => resolve("ADJUST_TO")}>Append adjustment</button></div></div>}</article></div>}</>}
    {screen === "sources" && <><PageHeader context="Connector operations" title="Sources" aside={<button className="secondary-button" onClick={() => sourcesQuery.refetch()}>Refresh status</button>}/><div className="source-list">{sources.map((source) => <article key={source.system}><div className="source-name"><span className={`status-dot ${source.status === "READY" ? "" : "status-warn"}`}/><div><h2>{titleCase(source.system)}</h2><p>{titleCase(source.status)}</p></div></div><dl><div><dt>Breaker</dt><dd>{titleCase(source.breakerState)}</dd></div><div><dt>Pending syncs</dt><dd className="number">{source.lag}</dd></div><div><dt>Last successful sync</dt><dd>{relativeTime(source.lastSync)}</dd></div></dl></article>)}</div>{sources.length === 0 && <div className="empty-state">No sources configured.</div>}<div className="instruction-note"><strong>When a connector is unavailable</strong><span>Writes stay queued and resume when the circuit breaker permits retries.</span></div></>}
    {screen === "detail" && latestSelected && <><PageHeader context="Position audit trail" title={latestSelected.sku} aside={<button className="text-action" onClick={() => navigate("positions")}>← Back to positions</button>}/><div className="detail-meta"><div><span>Location</span><strong>{latestSelected.location}</strong></div><div><span>Current position</span><strong className="number">{latestSelected.ledger}</strong></div><div><span>Last update</span><strong>{relativeTime(latestSelected.updatedAt)}</strong></div><div><span>History entries</span><strong>{historyQuery.data?.length ?? 0}</strong></div></div><section className="panel"><div className="panel-heading"><div><h2>Event history</h2><p>Receipt sequence and source time are preserved</p></div><button className="secondary-button" onClick={() => forceSync(latestSelected)}>Force sync</button></div>{actionMessage && <p role="status">{actionMessage}</p>}{historyQuery.error && <p className="action-error" role="alert">Could not load history: {historyQuery.error.message}</p>}<Timeline events={historyQuery.data ?? []}/></section></>}
  </main></div>;
}

function SignIn({ error, onSignIn }: { error: string; onSignIn: (username: string, password: string) => void }) {
  const [username, setUsername] = useState(""); const [password, setPassword] = useState("");
  return <main className="sign-in"><div className="brand"><span className="brand-mark">C</span><span>Converge</span></div><h1>Operations sign in</h1><p>{error}</p><form onSubmit={(event) => { event.preventDefault(); onSignIn(username, password); }}><label>Username<input autoComplete="username" required value={username} onChange={(event) => setUsername(event.target.value)}/></label><label>Password<input type="password" autoComplete="current-password" required value={password} onChange={(event) => setPassword(event.target.value)}/></label><button className="primary-button">Sign in</button></form></main>;
}
function PageHeader({ context, title, aside }: { context: string; title: string; aside?: React.ReactNode }) { return <header className="page-header"><div><p className="context-label">{context}</p><h1>{title}</h1></div>{aside ?? <div className="last-check">Live operations data</div>}</header>; }
function Overview({ positions, queue, sources, summary, drift, positionByKey, openQueue, showDetail }: { positions: Position[]; queue: QueueItem[]; sources: SourceHealth[]; summary?: Summary; drift: DriftSample[]; positionByKey: Map<string, Position>; openQueue: () => void; showDetail: (position: Position) => void }) {
  const largest = drift.reduce<DriftSample | null>((best, sample) => !best || Math.abs(sample.drift) > Math.abs(best.drift) ? sample : best, null);
  const hourly = new Map<string, { time: string; shopify?: number; square?: number }>();
  for (const sample of drift) {
    const hour = sample.sampledAt.slice(0, 13);
    const point = hourly.get(hour) ?? { time: new Date(`${hour}:00:00Z`).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) };
    const system = sample.system.toLowerCase();
    if (system === "shopify" || system === "square") {
      if (point[system] === undefined || Math.abs(sample.drift) > Math.abs(point[system])) point[system] = sample.drift;
    }
    hourly.set(hour, point);
  }
  const chart = [...hourly.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([, point]) => point);
  const attention = queue.map((item) => positionByKey.get(`${item.canonicalSkuId}:${item.locationId}`)).filter((row): row is Position => Boolean(row)).slice(0, 3);
  return <><PageHeader context="Inventory control" title="System overview"/><section className="summary-strip" aria-label="Inventory summary"><div><span>Open exceptions</span><strong>{queue.length}</strong><small>{queue.filter((item) => item.severity === "CRITICAL").length} critical</small></div><div><span>Tracked positions</span><strong>{positions.length}</strong><small>Across {summary?.locations ?? 0} locations</small></div><div><span>Largest drift</span><strong className="critical-number">{largest ? signed(largest.drift) : "—"}</strong><small>{largest ? titleCase(largest.system) : "No samples in 24 hours"}</small></div><div><span>Events today</span><strong>{summary?.eventsToday ?? 0}</strong><small>Received since midnight UTC</small></div></section><section className="panel chart-panel"><div className="panel-heading"><div><h2>Largest sampled drift by hour</h2><p>Maximum absolute difference from the canonical ledger in each hour</p></div><span>Last 24 hours</span></div>{chart.length === 0 ? <div className="empty-state">No drift samples in the last 24 hours.</div> : <div className="chart-wrap"><ResponsiveContainer width="100%" height="100%"><AreaChart data={chart} margin={{ top: 8, right: 12, left: -24, bottom: 0 }}><CartesianGrid stroke="#E3DEDF" vertical={false}/><XAxis dataKey="time" tickLine={false}/><YAxis tickLine={false} axisLine={false}/><Tooltip/><Area type="monotone" dataKey="shopify" stroke="#A51C30" fill="#A51C3022" strokeWidth={2}/><Area type="monotone" dataKey="square" stroke="#B8760B" fill="transparent" strokeWidth={2}/></AreaChart></ResponsiveContainer></div>}</section><section className="panel"><div className="panel-heading"><div><h2>Needs attention</h2><p>Active discrepancies, highest severity first</p></div><button className="text-action" onClick={openQueue}>Open queue →</button></div><div className="table-wrap"><PositionTable positions={attention} showDetail={showDetail}/></div>{attention.length === 0 && <div className="empty-state">No positions with active exceptions.</div>}</section><section className="source-strip" aria-label="Connector status">{sources.map((source) => <div key={source.system}><span className={`status-dot ${source.status === "READY" ? "" : "status-warn"}`}/><strong>{titleCase(source.system)}</strong><span>{source.lag} pending · {relativeTime(source.lastSync)}</span></div>)}</section></>;
}
function PositionTable({ positions, showDetail }: { positions: Position[]; showDetail: (position: Position) => void }) { return <table><thead><tr><th>SKU</th><th>Location</th><th className="number">Ledger</th><th className="number">Shopify</th><th className="number">Square</th><th className="number">Warehouse</th><th>Updated</th></tr></thead><tbody>{positions.map((row) => <tr key={`${row.canonicalSkuId}:${row.locationId}`}><td><button className="sku-link" onClick={() => showDetail(row)}>{row.sku}</button></td><td>{row.location}</td><Quantity value={row.ledger}/><Quantity value={row.shopify} drift={row.shopify !== null && row.shopify !== row.ledger}/><Quantity value={row.square} drift={row.square !== null && row.square !== row.ledger}/><Quantity value={row.warehouse} drift={row.warehouse !== null && row.warehouse !== row.ledger}/><td className="muted-cell">{relativeTime(row.updatedAt)}</td></tr>)}</tbody></table>; }
function Quantity({ value, drift = false }: { value: number | null; drift?: boolean }) { return <td className={`number ${drift ? "drift-cell" : ""}`}>{value ?? "—"}</td>; }
function Timeline({ events }: { events: EventItem[] }) { return events.length === 0 ? <div className="empty-state">No events to display.</div> : <div className="timeline">{events.map((event) => <div className="timeline-row" key={event.seq}><span className="timeline-dot"/><div><strong>{titleCase(event.eventType)}</strong><span>{titleCase(event.sourceSystem)}</span></div><div className="number event-change">{event.kind === "SNAPSHOT" ? `${event.qtyAbsolute} absolute` : signed(event.qtyDelta ?? 0)}</div><div><span>Sequence</span><strong className="number">{event.seq}</strong></div><time>{new Date(event.occurredAt).toLocaleString()}{event.absorbed ? " · absorbed" : ""}</time></div>)}</div>; }
