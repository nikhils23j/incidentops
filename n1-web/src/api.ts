export const API: string = import.meta.env.VITE_API_URL || '/api'

/** An RFC 7807 problem from the server, or a synthetic one for network errors. */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly type: string = 'about:blank',
    readonly fieldErrors: Record<string, string> = {},
  ) { super(message) }

  get isVersionConflict() { return this.status === 409 && this.type.endsWith('/version-conflict') }
}

export async function toApiError(response: Response): Promise<ApiError> {
  let body: Record<string, unknown> = {}
  try { body = await response.json() } catch { /* not JSON */ }
  const errors = (body.errors ?? {}) as Record<string, string>
  const firstField = Object.entries(errors)[0]
  const message = firstField
    ? `${firstField[0]}: ${firstField[1]}`
    : (body.detail as string) || (body.title as string) || `Request failed (${response.status})`
  return new ApiError(message, response.status, (body.type as string) || 'about:blank', errors)
}

export async function request<T>(path: string, options?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(API + path, {
      ...options,
      headers: { 'Content-Type': 'application/json', ...options?.headers },
    })
  } catch {
    throw new ApiError('Cannot reach the server', 0, 'network')
  }
  if (!response.ok) throw await toApiError(response)
  return response.json() as Promise<T>
}
