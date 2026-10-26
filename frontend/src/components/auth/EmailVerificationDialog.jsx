import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Link } from 'react-router'
import { notify } from '../../utils/notifications.js'
import { LuArrowRight, LuCircleCheck, LuLoaderCircle, LuMailCheck, LuX } from 'react-icons/lu'
import { authApi } from '../../api/authApi.js'
import { PATHS } from '../../route/paths.js'
import { getFieldErrors, validateOtp } from '../../utils/authValidation.js'
import ToastNotifications from '../ToastNotifications.jsx'

const toastContainerId = 'email-verification'
const actionClassName = 'flex min-h-11 w-full items-center justify-center gap-2 rounded-lg bg-primary px-4 py-2 font-bold text-on-primary shadow-button transition-colors hover:bg-primary-hover disabled:cursor-not-allowed disabled:opacity-60'

export default function EmailVerificationDialog({ email, open = true, expiresInSeconds = 0, initialCooldown = 0, onClose, onVerified }) {
  const dialog = useRef(null)
  const inputs = useRef([])
  const pendingFocus = useRef(0)
  const [digits, setDigits] = useState(Array(6).fill(''))
  const [codeError, setCodeError] = useState('')
  const [emailError, setEmailError] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState('')
  const [verified, setVerified] = useState(false)
  const [cooldown, setCooldown] = useState(initialCooldown)
  const [expiry, setExpiry] = useState(expiresInSeconds)
  const isCoolingDown = cooldown > 0

  useEffect(() => {
    if (!open) return
    const element = dialog.current
    const previousFocus = document.activeElement
    const wasLocked = document.documentElement.classList.contains('overflow-hidden')
    element.showModal()
    pendingFocus.current = 0
    document.documentElement.classList.add('overflow-hidden')
    return () => {
      if (element.open) element.close()
      if (!wasLocked) document.documentElement.classList.remove('overflow-hidden')
      if (previousFocus?.isConnected) previousFocus.focus()
    }
  }, [open])

  useEffect(() => {
    if (open && !busy && !verified && pendingFocus.current !== null) {
      inputs.current[pendingFocus.current]?.focus()
      pendingFocus.current = null
    }
  })

  useEffect(() => {
    if (!isCoolingDown) return
    const interval = setInterval(() => setCooldown(value => Math.max(0, value - 1)), 1000)
    return () => clearInterval(interval)
  }, [isCoolingDown])

  function writeDigits(index, value) {
    const numbers = value.replace(/\D/g, '').slice(0, 6)
    const start = numbers.length === 6 ? 0 : index
    setDigits(current => {
      const next = [...current]
      if (!numbers) next[index] = ''
      else for (let offset = 0; offset < numbers.length && start + offset < 6; offset++) next[start + offset] = numbers[offset]
      return next
    })
    setCodeError('')
    if (numbers) pendingFocus.current = Math.min(start + numbers.length, 5)
  }

  function keyDown(event, index) {
    let next
    if (event.key === 'ArrowLeft') next = Math.max(0, index - 1)
    if (event.key === 'ArrowRight') next = Math.min(5, index + 1)
    if (event.key === 'Home') next = 0
    if (event.key === 'End') next = 5
    if (event.key === 'Backspace' && !digits[index] && index > 0) {
      next = index - 1
      setDigits(current => current.map((digit, position) => position === next ? '' : digit))
      setCodeError('')
    }
    if (next !== undefined) { event.preventDefault(); inputs.current[next]?.focus() }
  }

  function trapTab(event) {
    if (event.key === 'Escape') {
      event.preventDefault()
      closeDialog()
      return
    }
    if (event.key !== 'Tab') return
    const controls = Array.from(dialog.current.querySelectorAll('button:not(:disabled), input:not(:disabled), a[href], [tabindex]:not([tabindex="-1"])')).filter(element => element.getClientRects().length > 0)
    const first = controls[0]
    const last = controls.at(-1)
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
  }

  function handleFailure(failure) {
    const fields = getFieldErrors(failure)
    const invalidCode = failure.code === 'INVALID_OTP'
      ? 'Mã xác nhận không đúng hoặc đã hết hạn. Vui lòng thử lại hoặc gửi mã mới.'
      : fields.code
    setCodeError(invalidCode || '')
    setEmailError(fields.email || '')
    if (invalidCode) pendingFocus.current = 0
    if (!invalidCode && !fields.email) {
      const text = failure.message || 'Không thể xử lý yêu cầu. Vui lòng thử lại.'
      notify.error(text, { toastId: text, containerId: toastContainerId })
    }
  }

  async function verify(event) {
    event.preventDefault()
    if (busy) return
    const code = digits.join('')
    const invalid = validateOtp(code)
    setCodeError(invalid || '')
    setEmailError('')
    if (invalid) { pendingFocus.current = Math.max(0, digits.findIndex(digit => !digit)); return }
    setBusy('verify')
    try {
      await authApi.verifyEmail({ email, code })
      setVerified(true)
      setDigits(Array(6).fill(''))
      setCooldown(0)
      onVerified?.()
    } catch (failure) { handleFailure(failure) }
    finally { setBusy('') }
  }

  async function resend() {
    if (busy || isCoolingDown) return
    setBusy('resend')
    setCodeError('')
    setEmailError('')
    setNotice('')
    try {
      const response = await authApi.resendVerification({ email })
      setExpiry(Number(response?.expiresInSeconds) || 0)
      setCooldown(60)
      setDigits(Array(6).fill(''))
      setNotice('Đã gửi yêu cầu gửi lại mã. Hãy kiểm tra email và sử dụng mã mới nhất.')
      pendingFocus.current = 0
    } catch (failure) { handleFailure(failure) }
    finally { setBusy('') }
  }

  async function closeDialog() {
    if (busy) return
    if (verified) { onClose(); return }
    setBusy('cancel')
    try {
      await authApi.cancelRegistration()
      onClose()
    } catch (failure) { handleFailure(failure) }
    finally { setBusy('') }
  }

  if (typeof document === 'undefined') return null
  return createPortal(<dialog ref={dialog} aria-modal="true" aria-labelledby="verification-title" aria-describedby="verification-description" onKeyDown={trapTab} onCancel={event => { event.preventDefault(); closeDialog() }} className="fixed inset-0 m-auto max-h-[calc(100dvh_-_2rem)] w-[calc(100%_-_2rem)] max-w-md overflow-y-auto rounded-2xl border-0 bg-surface p-5 font-sans text-sm/normal text-text shadow-panel backdrop:bg-overlay/40 backdrop:backdrop-blur-[3px] sm:p-8 motion-reduce:[&_*]:transition-none">
    <button className="absolute top-2 right-2 grid size-11 cursor-pointer place-items-center rounded-lg text-muted hover:bg-surface-soft focus-visible:outline-2 focus-visible:outline-primary disabled:cursor-not-allowed disabled:opacity-50" aria-label="Đóng xác minh email" type="button" disabled={!!busy} onClick={closeDialog}><LuX size={20} aria-hidden="true" /></button>
    <div className="mb-5 grid size-12 place-items-center rounded-xl bg-surface-soft text-primary">{verified ? <LuCircleCheck size={26} aria-hidden="true" /> : <LuMailCheck size={26} aria-hidden="true" />}</div>
    <h2 className="pr-5 text-2xl leading-tight font-bold tracking-tight" id="verification-title">{verified ? 'Email đã được xác minh' : 'Xác minh email của bạn'}</h2>
    <p className="mt-3 leading-relaxed text-muted" id="verification-description">{verified ? 'Tài khoản của bạn đã sẵn sàng. Đăng nhập để bắt đầu hành trình học tập cùng dumio.' : <>Nhập mã gồm 6 chữ số đã gửi đến <strong className="font-semibold text-text [overflow-wrap:anywhere]">{email}</strong>.</>}</p>
    {emailError && <p className="mt-2 text-xs/normal text-error" role="alert">{emailError}</p>}
    {verified ? <div className="mt-6">
      <p className="sr-only" role="status">Xác minh email thành công.</p>
      <Link className={`${actionClassName} hover:text-on-primary!`} to={PATHS.LOGIN}>Đăng nhập ngay<LuArrowRight size={18} aria-hidden="true" /></Link>
    </div> : <>
      <form className="mt-6" onSubmit={verify} noValidate aria-busy={!!busy}>
        <fieldset disabled={!!busy}>
          <legend className="mb-2 text-xs/normal font-semibold">Mã xác nhận</legend>
          <div className="grid grid-cols-6 gap-1.5 sm:gap-3">
            {digits.map((digit, index) => <input key={index} ref={element => { inputs.current[index] = element }} className="h-12 w-full min-w-0 rounded-lg border border-border bg-surface text-center text-xl font-semibold text-text outline-none transition-colors hover:border-primary/50 focus:border-primary focus:ring-2 focus:ring-primary/15 aria-invalid:border-error aria-invalid:focus:ring-error/15 sm:h-14" type="text" inputMode="numeric" autoComplete={index === 0 ? 'one-time-code' : 'off'} maxLength={6} value={digit} name={`verification-digit-${index}`} aria-label={`Chữ số thứ ${index + 1} trên 6`} aria-invalid={!!codeError} aria-describedby={codeError ? 'verification-code-error' : 'verification-code-hint'} onChange={event => writeDigits(index, event.target.value)} onFocus={event => event.target.select()} onKeyDown={event => keyDown(event, index)} onPaste={event => { event.preventDefault(); writeDigits(index, event.clipboardData.getData('text')) }} />)}
          </div>
          {codeError && <p className="mt-2 text-xs/normal text-error" id="verification-code-error">{codeError}</p>}
          <p className="mt-3 text-xs/normal text-muted" id="verification-code-hint">{expiry > 0 ? `Mã có hiệu lực trong ${Math.ceil(expiry / 60)} phút kể từ khi gửi. ` : ''}Kiểm tra cả mục Spam hoặc Thư rác nếu chưa thấy email.</p>
        </fieldset>
        <button className={`${actionClassName} mt-5`} type="submit" disabled={!!busy}>{busy === 'verify' ? <><LuLoaderCircle size={18} className="animate-spin motion-reduce:animate-none" aria-hidden="true" />Đang xác minh...</> : <>Xác minh email<LuArrowRight size={18} aria-hidden="true" /></>}</button>
      </form>
      <div className="mt-4 text-center text-xs/normal text-muted">
        <p>Chưa nhận được mã?</p>
        <button className="inline-flex min-h-11 cursor-pointer items-center justify-center gap-2 px-3 font-semibold text-primary hover:underline disabled:cursor-not-allowed disabled:opacity-60" type="button" disabled={!!busy || isCoolingDown} onClick={resend}>{busy === 'resend' ? <><LuLoaderCircle size={16} className="animate-spin motion-reduce:animate-none" aria-hidden="true" />Đang gửi lại...</> : isCoolingDown ? `Gửi lại mã sau ${cooldown}s` : 'Gửi lại mã xác nhận'}</button>
      </div>
      {notice && <p className="mt-2 text-xs/normal text-muted" role="status">{notice}</p>}
    </>}
    <ToastNotifications containerId={toastContainerId} />
  </dialog>, document.body)
}
