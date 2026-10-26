import { apiClient } from './baseUrl.js'

export function createAuthApi(client) {
  return {
    getCsrf: () => client.getCsrf(),
    register: (data) => client.post('register', data),
    verifyEmail: (data) => client.post('verify-email', data),
    resendVerification: (data) => client.post('resend-verification', data),
    cancelRegistration: () => client.post('cancel-registration'),
    login: (data) => client.post('login', data),
    refresh: () => client.post('refresh'),
    logout: () => client.post('logout'),
    forgotPassword: (data) => client.post('forgot-password', data),
    resetPassword: (data) => client.post('reset-password', data),
  }
}

export const authApi = createAuthApi(apiClient)
