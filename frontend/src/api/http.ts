export interface ApiErrorBody { code: string; message: string; timestamp?: string }

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message) }
}

let csrfToken = ''
let csrfHeader = 'X-XSRF-TOKEN'

export async function refreshCsrf(): Promise<void> {
  const response = await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' })
  if (!response.ok) throw new ApiError(response.status, 'CSRF_UNAVAILABLE', '无法初始化安全令牌')
  const body = await response.json() as { headerName: string; token: string }
  csrfHeader = body.headerName
  csrfToken = body.token
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase()
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method) && !csrfToken) await refreshCsrf()
  const headers = new Headers(init.headers)
  if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) headers.set(csrfHeader, csrfToken)
  const response = await fetch(path, { ...init, headers, credentials: 'same-origin' })
  if (response.status === 204) return undefined as T
  const body = await response.json().catch(() => null)
  if (!response.ok) {
    const error = body as ApiErrorBody | null
    throw new ApiError(response.status, error?.code ?? 'REQUEST_FAILED', error?.message ?? '请求失败，请稍后重试')
  }
  return body as T
}
