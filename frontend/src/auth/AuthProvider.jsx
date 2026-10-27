import { useEffect, useMemo, useSyncExternalStore } from 'react'
import { AuthContext } from './AuthContext.js'
import { sessionStore } from './sessionStore.js'

export default function AuthProvider({ children, session = sessionStore }) {
  const snapshot = useSyncExternalStore(session.subscribe, session.getSnapshot, session.getSnapshot)

  useEffect(() => {
    session.initialize()
    const checkExpiry = () => {
      if (!document.hidden && session.getSnapshot().status === 'authenticated') {
        session.ensureSession().catch(() => { /* Error state offers explicit recovery. */ })
      }
    }
    window.addEventListener('focus', checkExpiry)
    document.addEventListener('visibilitychange', checkExpiry)
    return () => {
      window.removeEventListener('focus', checkExpiry)
      document.removeEventListener('visibilitychange', checkExpiry)
    }
  }, [session])

  const value = useMemo(() => ({
    ...snapshot, initialize: session.initialize, ensureSession: session.ensureSession, recoverSession: session.recoverSession,
    login: session.login, retryInitialization: session.retryInitialization,
    beginLogin: session.beginLogin, clearSession: session.clearSession,
  }), [snapshot, session])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
