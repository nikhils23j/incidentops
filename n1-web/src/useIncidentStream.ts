import { useEffect, useRef, useState } from 'react'
import { API } from './api'
import type { IncidentChanged } from './types'

export type StreamState = 'connecting' | 'live' | 'offline'
const EVENTS = ['created', 'acknowledged', 'resolved', 'commented', 'assigned', 'escalated'].map(t => 'incident.' + t)

/** Subscribes to /api/stream. EventSource reconnects by itself; we just surface the state. */
export function useIncidentStream(onChange: (change: IncidentChanged) => void): StreamState {
  const [state, setState] = useState<StreamState>('connecting')
  const handler = useRef(onChange)
  handler.current = onChange

  useEffect(() => {
    const source = new EventSource(API + '/stream')
    source.addEventListener('hello', () => setState('live'))
    source.onerror = () => setState(source.readyState === EventSource.CLOSED ? 'offline' : 'connecting')
    const listener = (e: MessageEvent) => {
      try { handler.current(JSON.parse(e.data) as IncidentChanged) } catch { /* ignore malformed */ }
    }
    EVENTS.forEach(name => source.addEventListener(name, listener as EventListener))
    return () => source.close()
  }, [])

  return state
}
