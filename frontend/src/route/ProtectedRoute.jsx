import { useEffect, useState } from 'react'
import { Navigate, Outlet, useLocation } from 'react-router'
import { useAuth } from '../auth/useAuth.js'
import SessionGate from './SessionGate.jsx'
import { PATHS } from './paths.js'
import { getRoleHomePath } from '../auth/roleRedirect.js'

export default function ProtectedRoute({ allowedRoles }) {
  const auth = useAuth()
  const { key } = useLocation()
  const [checkedEntry, setCheckedEntry] = useState(null)
  const ensureSession = auth.ensureSession

  useEffect(() => {
    let active = true
    ensureSession().catch(() => null).then(() => {
      if (active) setCheckedEntry(key)
    })
    return () => { active = false }
  }, [ensureSession, key])

  if (auth.status === 'unauthenticated') return <Navigate to={PATHS.LOGIN} replace />
  if (auth.status !== 'authenticated' || checkedEntry !== key) return <SessionGate />
  const destination = getRoleHomePath(auth.user?.role)
  if (!destination) return <SessionGate />
  if (allowedRoles && !allowedRoles.includes(auth.user.role)) return <Navigate to={destination} replace />
  return <Outlet />
}
