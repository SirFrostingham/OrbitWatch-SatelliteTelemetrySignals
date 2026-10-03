import { useCallback, useEffect, useState } from 'react'
import { Alert, api, GroundTrack, Overview, Positions, SatelliteDetail, sse, TickStats } from './lib/api'
import { WorldMap } from './components/WorldMap'
import { AlertsFeed, CountBars, Detail, IngestRuns, KpiRow, RegimeBars, Search, Sparkline } from './components/Panels'

export default function App() {
  const [overview, setOverview] = useState<Overview | null>(null)
  const [positions, setPositions] = useState<Positions>({ tick: '', count: 0, points: [] })
  const [alerts, setAlerts] = useState<Alert[]>([])
  const [history, setHistory] = useState<TickStats[]>([])
  const [live, setLive] = useState(false)
  const [selected, setSelected] = useState<number | null>(null)
  const [detail, setDetail] = useState<SatelliteDetail | null>(null)
  const [track, setTrack] = useState<GroundTrack | null>(null)

  // initial load + periodic overview refresh
  useEffect(() => {
    const load = () => api.overview().then(setOverview).catch(() => undefined)
    load()
    api.alerts(500).then(setAlerts).catch(() => undefined)
    api.statsHistory(360).then(setHistory).catch(() => undefined)
    api.positions().then(setPositions).catch(() => undefined)
    const t = setInterval(load, 10_000)
    return () => clearInterval(t)
  }, [])

  // live streams
  useEffect(() => sse<Positions>('/api/stream/positions', 'positions', (p) => { if (p.count > 0) setPositions(p) }, setLive), [])
  useEffect(() => sse<Alert>('/api/stream/alerts', 'alert', (a) => setAlerts((prev) => [a, ...prev.filter((x) => x.alertId !== a.alertId)].slice(0, 1000))), [])
  useEffect(() => sse<TickStats>('/api/stream/stats', 'stats', (s) => setHistory((prev) => [...prev, s].slice(-720))), [])

  const select = useCallback((id: number) => {
    setSelected(id)
    api.satellite(id).then(setDetail).catch(() => setDetail(null))
    api.groundTrack(id).then(setTrack).catch(() => setTrack(null))
  }, [])

  // refresh the selected object's live row each tick
  useEffect(() => { if (selected !== null) api.satellite(selected).then(setDetail).catch(() => undefined) }, [positions.tick, selected])

  const stats = history.length ? history[history.length - 1] : overview?.latestStats ?? null

  return (
    <div className="app">
      <header className="topbar">
        <h1>ORBITWATCH</h1>
        <span className="sub">CelesTrak GP → Kafka → SGP4 → Kafka Streams → this page</span>
        <span className="spacer" />
        <span className="pill"><span className={`dot ${live ? 'live' : ''}`} />{live ? 'live' : 'reconnecting'}</span>
        <span className="pill mono">tick {positions.tick ? positions.tick.slice(11, 19) + 'Z' : '–'}</span>
        <a className="pill" href="/swagger-ui.html" target="_blank" rel="noreferrer">REST</a>
        <a className="pill" href="/graphiql" target="_blank" rel="noreferrer">GraphQL</a>
      </header>
      <div className="layout">
        <div className="main">
          <KpiRow overview={overview} stats={stats} history={history} />
          <div className="card">
            <h2>Live catalogue <span className="hint">click an object for details and its next 100 minutes</span></h2>
            <WorldMap points={positions.points} tick={positions.tick} selected={selected} track={track} onSelect={select} />
          </div>
          <div className="kpis">
            <div className="card"><h2>Objects by regime</h2><RegimeBars stats={stats} /></div>
            <div className="card"><h2>Objects by type</h2><CountBars counts={stats?.byObjectType} color="#199e70" /></div>
            <div className="card"><h2>Top owners</h2><CountBars counts={stats?.byOwnerTop} color="#c98500" /></div>
          </div>
          <div className="kpis">
            <div className="card"><h2>Objects propagated per tick</h2><Sparkline history={history} pick={(t) => t.objectsPropagated} /></div>
            <div className="card"><h2>Mean element age (h)</h2><Sparkline history={history} pick={(t) => t.meanElementAgeHours} color="#c98500" /></div>
            <div className="card"><h2>Lowest altitude (km)</h2><Sparkline history={history} pick={(t) => t.minAltitudeKm} color="#e66767" /></div>
          </div>
        </div>
        <aside className="side">
          <div className="card"><Search onSelect={select} /></div>
          {detail && <div className="card"><Detail detail={detail} onClose={() => { setSelected(null); setDetail(null); setTrack(null) }} /></div>}
          <div className="card"><h2>Alerts <span className="hint">newest first</span></h2><AlertsFeed alerts={alerts} onSelect={select} /></div>
          <div className="card"><h2>Ingest runs</h2><IngestRuns runs={overview?.recentIngestRuns ?? []} /></div>
        </aside>
      </div>
    </div>
  )
}
