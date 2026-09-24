import { defineStore } from 'pinia'
import { api, refreshCsrf } from '../api/http'

export type Role = 'ADMIN' | 'USER'
export interface CurrentUser {
  id: string; username: string; displayName: string; role: Role; enabled: boolean; mustChangePassword: boolean
}

export const useAuthStore = defineStore('auth', {
  state: () => ({ user: null as CurrentUser | null, initialized: false }),
  actions: {
    async load() {
      try { this.user = await api<CurrentUser>('/api/v1/auth/me') }
      catch { this.user = null }
      finally { this.initialized = true }
    },
    async login(username: string, password: string) {
      await refreshCsrf()
      this.user = await api<CurrentUser>('/api/v1/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) })
      await refreshCsrf()
    },
    async changePassword(currentPassword: string, newPassword: string) {
      this.user = await api<CurrentUser>('/api/v1/auth/change-password', {
        method: 'POST', body: JSON.stringify({ currentPassword, newPassword }),
      })
      await refreshCsrf()
    },
    async logout() {
      await api<void>('/api/v1/auth/logout', { method: 'POST' })
      this.user = null
      await refreshCsrf()
    },
  },
})
