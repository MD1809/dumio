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
      <Route element={<ProtectedRoute />}>
        <Route element={<AppLayout />}>
          <Route path={PATHS.ROOT} element={<HomePage />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to={PATHS.ROOT} replace />} />
    </Routes>
  )
}
