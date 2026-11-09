import { useState, type FormEvent } from 'react'
import type { Severity } from '../types'

export function CreateForm({ onCreate }: {
  onCreate: (input: { serviceName: string; severity: Severity; summary: string }) => Promise<boolean>
}) {
  const [serviceName, setServiceName] = useState('')
  const [summary, setSummary] = useState('')
  const [severity, setSeverity] = useState<Severity>('MEDIUM')
  const [saving, setSaving] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setSaving(true)
    const ok = await onCreate({ serviceName, severity, summary })
    setSaving(false)
    if (ok) { setServiceName(''); setSummary('') }
  }

  return <form className="panel form" onSubmit={e => void submit(e)}>
    <h2>Report incident</h2>
    <label>Service<input required maxLength={80} value={serviceName}
      onChange={e => setServiceName(e.target.value)} placeholder="e.g. Order API" /></label>
    <label>Severity<select value={severity} onChange={e => setSeverity(e.target.value as Severity)}>
      <option>LOW</option><option>MEDIUM</option><option>HIGH</option><option>CRITICAL</option>
    </select></label>
    <label>Summary<textarea required maxLength={240} value={summary}
      onChange={e => setSummary(e.target.value)} placeholder="What's broken?" /></label>
    <button disabled={saving}>{saving ? 'Saving…' : 'Open incident'}</button>
  </form>
}
