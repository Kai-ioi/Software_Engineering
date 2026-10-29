import { reactive } from 'vue'
import { request, refreshCsrf } from '../api/http'
export interface User { id: number; username: string; displayName: string; platformAdmin: boolean }
export const auth = reactive({ user: null as User | null, ready: false })
let loading: Promise<void> | null = null
export async function loadUser() {
  if (auth.ready) return
  if (!loading) loading = (async () => {
    try { auth.user = await request<User>('/auth/me', {}, true) }
    catch { auth.user = null }
    finally { auth.ready = true; loading = null }
  })()
  await loading
}
export async function login(username: string, password: string) {
  // Always renew CSRF before login, including a retry after session expiry.
  await refreshCsrf()
  auth.user = await request<User>('/auth/login', { method: 'POST', body: new URLSearchParams({ username, password }) })
  auth.ready = true
  await refreshCsrf()
}
export async function logout() {
  await request('/auth/logout', { method: 'POST' })
  auth.user = null; auth.ready = true
  await refreshCsrf()
}
window.addEventListener('session-expired', () => { auth.user = null; auth.ready = true })
