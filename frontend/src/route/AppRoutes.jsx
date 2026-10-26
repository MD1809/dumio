import { Navigate, Route, Routes } from 'react-router'
import AuthLayout from '../components/auth/AuthLayout.jsx'
import LoginPage from '../pages/auth/LoginPage.jsx'
import RegisterPage from '../pages/auth/RegisterPage.jsx'
import VerifyEmailPage from '../pages/auth/VerifyEmailPage.jsx'
import ForgotPasswordPage from '../pages/auth/ForgotPasswordPage.jsx'
import ResetPasswordPage from '../pages/auth/ResetPasswordPage.jsx'
import { PATHS } from './paths.js'

export default function AppRoutes() {
  return (
    <Routes>
      <Route element={<AuthLayout />}>
        <Route path={PATHS.LOGIN} element={<LoginPage />} />
        <Route path={PATHS.REGISTER} element={<RegisterPage />} />
        <Route path={PATHS.VERIFY_EMAIL} element={<VerifyEmailPage />} />
        <Route path={PATHS.FORGOT_PASSWORD} element={<ForgotPasswordPage />} />
        <Route path={PATHS.RESET_PASSWORD} element={<ResetPasswordPage />} />
      </Route>
      <Route path={PATHS.ROOT} element={<Navigate to={PATHS.LOGIN} replace />} />
      <Route path="*" element={<Navigate to={PATHS.LOGIN} replace />} />
    </Routes>
  )
}
