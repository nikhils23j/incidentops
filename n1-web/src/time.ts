export type SlaState = 'ok' | 'warn' | 'breached' | 'done'

/** "4m 05s", "1h 02m", "-0m 30s" for overdue. */
export function formatCountdown(ms: number): string {
  const sign = ms < 0 ? '-' : ''
  const total = Math.floor(Math.abs(ms) / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  return h > 0 ? `${sign}${h}h ${String(m).padStart(2, '0')}m` : `${sign}${m}m ${String(s).padStart(2, '0')}s`
}

/** Warn when less than 20% of the window (or under a minute) is left. */
export function slaState(status: string, createdAt: string, ackDueAt: string | null, now: number): SlaState {
  if (status !== 'OPEN' || !ackDueAt) return 'done'
  const due = Date.parse(ackDueAt)
  const left = due - now
  if (left <= 0) return 'breached'
  const window = due - Date.parse(createdAt)
  return left < Math.max(60_000, window * 0.2) ? 'warn' : 'ok'
}

export function formatSeconds(seconds: number | null): string {
  if (seconds == null) return '—'
  if (seconds < 60) return `${Math.round(seconds)}s`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ${Math.round(seconds % 60)}s`
  return `${Math.floor(seconds / 3600)}h ${Math.round((seconds % 3600) / 60)}m`
}

export function timeAgo(iso: string, now: number): string {
  const s = Math.max(0, Math.round((now - Date.parse(iso)) / 1000))
  if (s < 60) return `${s}s ago`
  if (s < 3600) return `${Math.floor(s / 60)}m ago`
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`
  return `${Math.floor(s / 86400)}d ago`
}
