import { useState, type FormEvent } from 'react'
import type { Incident, Postmortem, TimelineEntry } from '../types'
import { ResolveDialog } from './ResolveDialog'
import { SlaBadge } from './SlaBadge'
import { Timeline } from './Timeline'

type Props = {
  incident: Incident
  timeline: TimelineEntry[]
  rotation: string[]
  now: number
  busy: boolean
  onAck: () => void
  onResolve: (pm: Postmortem) => void
  onReassign: (assignee: string) => void
  onComment: (message: string) => Promise<void>
  onClose: () => void
}

export function IncidentDetail(p: Props) {
  const { incident: i } = p
  const [resolving, setResolving] = useState(false)
  const [comment, setComment] = useState('')

  async function submitComment(e: FormEvent) {
    e.preventDefault()
    if (!comment.trim()) return
    await p.onComment(comment)
    setComment('')
  }

  return <aside className="panel detail" aria-label="Incident detail">
    <div className="panel-head">
      <div>
        <span className={`badge ${i.severity.toLowerCase()}`}>{i.severity}</span>{' '}
        <span className={`pill st-${i.status.toLowerCase()}`}>{i.status}</span>
      </div>
      <button className="ghost" onClick={p.onClose} aria-label="Close detail">✕</button>
    </div>
    <h2>{i.serviceName}</h2>
    <p>{i.summary}</p>
    <div className="meta">
      <SlaBadge incident={i} now={p.now} />
      <label className="inline">On call
        <select value={i.assignee ?? ''} disabled={i.status === 'RESOLVED' || p.busy}
          onChange={e => p.onReassign(e.target.value)}>
          {[...new Set([...(i.assignee ? [i.assignee] : []), ...p.rotation])].map(r => <option key={r}>{r}</option>)}
        </select>
      </label>
      <small>v{i.version}</small>
    </div>

    <div className="actions">
      {i.allowedNext.includes('ACKNOWLEDGED') &&
        <button disabled={p.busy} onClick={p.onAck}>Acknowledge</button>}
      {i.allowedNext.includes('RESOLVED') && !resolving &&
        <button disabled={p.busy} onClick={() => setResolving(true)}>Resolve…</button>}
    </div>
    {resolving && <ResolveDialog busy={p.busy} onCancel={() => setResolving(false)}
      onSubmit={pm => { p.onResolve(pm); setResolving(false) }} />}

    {i.postmortem && <div className="postmortem">
      <h3>Postmortem</h3>
      <dl><dt>Cause</dt><dd>{i.postmortem.rootCause}</dd>
        <dt>Impact</dt><dd>{i.postmortem.impact}</dd>
        <dt>Follow-up</dt><dd>{i.postmortem.followUp}</dd></dl>
    </div>}

    <h3>Timeline</h3>
    <Timeline entries={p.timeline} now={p.now} />
    <form className="comment" onSubmit={e => void submitComment(e)}>
      <input aria-label="Add a comment" maxLength={2000} value={comment} placeholder="Add an update…"
        onChange={e => setComment(e.target.value)} />
      <button disabled={!comment.trim()}>Post</button>
    </form>
  </aside>
}
