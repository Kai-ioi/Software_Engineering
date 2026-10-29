import { ElMessage } from 'element-plus'
interface Envelope<T> { code: string; message: string; data: T }
export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message) }
}
let csrf: { headerName: string; token: string } | null = null
export async function refreshCsrf() {
  csrf = await request<{ headerName: string; token: string }>('/auth/csrf', {}, true)
}
export async function request<T>(path: string, init: RequestInit = {}, quiet = false): Promise<T> {
  const method = (init.method || 'GET').toUpperCase()
  const headers = new Headers(init.headers)
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    if (!csrf) await refreshCsrf()
    headers.set(csrf!.headerName, csrf!.token)
  }
  let response: Response
  try { response = await fetch(`/api${path}`, { ...init, headers, credentials: 'same-origin' }) }
  catch { if (!quiet) ElMessage.error('无法连接服务，请检查后端是否启动'); throw new ApiError(0, 'NETWORK_ERROR', '无法连接服务') }
  let body: Envelope<T>
  try { body = await response.json() }
  catch { if (!quiet) ElMessage.error('服务返回异常，请稍后重试'); throw new ApiError(response.status, 'INVALID_RESPONSE', '服务返回异常') }
  if (!response.ok || body.code !== 'OK') {
    if (response.status === 401) { csrf = null; window.dispatchEvent(new Event('session-expired')) }
    if (body.code === 'CSRF_INVALID') csrf = null
    if (!quiet) ElMessage.error(body.message || '操作失败')
    throw new ApiError(response.status, body.code, body.message)
  }
  return body.data
}
