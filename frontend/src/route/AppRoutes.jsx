import { Navigate, Route, Routes } from 'react-router'
import AuthLayout from '../components/auth/AuthLayout.jsx'
import LoginPage from '../pages/auth/LoginPage.jsx'
import RegisterPage from '../pages/auth/RegisterPage.jsx'
import VerifyEmailPage from '../pages/auth/VerifyEmailPage.jsx'
import ForgotPasswordPage from '../pages/auth/ForgotPasswordPage.jsx'
import ResetPasswordPage from '../pages/auth/ResetPasswordPage.jsx'
import { PATHS } from './paths.js'
import ProtectedRoute from './ProtectedRoute.jsx'
import GuestRoute from './GuestRoute.jsx'
import AppLayout from '../components/layout/AppLayout.jsx'
import HomePage from '../pages/home/HomePage.jsx'
import AdminLayout from '../components/layout/AdminLayout.jsx'
import AdminHomePage from '../pages/admin/AdminHomePage.jsx'
import { useAuth } from '../auth/useAuth.js'
import { getRoleHomePath } from '../auth/roleRedirect.js'
import SessionGate from './SessionGate.jsx'

function DefaultRoute() {
  const { user } = useAuth()
  const destination = getRoleHomePath(user?.role)
  return destination ? <Navigate to={destination} replace /> : <SessionGate />
}

export default function AppRoutes() {
  return (
    <Routes>
      <Route element={<GuestRoute />}>
        <Route element={<AuthLayout />}>
          <Route path={PATHS.LOGIN} element={<LoginPage />} />
          <Route path={PATHS.REGISTER} element={<RegisterPage />} />
        </Route>
      </Route>
      <Route element={<AuthLayout />}>
        <Route path={PATHS.VERIFY_EMAIL} element={<VerifyEmailPage />} />
        <Route path={PATHS.FORGOT_PASSWORD} element={<ForgotPasswordPage />} />
        <Route path={PATHS.RESET_PASSWORD} element={<ResetPasswordPage />} />
      </Route>
      <Route element={<ProtectedRoute allowedRoles={['USER']} />}>
        <Route element={<AppLayout />}>
          <Route path={PATHS.ROOT} element={<HomePage />} />
        </Route>
      </Route>
      <Route element={<ProtectedRoute allowedRoles={['ADMIN']} />}>
        <Route path={PATHS.ADMIN_ROOT} element={<AdminLayout />}>
          <Route index element={<Navigate to={PATHS.ADMIN_DASHBOARD} replace />} />
          <Route path="dashboard" element={<AdminHomePage />} />
        </Route>
      </Route>
      <Route element={<ProtectedRoute />}>
        <Route path="*" element={<DefaultRoute />} />
      </Route>
    </Routes>
  )
}
