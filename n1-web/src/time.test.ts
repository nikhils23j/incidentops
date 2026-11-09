import { describe, expect, it } from 'vitest'
import { formatCountdown, formatSeconds, slaState, timeAgo } from './time'

const created = '2026-01-01T00:00:00Z'
const due = '2026-01-01T00:05:00Z'           // 5 min CRITICAL window
const at = (iso: string) => Date.parse(iso)

describe('formatCountdown', () => {
  it('formats minutes and seconds', () => expect(formatCountdown(245_000)).toBe('4m 05s'))
  it('switches to hours over an hour', () => expect(formatCountdown(3_720_000)).toBe('1h 02m'))
  it('marks overdue as negative', () => expect(formatCountdown(-30_000)).toBe('-0m 30s'))
})

describe('slaState', () => {
  it('is ok early in the window', () => expect(slaState('OPEN', created, due, at('2026-01-01T00:01:00Z'))).toBe('ok'))
  it('warns in the last 20% / last minute', () => expect(slaState('OPEN', created, due, at('2026-01-01T00:04:30Z'))).toBe('warn'))
  it('is breached after the deadline', () => expect(slaState('OPEN', created, due, at('2026-01-01T00:05:01Z'))).toBe('breached'))
  it('stops counting once acknowledged', () => expect(slaState('ACKNOWLEDGED', created, due, at('2026-01-01T01:00:00Z'))).toBe('done'))
})

describe('formatSeconds / timeAgo', () => {
  it('shows a dash when there is no data', () => expect(formatSeconds(null)).toBe('—'))
  it('formats MTTA-style values', () => expect(formatSeconds(180)).toBe('3m 0s'))
  it('formats relative times', () => expect(timeAgo(created, at('2026-01-01T00:03:10Z'))).toBe('3m ago'))
})
