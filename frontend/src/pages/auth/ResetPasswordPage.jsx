import { Navigate, useLocation } from 'react-router'
import { PATHS } from '../../route/paths.js'

export default function ResetPasswordPage() {
  const { search } = useLocation()
  return <Navigate to={{ pathname: PATHS.FORGOT_PASSWORD, search }} replace />
}