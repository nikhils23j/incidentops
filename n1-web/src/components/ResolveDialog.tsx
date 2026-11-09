import { useState, type FormEvent } from 'react'
import type { Postmortem } from '../types'

export function ResolveDialog({ onSubmit, onCancel, busy }: {
  onSubmit: (pm: Postmortem) => void; onCancel: () => void; busy: boolean
}) {
  const [pm, setPm] = useState<Postmortem>({ rootCause: '', impact: '', followUp: '' })
  const field = (key: keyof Postmortem, label: string, hint: string) =>
    <label>{label}<textarea required maxLength={1000} value={pm[key]} placeholder={hint}
      onChange={e => setPm({ ...pm, [key]: e.target.value })} /></label>
  function submit(e: FormEvent) { e.preventDefault(); onSubmit(pm) }
  return <form className="resolve" onSubmit={submit}>
    <h3>Postmortem</h3>
    <p className="hint">Short and blameless. Required to resolve.</p>
    {field('rootCause', 'What caused it?', 'e.g. config push removed the DB pool limit')}
    {field('impact', 'Who/what was affected?', 'e.g. ~4 min of 5xx on checkout')}
    {field('followUp', 'What will stop it happening again?', 'e.g. validate config in CI')}
    <div className="row">
      <button type="button" className="secondary" onClick={onCancel}>Cancel</button>
      <button disabled={busy}>{busy ? 'Resolving…' : 'Resolve incident'}</button>
    </div>
  </form>
}
