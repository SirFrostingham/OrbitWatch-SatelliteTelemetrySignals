import { useEffect, useRef, useState } from 'react'
import land from '../lib/land.json'
import { GroundTrack, PositionRow, REGIME_CODE, Regime, REGIMES } from '../lib/api'

const COLORS: Record<Regime, string> = { LEO: '#3987e5', MEO: '#d95926', GEO: '#199e70', HEO: '#c98500', OTHER: '#d55181' }

interface Props {
  points: PositionRow[]
  tick: string
  selected: number | null
  track: GroundTrack | null
  onSelect: (noradId: number) => void
}

/**
 * Equirectangular world map on a canvas. 16k points redraw in a few ms; no map library, no tiles,
 * no network — the land outlines are Natural Earth 110m (public domain) baked into the bundle.
 */
export function WorldMap({ points, tick, selected, track, onSelect }: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const [visible, setVisible] = useState<Record<Regime, boolean>>({ LEO: true, MEO: true, GEO: true, HEO: true, OTHER: true })
  const [hover, setHover] = useState<PositionRow | null>(null)

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const dpr = window.devicePixelRatio || 1
    const w = canvas.clientWidth, h = w / 2
    canvas.width = w * dpr; canvas.height = h * dpr
    canvas.style.height = `${h}px`
    const ctx = canvas.getContext('2d')!
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    const px = (lon: number) => ((lon + 180) / 360) * w
    const py = (lat: number) => ((90 - lat) / 180) * h

    ctx.clearRect(0, 0, w, h)
    // graticule
    ctx.strokeStyle = '#16213a'; ctx.lineWidth = 1
    for (let lon = -180; lon <= 180; lon += 30) { ctx.beginPath(); ctx.moveTo(px(lon), 0); ctx.lineTo(px(lon), h); ctx.stroke() }
    for (let lat = -60; lat <= 60; lat += 30) { ctx.beginPath(); ctx.moveTo(0, py(lat)); ctx.lineTo(w, py(lat)); ctx.stroke() }
    // land
    ctx.fillStyle = '#152238'; ctx.strokeStyle = '#2a3b5c'
    for (const ring of land as number[][][]) {
      ctx.beginPath()
      ring.forEach(([lon, lat], i) => (i === 0 ? ctx.moveTo(px(lon), py(lat)) : ctx.lineTo(px(lon), py(lat))))
      ctx.closePath(); ctx.fill(); ctx.stroke()
    }
    // satellites
    for (const p of points) {
      const regime = REGIME_CODE[p[4]]
      if (!visible[regime]) continue
      ctx.fillStyle = COLORS[regime]
      ctx.globalAlpha = regime === 'LEO' ? 0.75 : 0.95
      ctx.fillRect(px(p[2]) - 1, py(p[1]) - 1, 2, 2)
    }
    ctx.globalAlpha = 1
    // ground track
    if (track && track.points.length > 1) {
      ctx.strokeStyle = '#ffffff'; ctx.lineWidth = 1.5; ctx.setLineDash([4, 3])
      ctx.beginPath()
      let prev: number | null = null
      for (const q of track.points) {
        const x = px(q.lonDeg), y = py(q.latDeg)
        if (prev !== null && Math.abs(q.lonDeg - prev) > 180) ctx.moveTo(x, y); else if (prev === null) ctx.moveTo(x, y); else ctx.lineTo(x, y)
        prev = q.lonDeg
      }
      ctx.stroke(); ctx.setLineDash([])
    }
    // selected marker
    if (selected !== null) {
      const p = points.find((r) => r[0] === selected)
      if (p) {
        ctx.strokeStyle = '#ffffff'; ctx.lineWidth = 2
        ctx.beginPath(); ctx.arc(px(p[2]), py(p[1]), 7, 0, Math.PI * 2); ctx.stroke()
      }
    }
    if (hover) {
      ctx.strokeStyle = '#e6ebf5'; ctx.lineWidth = 1
      ctx.beginPath(); ctx.arc(px(hover[2]), py(hover[1]), 5, 0, Math.PI * 2); ctx.stroke()
    }
  }, [points, visible, selected, track, hover])

  const nearest = (e: React.MouseEvent<HTMLCanvasElement>): PositionRow | null => {
    const canvas = canvasRef.current!
    const rect = canvas.getBoundingClientRect()
    const w = rect.width, h = rect.height
    const lon = (e.clientX - rect.left) / w * 360 - 180
    const lat = 90 - (e.clientY - rect.top) / h * 180
    let best: PositionRow | null = null, bestD = 4 // degrees
    for (const p of points) {
      if (!visible[REGIME_CODE[p[4]]]) continue
      const d = Math.hypot((p[2] - lon) * Math.cos(lat * Math.PI / 180), p[1] - lat)
      if (d < bestD) { bestD = d; best = p }
    }
    return best
  }

  return (
    <div className="map-wrap">
      <canvas ref={canvasRef}
        onMouseMove={(e) => setHover(nearest(e))}
        onMouseLeave={() => setHover(null)}
        onClick={(e) => { const p = nearest(e); if (p) onSelect(p[0]) }} />
      <div className="map-meta">
        {hover ? `#${hover[0]} · ${REGIME_CODE[hover[4]]} · ${hover[3].toLocaleString()} km · ${hover[1].toFixed(1)}°, ${hover[2].toFixed(1)}°` : `tick ${tick.replace('T', ' ').slice(0, 19)}Z · ${points.length.toLocaleString()} objects`}
      </div>
      <div className="map-legend">
        {REGIMES.map((r) => (
          <button key={r} className={visible[r] ? '' : 'off'} onClick={() => setVisible({ ...visible, [r]: !visible[r] })}>
            <span className="sw" style={{ background: COLORS[r] }} /> {r}
          </button>
        ))}
      </div>
    </div>
  )
}

export const REGIME_COLORS = COLORS
