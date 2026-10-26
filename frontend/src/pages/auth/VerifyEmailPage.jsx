import { useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { LuMail, LuMailCheck } from 'react-icons/lu'
import { PATHS } from '../../route/paths.js'
import { normalizeEmail, validateEmail } from '../../utils/authValidation.js'
import EmailVerificationDialog from '../../components/auth/EmailVerificationDialog.jsx'

export default function VerifyEmailPage() {
  const [params] = useSearchParams()
  const [email, setEmail] = useState(() => params.get('email') || '')
  const [error, setError] = useState('')
  const [verification, setVerification] = useState(() => {
    const initialEmail = normalizeEmail(params.get('email') || '')
    return validateEmail(initialEmail) ? null : { email: initialEmail, open: true, verified: false }
  })
  const emailInput = useRef(null)

  function openVerification(event) {
    event.preventDefault()
    const normalizedEmail = normalizeEmail(email)
    const invalid = validateEmail(normalizedEmail)
    setError(invalid || '')
    if (invalid) { emailInput.current?.focus(); return }
    setVerification(current => current?.email === normalizedEmail ? { ...current, open: true } : { email: normalizedEmail, open: true, verified: false })
  }

  return (
    <>
      <span className="mb-5 grid size-12 place-items-center rounded-xl bg-surface-soft text-primary"><LuMailCheck size={26} aria-hidden="true" /></span>
      <h1 className="text-[28px] leading-tight font-bold tracking-tight text-text">Xác minh email</h1>
      {verification?.verified ? <div className="mt-4">
        <p className="text-muted" role="status">Email của bạn đã được xác minh. Đăng nhập để bắt đầu học tập.</p>
        <Link className="mt-6 flex min-h-11 items-center justify-center rounded-lg bg-primary px-4 py-2 font-semibold text-on-primary hover:bg-primary-hover hover:text-on-primary!" to={PATHS.LOGIN}>Đăng nhập ngay</Link>
      </div> : <>
        <p className="mt-3 leading-relaxed text-muted">Nhập email đã đăng ký để mở popup xác minh. Bạn có thể nhập mã đã nhận hoặc yêu cầu gửi lại mã trong popup.</p>
        <form className="mt-6 space-y-5" onSubmit={openVerification} noValidate>
          <div>
            <label className="mb-1.5 block text-xs/normal font-semibold" htmlFor="verification-email">Địa chỉ Email</label>
            <div className="relative flex items-center"><LuMail size={20} className="pointer-events-none absolute left-3.5 text-muted" aria-hidden="true" /><input ref={emailInput} className="h-11 w-full min-w-0 rounded-lg border border-border bg-surface pr-4 pl-11 text-base placeholder:text-placeholder hover:border-primary/50 focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/15 aria-invalid:border-error aria-invalid:focus:ring-error/15 sm:text-sm/normal" id="verification-email" name="email" type="email" autoComplete="email" autoCapitalize="none" spellCheck={false} placeholder="name@example.com" required value={email} onChange={event => { setEmail(event.target.value); setError('') }} aria-invalid={!!error} aria-describedby={error ? 'verification-email-error' : undefined} /></div>
            {error && <p className="mt-1.5 text-xs/normal text-error" id="verification-email-error">{error}</p>}
          </div>
          <button className="flex min-h-11 w-full items-center justify-center rounded-lg bg-primary px-4 py-2 font-semibold text-on-primary hover:bg-primary-hover" type="submit">Nhập mã xác nhận</button>
        </form>
        <p className="mt-5 text-center text-xs/normal text-muted">Chưa đăng ký? <Link className="inline-block py-2 font-semibold text-primary" to={PATHS.REGISTER}>Tạo tài khoản</Link></p>
        <Link className="mt-2 flex min-h-11 items-center justify-center text-sm/normal font-semibold text-primary" to={PATHS.LOGIN}>Trở về đăng nhập</Link>
      </>}
      {verification && <EmailVerificationDialog open={verification.open} email={verification.email} onClose={() => setVerification(current => current?.verified ? { ...current, open: false } : null)} onVerified={() => setVerification(current => ({ ...current, verified: true }))} />}
    </>
  )
}
