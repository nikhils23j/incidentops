import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { cleanup } from '@testing-library/react'
import type { Incident } from '../types'
import { SlaBadge } from './SlaBadge'

afterEach(cleanup)

const base: Incident = {
  id: '1', serviceName: 'Payments', severity: 'CRITICAL', status: 'OPEN', summary: 'x', assignee: 'responder-a',
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', acknowledgedAt: null, resolvedAt: null,
  ackDueAt: '2026-01-01T00:05:00Z', slaBreached: false, escalationLevel: 0, allowedNext: ['ACKNOWLEDGED'],
  postmortem: null, version: 0,
}

describe('SlaBadge', () => {
  it('counts down while open', () => {
    render(<SlaBadge incident={base} now={Date.parse('2026-01-01T00:02:00Z')} />)
    expect(screen.getByText('ack in 3m 00s')).toBeTruthy()
  })
  it('shows breach and escalation level', () => {
    render(<SlaBadge incident={{ ...base, escalationLevel: 2 }} now={Date.parse('2026-01-01T00:06:00Z')} />)
    expect(screen.getByText(/SLA breached 1m 00s ago/)).toBeTruthy()
    expect(screen.getByText('L2')).toBeTruthy()
  })
  it('renders nothing for an acknowledged, never-escalated incident', () => {
    const { container } = render(<SlaBadge incident={{ ...base, status: 'ACKNOWLEDGED' }} now={0} />)
    expect(container.textContent).toBe('')
  })
})
