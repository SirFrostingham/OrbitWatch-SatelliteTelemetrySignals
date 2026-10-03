// Typed client for the gateway REST + SSE surface. Types mirror contracts/*.java.

export type Regime = 'LEO' | 'MEO' | 'GEO' | 'HEO' | 'OTHER'

export interface StateVector {
  noradId: number
  objectName: string | null
  objectType: string | null
  owner: string | null
  regime: Regime
  tick: string
  elementEpoch: string
  elementAgeHours: number
  x: number; y: number; z: number
  vx: number; vy: number; vz: number
  latDeg: number; lonDeg: number; altKm: number
  speedKmS: number
}

export type AlertType = 'CONJUNCTION' | 'DECAY_WATCH' | 'STALE_ELEMENTS' | 'NEW_OBJECT' | 'MANEUVER_CANDIDATE' | 'PROPAGATION_FAILURE'
export type Severity = 'INFO' | 'WARNING' | 'CRITICAL'

export interface Alert {
  alertId: string
  type: AlertType
  severity: Severity
  occurredAt: string
  primaryNoradId: number
  primaryName: string | null
  secondaryNoradId: number | null
  secondaryName: string | null
  title: string
  details: Record<string, unknown>
  dedupeKey: string
}

export interface TickStats {
  tick: string
  objectsPropagated: number
  byRegime: Record<string, number>
  byObjectType: Record<string, number>
  byOwnerTop: Record<string, number>
  meanElementAgeHours: number
  staleElementCount: number
  lowPerigeeCount: number
  minAltitudeKm: number
  maxAltitudeKm: number
  computedAt: string
}

export interface IngestRun {
  runId: string; source: string; startedAt: string; finishedAt: string; fromCache: boolean
  fetched: number; published: number; unchanged: number; newObjects: number; error: string | null
}

export interface Overview {
  currentTick: string
  trackedObjects: number
  vectorsSeen: number
  latestStats: TickStats | null
  alertCounts: Record<string, number>
  recentIngestRuns: IngestRun[]
  serverTime: string
}

/** [noradId, lat, lon, altKm, regimeCode] */
export type PositionRow = [number, number, number, number, number]
export interface Positions { tick: string; count: number; points: PositionRow[] }

export interface GroundTrackPoint { t: string; latDeg: number; lonDeg: number; altKm: number }
export interface GroundTrack { noradId: number; name: string; regime: Regime; from: string; stepSeconds: number; points: GroundTrackPoint[] }

export interface SatelliteDetail {
  noradId: number
  latest: StateVector | null
  details: { elements?: Record<string, unknown>; catalog?: Record<string, unknown>; regime?: string; propagationFailed?: boolean }
  recentAlerts: Alert[]
}

export const REGIMES: Regime[] = ['LEO', 'MEO', 'GEO', 'HEO', 'OTHER']
export const REGIME_CODE: Record<number, Regime> = { 0: 'LEO', 1: 'MEO', 2: 'GEO', 3: 'HEO', 4: 'OTHER' }

const base = ''

async function get<T>(path: string): Promise<T> {
  const r = await fetch(base + path)
  if (!r.ok) throw new Error(`${path}: HTTP ${r.status}`)
  return r.json() as Promise<T>
}

export const api = {
  overview: () => get<Overview>('/api/overview'),
  positions: (regime?: string) => get<Positions>(`/api/positions${regime ? `?regime=${regime}` : ''}`),
  alerts: (limit = 100) => get<Alert[]>(`/api/alerts?limit=${limit}`),
  statsHistory: (last = 120) => get<TickStats[]>(`/api/stats/history?last=${last}`),
  search: (q: string) => get<StateVector[]>(`/api/satellites?q=${encodeURIComponent(q)}&sort=name&limit=25`),
  satellite: (id: number) => get<SatelliteDetail>(`/api/satellites/${id}`),
  groundTrack: (id: number, spanMinutes = 100) => get<GroundTrack>(`/api/satellites/${id}/groundtrack?spanMinutes=${spanMinutes}&stepSeconds=30`),
}

/** Subscribe to a named SSE event; returns an unsubscribe function. */
export function sse<T>(path: string, event: string, onMessage: (t: T) => void, onState?: (open: boolean) => void): () => void {
  const es = new EventSource(base + path)
  es.addEventListener(event, (e) => onMessage(JSON.parse((e as MessageEvent).data) as T))
  es.onopen = () => onState?.(true)
  es.onerror = () => onState?.(false)
  return () => es.close()
}
