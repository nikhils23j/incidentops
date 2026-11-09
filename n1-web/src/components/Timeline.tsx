import { timeAgo } from '../time'
import type { TimelineEntry } from '../types'

const ICON: Record<TimelineEntry['type'], string> = {
  CREATED: '●', ACKNOWLEDGED: '✓', RESOLVED: '■', COMMENTED: '…', ASSIGNED: '→', ESCALATED: '▲',
}

export function Timeline({ entries, now }: { entries: TimelineEntry[]; now: number }) {
  if (entries.length === 0) return <p className="empty">No events yet.</p>
  return <ol className="timeline">
    {entries.map(e => <li key={e.id} className={`ev ev-${e.type.toLowerCase()}`}>
      <span className="dot" aria-hidden>{ICON[e.type]}</span>
      <div>
        <div className="ev-head"><strong>{e.type.toLowerCase()}</strong> · {e.actor}
          <time dateTime={e.at} title={new Date(e.at).toLocaleString()}>{timeAgo(e.at, now)}</time></div>
        <p>{e.message}</p>
      </div>
    </li>)}
  </ol>
}
