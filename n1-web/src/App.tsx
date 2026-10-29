import { useCallback, useEffect, useState, type FormEvent } from 'react'

type Severity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
type Status = 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED'
type Incident = {
  id: string
  serviceName: string
  severity: Severity
  status: Status
  summary: string
  createdAt: string
}
type Metrics = Record<Status, number>
const API = import.meta.env.VITE_API_URL || 'http://localhost:8080/api'

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(API + path, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...options?.headers },
  })
  if (!response.ok) {
    let message = `Request failed (${response.status})`
    try { message = (await response.json()).error || message } catch { /* use status */ }
    throw new Error(message)
  }
  return response.json() as Promise<T>
}

export default function App() {
  const [incidents, setIncidents] = useState<Incident[]>([])
  const [metrics, setMetrics] = useState<Metrics>({ OPEN: 0, ACKNOWLEDGED: 0, RESOLVED: 0 })
  const [filter, setFilter] = useState<Status | 'ALL'>('ALL')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [serviceName, setServiceName] = useState('')
  const [summary, setSummary] = useState('')
  const [severity, setSeverity] = useState<Severity>('MEDIUM')
  const [saving, setSaving] = useState(false)

  const refresh = useCallback(async () => {
    try {
      setError('')
      const [rows, counts] = await Promise.all([
        request<Incident[]>(`/incidents${filter === 'ALL' ? '' : '?status=' + filter}`),
        request<Metrics>('/metrics'),
      ])
      setIncidents(rows)
      setMetrics(counts)
    } catch (e) { setError((e as Error).message) }
    finally { setLoading(false) }
  }, [filter])

  useEffect(() => { void refresh() }, [refresh])

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSaving(true)
    try {
      await request<Incident>('/incidents', {
        method: 'POST',
        body: JSON.stringify({ serviceName, summary, severity }),
      })
      setServiceName('')
      setSummary('')
      await refresh()
    } catch (e) { setError((e as Error).message) }
    finally { setSaving(false) }
  }

  async function advance(incident: Incident) {
    const status: Status = incident.status === 'OPEN' ? 'ACKNOWLEDGED' : 'RESOLVED'
    try {
      await request<Incident>(`/incidents/${incident.id}/status`, {
        method: 'PATCH',
        body: JSON.stringify({ status }),
      })
      await refresh()
    } catch (e) { setError((e as Error).message) }
  }

  return <main>
    <header>
      <div>
        <p className="eyebrow">Operational reliability</p>
        <h1>IncidentOps</h1>
        <p className="sub">Track service incidents from detection through resolution.</p>
      </div>
      <button className="secondary" onClick={() => void refresh()}>Refresh</button>
    </header>

    <section className="stats" aria-label="Incident counts">
      {(['OPEN', 'ACKNOWLEDGED', 'RESOLVED'] as const).map(status =>
        <div className="stat" key={status}><strong>{metrics[status]}</strong><span>{status}</span></div>
      )}
    </section>

    <section className="layout">
      <div className="panel">
        <div className="panel-head">
          <h2>Incidents</h2>
          <select aria-label="Filter by status" value={filter}
            onChange={e => setFilter(e.target.value as Status | 'ALL')}>
            <option value="ALL">All statuses</option>
            <option value="OPEN">Open</option>
            <option value="ACKNOWLEDGED">Acknowledged</option>
            <option value="RESOLVED">Resolved</option>
          </select>
        </div>
        {error && <p role="alert" className="error">{error}</p>}
        {loading ? <p>Loading incidents…</p> : incidents.length === 0 ?
          <p className="empty">No incidents match this filter.</p> :
          <ul className="incidents">
            {incidents.map(i => <li key={i.id}>
              <div className="incident-top"><strong>{i.serviceName}</strong>
                <span className={`badge ${i.severity.toLowerCase()}`}>{i.severity}</span></div>
              <p>{i.summary}</p>
              <div className="incident-foot">
                <small>{i.status} · {new Date(i.createdAt).toLocaleString()}</small>
                {i.status !== 'RESOLVED' && <button onClick={() => void advance(i)}>
                  {i.status === 'OPEN' ? 'Acknowledge' : 'Resolve'}
                </button>}
              </div>
            </li>)}
          </ul>}
      </div>

      <form className="panel form" onSubmit={e => void create(e)}>
        <h2>Report incident</h2>
        <label>Service name<input required maxLength={80} value={serviceName}
          onChange={e => setServiceName(e.target.value)} placeholder="e.g. Order API" /></label>
        <label>Severity<select value={severity} onChange={e => setSeverity(e.target.value as Severity)}>
          <option>LOW</option><option>MEDIUM</option><option>HIGH</option><option>CRITICAL</option>
        </select></label>
        <label>Summary<textarea required maxLength={240} value={summary}
          onChange={e => setSummary(e.target.value)} placeholder="What happened?" /></label>
        <button disabled={saving}>{saving ? 'Saving…' : 'Create incident'}</button>
      </form>
    </section>
  </main>
}
