import { authApi } from '../api/authApi.js'
import { ApiError } from '../api/baseUrl.js'

const emptySession = { user: null, accessToken: null, expiresAt: 0 }

function changedSession() {
  return new ApiError(0, { code: 'SESSION_CHANGED', message: 'Phiên đăng nhập đã thay đổi. Vui lòng thử lại.' })
}

function readSession(response, now) {
  const user = response?.user
  if (typeof response?.accessToken !== 'string' || !response.accessToken.trim()
    || response.tokenType !== 'Bearer' || !Number.isSafeInteger(response.expiresIn) || response.expiresIn <= 0
    || !Number.isSafeInteger(now + response.expiresIn * 1000)
    || typeof user?.id !== 'string' || !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(user.id)
    || typeof user.email !== 'string' || !/^[^\s@]+@[^\s@]+$/.test(user.email)
    || !['USER', 'ADMIN'].includes(user.role)) {
    throw new ApiError(200, {
      code: 'INVALID_AUTH_RESPONSE', message: 'Không thể xác nhận phiên đăng nhập. Vui lòng đăng nhập lại.',
    })
  }
  return {
    accessToken: response.accessToken,
    expiresAt: now + response.expiresIn * 1000,
    user: Object.freeze({ id: user.id, username: typeof user.username === 'string' ? user.username : '', email: user.email, role: user.role }),
  }
}

// One instance per tab. React effect cleanup never cancels a rotating request.
export function createSessionStore({ api = authApi, now = Date.now } = {}) {
  let snapshot = Object.freeze({ status: 'initializing', ...emptySession, error: null, canRetry: false, operation: null, generation: 0 })
  const listeners = new Set()
  let initialized = false
  let initializationPromise = null
  let refreshPromise = null
  let loginPromise = null
  let reauthenticationPromise = null
  let resetPromise = null
  let logoutPromise = null
  let generation = 0

  function publish(next) {
    snapshot = Object.freeze({ ...emptySession, error: null, canRetry: false, operation: null, generation, ...next })
    listeners.forEach(listener => listener())
    return snapshot
  }

  function refreshSession() {
    if (refreshPromise) return refreshPromise
    const version = generation
    publish({ status: 'initializing', operation: 'refresh' })
    refreshPromise = (async () => {
      let stage = 'csrf'
      try {
        await api.getCsrf()
        if (version !== generation) return null
        stage = 'refresh'
        const response = await api.refresh()
        stage = 'response'
        if (version !== generation) return null
        return publish({ status: 'authenticated', ...readSession(response, now()) })
      } catch (error) {
        if (version !== generation) return null
        if (stage === 'refresh' && error.status === 401) {
          publish({ status: 'unauthenticated' })
          return null
        }
        publish({
          status: 'error', error,
          canRetry: stage === 'csrf' || (stage === 'refresh' && (error.status === 503 || (error.status === 403 && error.code === 'CSRF_INVALID'))),
        })
        throw error
      }
    })().finally(() => { refreshPromise = null })
    return refreshPromise
  }

  function initialize() {
    if (!initialized) {
      initialized = true
      initializationPromise = refreshSession().catch(() => null)
    }
    return initializationPromise || Promise.resolve(null)
  }

  function ensureSession() {
    if (logoutPromise) return Promise.reject(changedSession())
    if (resetPromise) return Promise.reject(changedSession())
    if (reauthenticationPromise) return reauthenticationPromise.then(() => null)
    if (loginPromise) return loginPromise
    if (refreshPromise) return refreshPromise
    if (!initialized) return initialize()
    if (snapshot.status === 'error') return Promise.reject(snapshot.error)
    if (snapshot.status !== 'authenticated') return Promise.resolve(null)
    if (snapshot.expiresAt - now() > 30000) return Promise.resolve(snapshot)
    return refreshSession()
  }

  function retryInitialization() {
    if (logoutPromise) return Promise.reject(changedSession())
    if (resetPromise) return Promise.reject(changedSession())
    if (reauthenticationPromise) return Promise.reject(changedSession())
    if (refreshPromise) return refreshPromise
    if (loginPromise || snapshot.status !== 'error' || !snapshot.canRetry) {
      return Promise.reject(snapshot.error || changedSession())
    }
    return refreshSession()
  }

  // For future protected reads: a late 401 must not rotate a newer token again.
  function recoverSession(rejectedAccessToken, expectedGeneration) {
    if (logoutPromise) return Promise.reject(changedSession())
    if (resetPromise) return Promise.reject(changedSession())
    if (expectedGeneration !== generation) return Promise.reject(changedSession())
    if (reauthenticationPromise) return Promise.reject(changedSession())
    if (loginPromise) return Promise.reject(changedSession())
    if (refreshPromise) return refreshPromise
    if (snapshot.status === 'error') return Promise.reject(snapshot.error)
    if (snapshot.status !== 'authenticated') return Promise.resolve(null)
    return rejectedAccessToken === snapshot.accessToken ? refreshSession() : ensureSession()
  }

  function beginLogin() {
    if (reauthenticationPromise) return reauthenticationPromise
    // Await the cookie response even when its auth-state result has been discarded.
    const version = ++generation
    reauthenticationPromise = Promise.allSettled([initializationPromise, refreshPromise, loginPromise, resetPromise, logoutPromise].filter(Boolean))
      .then(() => {
        if (version !== generation) throw changedSession()
        initialized = true
        publish({ status: 'unauthenticated' })
      }).finally(() => { reauthenticationPromise = null })
    return reauthenticationPromise
  }

  function login(credentials) {
    if (logoutPromise) return Promise.reject(changedSession())
    if (resetPromise) return Promise.reject(changedSession())
    if (loginPromise) return loginPromise
    const requestedGeneration = generation
    loginPromise = (async () => {
      if (reauthenticationPromise) await reauthenticationPromise
      await initialize()
      if (refreshPromise) await refreshPromise.catch(() => null)
      if (requestedGeneration !== generation) throw changedSession()
      if (snapshot.status === 'error') throw snapshot.error
      const version = ++generation
      publish({ status: 'initializing', operation: 'login' })
      try {
        const response = await api.login(credentials)
        if (version !== generation) throw changedSession()
        return publish({ status: 'authenticated', ...readSession(response, now()) })
      } catch (error) {
        if (version === generation) {
          if ([400, 401, 403, 409, 503].includes(error.status)) publish({ status: 'unauthenticated' })
          else publish({ status: 'error', error })
        }
        throw error
      }
    })().finally(() => { loginPromise = null })
    return loginPromise
  }

  function clearSession() {
    generation++
    initialized = true
    publish({ status: 'unauthenticated' })
  }

  // Public recovery remains usable after adding guards to login/register.
  function resetPassword(input) {
    if (logoutPromise) return Promise.reject(changedSession())
    if (resetPromise) return resetPromise
    const previous = snapshot
    const version = ++generation
    const pending = [initializationPromise, refreshPromise, loginPromise, reauthenticationPromise].filter(Boolean)
    initialized = true
    publish({ status: 'initializing', operation: 'reset' })
    resetPromise = (async () => {
      await Promise.allSettled(pending)
      if (version !== generation) throw changedSession()
      try {
        const response = await api.resetPassword(input)
        if (version !== generation) throw changedSession()
        if (typeof response?.message !== 'string') throw new ApiError(200, { code: 'API_ERROR', message: 'Không thể xác nhận kết quả đặt lại mật khẩu. Vui lòng thử đăng nhập lại.' })
        initialized = true
        publish({ status: 'unauthenticated' })
        return response
      } catch (error) {
        if (version === generation) {
          initialized = true
          if ([400, 401, 403, 409, 503].includes(error.status)) {
            if (previous.status === 'authenticated' && previous.expiresAt - now() > 30000) {
              publish({ status: 'authenticated', user: previous.user, accessToken: previous.accessToken, expiresAt: previous.expiresAt })
            } else publish({ status: 'unauthenticated' })
          } else publish({ status: 'error', error })
        }
        throw error
      }
    })().finally(() => { resetPromise = null })
    return resetPromise
  }

  function logout() {
    if (logoutPromise) return logoutPromise
    const previous = snapshot
    const version = ++generation
    const pending = [initializationPromise, refreshPromise, loginPromise, resetPromise, reauthenticationPromise].filter(Boolean)
    initialized = true
    publish(previous.status === 'authenticated'
      ? { status: 'authenticated', user: previous.user, accessToken: previous.accessToken, expiresAt: previous.expiresAt, operation: 'logout' }
      : { status: 'initializing', operation: 'logout' })
    logoutPromise = (async () => {
      await Promise.allSettled(pending)
      if (version !== generation) throw changedSession()
      try {
        await api.logout()
        if (version !== generation) throw changedSession()
        publish({ status: 'unauthenticated' })
      } catch (error) {
        if (version === generation) {
          if ([400, 401, 403, 409, 503].includes(error.status) && previous.status === 'authenticated' && previous.expiresAt - now() > 30000) {
            publish({ status: 'authenticated', user: previous.user, accessToken: previous.accessToken, expiresAt: previous.expiresAt })
          } else publish({ status: 'error', error })
        }
        throw error
      }
    })().finally(() => { logoutPromise = null })
    return logoutPromise
  }

  return {
    getSnapshot: () => snapshot,
    subscribe: listener => { listeners.add(listener); return () => listeners.delete(listener) },
    initialize, ensureSession, recoverSession, retryInitialization, beginLogin, login, clearSession, resetPassword, logout,
  }
}

export const sessionStore = createSessionStore()
