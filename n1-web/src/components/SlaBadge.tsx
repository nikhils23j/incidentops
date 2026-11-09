import { formatCountdown, slaState } from '../time'
import type { Incident } from '../types'

export function SlaBadge({ incident, now }: { incident: Incident; now: number }) {
  const state = slaState(incident.status, incident.createdAt, incident.ackDueAt, now)
  const level = incident.escalationLevel > 0 ? <span className="esc" title="Escalation level">L{incident.escalationLevel}</span> : null
  if (state === 'done') return level
  const left = Date.parse(incident.ackDueAt!) - now
  return <>
    <span className={`sla sla-${state}`} title={`Ack due ${new Date(incident.ackDueAt!).toLocaleTimeString()}`}>
      {state === 'breached' ? `SLA breached ${formatCountdown(-left)} ago` : `ack in ${formatCountdown(left)}`}
    </span>
    {level}
  </>
}
