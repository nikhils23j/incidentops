import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, request } from './api'
import { CreateForm } from './components/CreateForm'
import { IncidentDetail } from './components/IncidentDetail'
import { SlaBadge } from './components/SlaBadge'
import { formatSeconds, timeAgo } from './time'
import type { Incident, IncidentChanged, Page, Postmortem, Severity, Stats, Status, TimelineEntry } from './types'
import { useIncidentStream } from './useIncidentStream'
import { useNow } from './useNow'

const PAGE_SIZE = 10
type Toast = { id: number; text: string; kind: 'info' | 'warn' }

export default function App() {
  const now = useNow()
  const [me, setMe] = useState(() => localStorage.getItem('incidentops.me') || 'me')
  const [page, setPage] = useState<Page<Incident> | null>(null)
  const [pageNo, setPageNo] = useState(0)
  const [stats, setStats] = useState<Stats | null>(null)
  const [rotation, setRotation] = useState<string[]>([])
  const [status, setStatus] = useState<Status | 'ALL'>('ALL')
  const [severity, setSeverity] = useState<Severity | 'ALL'>('ALL')
  const [q, setQ] = useState('')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [timeline, setTimeline] = useState<TimelineEntry[]>([])
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [toasts, setToasts] = useState<Toast[]>([])
  const toastId = useRef(0)

  useEffect(() => { localStorage.setItem('incidentops.me', me) }, [me])

  const toast = useCallback((text: string, kind: Toast['kind'] = 'info') => {
    const id = ++toastId.current
    setToasts(t => [...t.slice(-3), { id, text, kind }])
    setTimeout(() => setToasts(t => t.filter(x => x.id !== id)), 5000)
  }, [])

  const refresh = useCallback(async () => {
    const params = new URLSearchParams({ page: String(pageNo), size: String(PAGE_SIZE) })
    if (status !== 'ALL') params.set('status', status)
    if (severity !== 'ALL') params.set('severity', severity)
    if (q.trim()) params.set('q', q.trim())
    try {
      const [rows, s] = await Promise.all([
        request<Page<Incident>>('/incidents?' + params), request<Stats>('/stats')])
      setPage(rows)
      setStats(s)
      setError('')
    } catch (e) { setError((e as Error).message) }
  }, [pageNo, status, severity, q])

  const loadTimeline = useCallback(async (id: string) => {
    try { setTimeline(await request<TimelineEntry[]>(`/incidents/${id}/events`)) }
    catch (e) { setError((e as Error).message) }
  }, [])

  useEffect(() => { const t = setTimeout(() => void refresh(), 200); return () => clearTimeout(t) }, [refresh])
  useEffect(() => { void request<{ rotation: string[] }>('/oncall').then(r => setRotation(r.rotation)).catch(() => {}) }, [])
  useEffect(() => { if (selectedId) void loadTimeline(selectedId); else setTimeline([]) }, [selectedId, loadTimeline])

  const selected = page?.items.find(i => i.id === selectedId) ?? null
  const [detail, setDetail] = useState<Incident | null>(null)
  useEffect(() => { if (selected) setDetail(selected) }, [selected])
  useEffect(() => {
    if (selectedId && !selected) void request<Incident>(`/incidents/${selectedId}`).then(setDetail).catch(() => {})
    if (!selectedId) setDetail(null)
  }, [selectedId, selected])

  // Live updates from other people / the SLA bot.
  const live = useIncidentStream(useCallback((c: IncidentChanged) => {
    void refresh()
    if (c.incident.id === selectedId) { setDetail(c.incident); void loadTimeline(c.incident.id) }
    if (c.event.actor !== me) {
      toast(`${c.event.actor} · ${c.event.type.toLowerCase()} · ${c.incident.serviceName}`,
        c.event.type === 'ESCALATED' ? 'warn' : 'info')
    }
  }, [refresh, loadTimeline, selectedId, me, toast]))

  async function mutate(fn: () => Promise<unknown>) {
    setBusy(true)
    try {
      await fn()
      setError('')
    } catch (e) {
      if (e instanceof ApiError && e.isVersionConflict) {
        toast('Someone else changed this incident first. Showing the latest version.', 'warn')
      } else setError((e as Error).message)
    } finally {
      setBusy(false)
      await refresh()
      if (selectedId) await loadTimeline(selectedId)
    }
  }

  const changeStatus = (i: Incident, next: Status, postmortem?: Postmortem) => mutate(() =>
    request(`/incidents/${i.id}/status`, { method: 'PATCH',
      body: JSON.stringify({ status: next, expectedVersion: i.version, actor: me, postmortem }) }))

  async function create(input: { serviceName: string; severity: Severity; summary: string }) {
    try {
      const created = await request<Incident>('/incidents', { method: 'POST', body: JSON.stringify({ ...input, reporter: me }) })
      setSelectedId(created.id)
      await refresh()
      return true
    } catch (e) { setError((e as Error).message); return false }
  }

  return <main>
    <header>
      <div>
        <p className="eyebrow">IncidentOps</p>
        <h1>What's on fire?</h1>
      </div>
      <div className="whoami">
        <span className={`live live-${live}`} title="Server-Sent Events connection">{live === 'live' ? 'Live' : live}</span>
        <label>You are <input aria-label="Your name" value={me} maxLength={60} onChange={e => setMe(e.target.value || 'me')} /></label>
      </div>
    </header>

    <section className="stats" aria-label="Stats">
      <div className="stat"><strong>{stats?.byStatus.OPEN ?? '—'}</strong><span>Open</span></div>
      <div className="stat"><strong>{stats?.byStatus.ACKNOWLEDGED ?? '—'}</strong><span>Acknowledged</span></div>
      <div className={`stat ${stats?.slaBreachedOpen ? 'hot' : ''}`}><strong>{stats?.slaBreachedOpen ?? '—'}</strong><span>SLA breached</span></div>
      <div className="stat"><strong>{formatSeconds(stats?.mttaMeanSeconds ?? null)}</strong><span>MTTA (n={stats?.sampleAcknowledged ?? 0})</span></div>
      <div className="stat"><strong>{formatSeconds(stats?.mttrMeanSeconds ?? null)}</strong><span>MTTR (n={stats?.sampleResolved ?? 0})</span></div>
    </section>

    {error && <p role="alert" className="error">{error} <button className="ghost" onClick={() => setError('')}>dismiss</button></p>}

    <section className={`layout ${detail ? 'with-detail' : ''}`}>
      <div className="panel">
        <div className="filters">
          <input type="search" aria-label="Search" placeholder="Search service or summary…" value={q}
            onChange={e => { setQ(e.target.value); setPageNo(0) }} />
          <select aria-label="Filter by status" value={status} onChange={e => { setStatus(e.target.value as Status | 'ALL'); setPageNo(0) }}>
            <option value="ALL">All statuses</option><option>OPEN</option><option>ACKNOWLEDGED</option><option>RESOLVED</option>
          </select>
          <select aria-label="Filter by severity" value={severity} onChange={e => { setSeverity(e.target.value as Severity | 'ALL'); setPageNo(0) }}>
            <option value="ALL">All severities</option><option>CRITICAL</option><option>HIGH</option><option>MEDIUM</option><option>LOW</option>
          </select>
        </div>
        {!page ? <p>Loading…</p> : page.items.length === 0 ? <p className="empty">Nothing matches. Quiet day?</p> :
          <ul className="incidents">
            {page.items.map(i => <li key={i.id} className={i.id === selectedId ? 'selected' : ''}>
              <button className="row-btn" onClick={() => setSelectedId(i.id === selectedId ? null : i.id)}>
                <div className="incident-top">
                  <strong>{i.serviceName}</strong>
                  <span className={`badge ${i.severity.toLowerCase()}`}>{i.severity}</span>
                </div>
                <p>{i.summary}</p>
                <div className="incident-foot">
                  <span className={`pill st-${i.status.toLowerCase()}`}>{i.status}</span>
                  <SlaBadge incident={i} now={now} />
                  <small>{i.assignee} · {timeAgo(i.createdAt, now)}</small>
                </div>
              </button>
            </li>)}
          </ul>}
        {page && page.totalPages > 1 && <div className="pager">
          <button className="secondary" disabled={pageNo === 0} onClick={() => setPageNo(pageNo - 1)}>← Newer</button>
          <small>Page {page.page + 1} of {page.totalPages} · {page.totalElements} incidents</small>
          <button className="secondary" disabled={pageNo + 1 >= page.totalPages} onClick={() => setPageNo(pageNo + 1)}>Older →</button>
        </div>}
      </div>

      {detail ? <IncidentDetail incident={detail} timeline={timeline} rotation={rotation} now={now} busy={busy}
        onClose={() => setSelectedId(null)}
        onAck={() => void changeStatus(detail, 'ACKNOWLEDGED')}
        onResolve={pm => void changeStatus(detail, 'RESOLVED', pm)}
        onReassign={a => void mutate(() => request(`/incidents/${detail.id}/assignee`, { method: 'PATCH',
          body: JSON.stringify({ assignee: a, expectedVersion: detail.version, actor: me }) }))}
        onComment={m => mutate(() => request(`/incidents/${detail.id}/comments`, { method: 'POST',
          body: JSON.stringify({ author: me, message: m }) }))}
      /> : <CreateForm onCreate={create} />}
    </section>

    <div className="toasts" aria-live="polite">
      {toasts.map(t => <div key={t.id} className={`toast ${t.kind}`}>{t.text}</div>)}
    </div>
  </main>
}
