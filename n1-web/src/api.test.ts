import { describe, expect, it } from 'vitest'
import { toApiError } from './api'

const problem = (status: number, body: object) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/problem+json' } })

describe('toApiError', () => {
  it('recognises the version-conflict problem type', async () => {
    const e = await toApiError(problem(409, { type: '/problems/version-conflict', title: 'Someone else changed this incident', detail: 'expected 0, current 1' }))
    expect(e.isVersionConflict).toBe(true)
    expect(e.message).toBe('expected 0, current 1')
  })
  it('does not confuse an illegal transition with a version conflict', async () => {
    const e = await toApiError(problem(409, { type: '/problems/illegal-transition', detail: 'Cannot move incident from OPEN to RESOLVED' }))
    expect(e.isVersionConflict).toBe(false)
  })
  it('surfaces the first field error from validation problems', async () => {
    const e = await toApiError(problem(400, { type: '/problems/validation', detail: 'One or more fields are invalid', errors: { serviceName: 'must not be blank' } }))
    expect(e.message).toBe('serviceName: must not be blank')
    expect(e.fieldErrors).toEqual({ serviceName: 'must not be blank' })
  })
  it('falls back to the status code for non-JSON bodies', async () => {
    const e = await toApiError(new Response('gateway down', { status: 502 }))
    expect(e.message).toBe('Request failed (502)')
  })
})
