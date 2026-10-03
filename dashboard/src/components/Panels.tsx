import { useEffect, useRef, useState } from 'react'
import { Alert, AlertType, api, IngestRun, Overview, SatelliteDetail, StateVector, TickStats } from '../lib/api'
import { REGIME_COLORS } from './WorldMap'

const fmt = (n: number | undefined | null, d = 0) => (n == null ? '–' : n.toLocaleString(undefined, { maximumFractionDigits: d }))
const ago = (iso: string) => {
  const s = Math.max(0, (Date.now() - Date.parse(iso)) / 1000)
  return s < 90 ? `${Math.round(s)}s ago` : s < 5400 ? `${Math.round(s / 60)}m ago` : `${(s / 3600).toFixed(1)}h ago`
}

export function KpiRow({ overview, stats, history }: { overview: Overview | null; stats: TickStats | null; history: TickStats[] }) {
  const conj = overview?.alertCounts?.CONJUNCTION ?? 0
  return (
    <div className="kpis">
      <div className="kpi"><div className="label">Objects tracked</div><div className="value">{fmt(overview?.trackedObjects)}</div><div className="trend">propagated every 10 s</div></div>
      <div className="kpi"><div className="label">State vectors</div><div className="value">{fmt(overview?.vectorsSeen)}</div><div className="trend">since gateway start</div></div>
      <div className="kpi"><div className="label">Mean element age</div><div className="value">{fmt(stats?.meanElementAgeHours, 1)}<small>h</small></div><div className="trend">{fmt(stats?.staleElementCount)} stale (&gt;30 d)</div></div>
      <div className="kpi"><div className="label">Decay watch</div><div className="value">{fmt(stats?.lowPerigeeCount)}</div><div className="trend">below 250 km now</div></div>
      <div className="kpi"><div className="label">Close approaches</div><div className="value">{fmt(conj)}</div><div className="trend">pairs &lt;10 km, deduped 30 m</div></div>
      <div className="kpi"><div className="label">Ticks analysed</div><div className="value">{fmt(history.length)}</div><div className="trend">{stats ? ago(stats.tick) : '–'}</div></div>
    </div>
  )
}

export function RegimeBars({ stats }: { stats: TickStats | null }) {
  if (!stats) return <div className="empty">Waiting for the first analytics window…</div>
  const entries = Object.entries(stats.byRegime).sort((a, b) => b[1] - a[1])
  const max = Math.max(...entries.map((e) => e[1]), 1)
  return (
    <div className="bars">
      {entries.map(([k, v]) => (
        <div className="bar-row" key={k}>
          <span className="name">{k}</span>
          <div className="track"><div className="fill" style={{ width: `${(v / max) * 100}%`, background: REGIME_COLORS[k as keyof typeof REGIME_COLORS] ?? '#888' }} /></div>
          <span className="val">{fmt(v)}</span>
        </div>
      ))}
    </div>
  )
}

export function CountBars({ counts, color = '#3987e5' }: { counts: Record<string, number> | undefined; color?: string }) {
  if (!counts) return <div className="empty">–</div>
  const entries = Object.entries(counts).sort((a, b) => b[1] - a[1]).slice(0, 8)
  const max = Math.max(...entries.map((e) => e[1]), 1)
  return (
    <div className="bars">
      {entries.map(([k, v]) => (
        <div className="bar-row" key={k}>
          <span className="name" title={k}>{k}</span>
          <div className="track"><div className="fill" style={{ width: `${(v / max) * 100}%`, background: color }} /></div>
          <span className="val">{fmt(v)}</span>
        </div>
      ))}
    </div>
  )
}

/** Single-series sparkline (one series → no legend; the card title names it). */
export function Sparkline({ history, pick, color = '#3987e5' }: { history: TickStats[]; pick: (t: TickStats) => number; color?: string }) {
  const ref = useRef<HTMLCanvasElement>(null)
  useEffect(() => {
    const c = ref.current; if (!c) return
    const dpr = window.devicePixelRatio || 1
    const w = c.clientWidth, h = 56
    c.width = w * dpr; c.height = h * dpr
    const ctx = c.getContext('2d')!; ctx.setTransform(dpr, 0, 0, dpr, 0, 0); ctx.clearRect(0, 0, w, h)
    const vals = history.map(pick)
    if (vals.length < 2) return
    const min = Math.min(...vals), max = Math.max(...vals)
    const y = (v: number) => h - 4 - ((v - min) / (max - min || 1)) * (h - 8)
    ctx.strokeStyle = color; ctx.lineWidth = 2; ctx.beginPath()
    vals.forEach((v, i) => { const x = (i / (vals.length - 1)) * (w - 2) + 1; i ? ctx.lineTo(x, y(v)) : ctx.moveTo(x, y(v)) })
    ctx.stroke()
    ctx.fillStyle = '#a3aec4'; ctx.font = '10px system-ui'
    ctx.fillText(fmt(max, 1), 2, 10); ctx.fillText(fmt(min, 1), 2, h - 2)
  }, [history, pick, color])
  return <canvas ref={ref} className="spark" />
}

const TYPES: AlertType[] = ['CONJUNCTION', 'MANEUVER_CANDIDATE', 'DECAY_WATCH', 'NEW_OBJECT', 'STALE_ELEMENTS', 'PROPAGATION_FAILURE']
const TYPE_LABEL: Record<AlertType, string> = { CONJUNCTION: 'Conjunction', MANEUVER_CANDIDATE: 'Manoeuvre', DECAY_WATCH: 'Decay', NEW_OBJECT: 'New object', STALE_ELEMENTS: 'Stale', PROPAGATION_FAILURE: 'Prop. failure' }

export function AlertsFeed({ alerts, onSelect }: { alerts: Alert[]; onSelect: (id: number) => void }) {
  const [filter, setFilter] = useState<AlertType | null>(null)
  const shown = alerts.filter((a) => !filter || a.type === filter).slice(0, 150)
  return (
    <>
      <div className="filters">
        <button className={filter === null ? 'on' : ''} onClick={() => setFilter(null)}>All ({alerts.length})</button>
        {TYPES.map((t) => { const n = alerts.filter((a) => a.type === t).length; return n ? <button key={t} className={filter === t ? 'on' : ''} onClick={() => setFilter(t)}>{TYPE_LABEL[t]} ({n})</button> : null })}
      </div>
      <div className="alerts">
        {shown.length === 0 && <div className="empty">No alerts yet — the analytics window closes one tick after data arrives.</div>}
        {shown.map((a) => (
          <div className="alert" key={a.alertId} onClick={() => onSelect(a.primaryNoradId)} title={JSON.stringify(a.details, null, 1)}>
            <div className={`sev ${a.severity}`} />
            <div>
              <div className="title">{a.title}</div>
              <div className="meta"><span className="tag">{TYPE_LABEL[a.type]}</span><span>#{a.primaryNoradId}{a.secondaryNoradId ? ` ↔ #${a.secondaryNoradId}` : ''}</span><span>{ago(a.occurredAt)}</span></div>
            </div>
          </div>
        ))}
      </div>
    </>
  )
}

export function Search({ onSelect }: { onSelect: (id: number) => void }) {
  const [q, setQ] = useState('')
  const [results, setResults] = useState<StateVector[]>([])
  useEffect(() => {
    if (q.trim().length < 2) { setResults([]); return }
    const t = setTimeout(() => api.search(q.trim()).then(setResults).catch(() => setResults([])), 200)
    return () => clearTimeout(t)
  }, [q])
  return (
    <div className="search">
      <input placeholder="Search by name or NORAD id (e.g. ISS, HST, 25544)…" value={q} onChange={(e) => setQ(e.target.value)} />
      {results.length > 0 && (
        <div className="results">
          {results.map((r) => (
            <div key={r.noradId} onClick={() => { onSelect(r.noradId); setQ(''); setResults([]) }}>
              <span>{r.objectName} <span className="muted">{r.regime} · {r.owner}</span></span><span className="id">#{r.noradId}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

export function Detail({ detail, onClose }: { detail: SatelliteDetail | null; onClose: () => void }) {
  if (!detail) return null
  const v = detail.latest
  const el = detail.details.elements ?? {}
  const cat = detail.details.catalog ?? {}
  const num = (x: unknown, d = 4) => (typeof x === 'number' ? x.toLocaleString(undefined, { maximumFractionDigits: d }) : String(x ?? '–'))
  return (
    <div className="detail">
      <button className="close" onClick={onClose}>×</button>
      <h2>{v?.objectName ?? String(cat.objectName ?? el.objectName ?? `NORAD ${detail.noradId}`)} <span className="hint">#{detail.noradId} · {String(el.objectId ?? '')}</span></h2>
      <dl>
        <dt>Regime</dt><dd>{v?.regime ?? detail.details.regime ?? '–'}</dd>
        <dt>Owner / type</dt><dd>{String(cat.owner ?? v?.owner ?? '–')} · {String(cat.objectType ?? v?.objectType ?? '–')}</dd>
        <dt>Launched</dt><dd>{String(cat.launchDate ?? '–')} {cat.launchSite ? `(${cat.launchSite})` : ''}</dd>
        {v && <><dt>Position</dt><dd>{v.latDeg.toFixed(3)}°, {v.lonDeg.toFixed(3)}° · {fmt(v.altKm)} km</dd>
          <dt>Speed</dt><dd>{v.speedKmS.toFixed(3)} km/s</dd>
          <dt>Tick</dt><dd className="mono">{v.tick.slice(11, 19)}Z</dd></>}
        <dt>Element epoch</dt><dd className="mono">{String(el.epoch ?? v?.elementEpoch ?? '–').slice(0, 19)} {v ? `(${v.elementAgeHours.toFixed(1)} h old)` : ''}</dd>
        <dt>Mean motion</dt><dd>{num(el.meanMotion, 6)} rev/day</dd>
        <dt>Inclination</dt><dd>{num(el.inclination)}°</dd>
        <dt>Eccentricity</dt><dd>{num(el.eccentricity, 7)}</dd>
        <dt>RAAN / AoP</dt><dd>{num(el.raOfAscNode)}° / {num(el.argOfPericenter)}°</dd>
        <dt>B*</dt><dd>{typeof el.bstar === 'number' ? el.bstar.toExponential(3) : '–'}</dd>
        {typeof cat.rcs === 'number' && <><dt>RCS</dt><dd>{num(cat.rcs, 3)} m²</dd></>}
      </dl>
      {detail.recentAlerts.length > 0 && (
        <div style={{ marginTop: 10 }}>
          <div className="muted" style={{ fontSize: 11, marginBottom: 4 }}>Recent alerts</div>
          {detail.recentAlerts.slice(0, 5).map((a) => <div key={a.alertId} style={{ fontSize: 12 }}><span className="tag">{TYPE_LABEL[a.type]}</span> {a.title}</div>)}
        </div>
      )}
    </div>
  )
}

export function IngestRuns({ runs }: { runs: IngestRun[] }) {
  if (!runs.length) return <div className="empty">No ingest runs seen yet.</div>
  return (
    <div className="runs">
      {runs.map((r) => (
        <div className="run" key={r.runId}>
          <span>{r.source} <span className="muted">{r.fromCache ? 'cache' : 'network'}{r.error ? ' · error' : ''}</span></span>
          <span className="tabular muted">{fmt(r.published)}/{fmt(r.fetched)} · {ago(r.finishedAt)}</span>
        </div>
      ))}
    </div>
  )
}
