import { useState } from 'react'
import { Navigate, Outlet } from 'react-router'
import { useAuth } from '../auth/useAuth.js'
import SessionGate from './SessionGate.jsx'
import { PATHS } from './paths.js'

export default function GuestRoute() {
  const auth = useAuth()
  const [formOpened, setFormOpened] = useState(auth.status === 'unauthenticated')
  if (!formOpened && auth.status === 'unauthenticated') setFormOpened(true)

  if (auth.status === 'authenticated') return <Navigate to={PATHS.ROOT} replace />
  // Keep a submitted form mounted so failed login preserves its inputs/errors.
  if (!formOpened && auth.status !== 'unauthenticated') return <SessionGate />
  return <Outlet />
}
