export type Severity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
export type Status = 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED'
export type EventType = 'CREATED' | 'ACKNOWLEDGED' | 'RESOLVED' | 'COMMENTED' | 'ASSIGNED' | 'ESCALATED'

export type Postmortem = { rootCause: string; impact: string; followUp: string }

export type Incident = {
  id: string
  serviceName: string
  severity: Severity
  status: Status
  summary: string
  assignee: string | null
  createdAt: string
  updatedAt: string
  acknowledgedAt: string | null
  resolvedAt: string | null
  ackDueAt: string | null
  slaBreached: boolean
  escalationLevel: number
  allowedNext: Status[]
  postmortem: Postmortem | null
  version: number
}

export type TimelineEntry = {
  id: number
  incidentId: string
  type: EventType
  actor: string
  message: string
  at: string
}

export type Page<T> = { items: T[]; page: number; size: number; totalElements: number; totalPages: number }

export type Stats = {
  byStatus: Record<Status, number>
  open: number
  slaBreachedOpen: number
  escalatedTotal: number
  mttaMeanSeconds: number | null
  mttaP50Seconds: number | null
  mttrMeanSeconds: number | null
  mttrP50Seconds: number | null
  sampleAcknowledged: number
  sampleResolved: number
}

export type IncidentChanged = { incident: Incident; event: TimelineEntry }
