import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { notify } from '../../utils/notifications.js'
import { LuArrowLeft, LuArrowRight, LuCircleCheck, LuEye, LuEyeOff, LuKeyRound, LuLoaderCircle, LuLock, LuMail, LuShieldCheck } from 'react-icons/lu'
import { authApi } from '../../api/authApi.js'
import { PATHS } from '../../route/paths.js'
import { getFieldErrors, normalizeEmail, validateAuthInput, validateEmail } from '../../utils/authValidation.js'

const steps = [
  ['Nhập email đăng ký', 'Nhận mã xác nhận qua email liên kết với dumio.'],
  ['Tạo mật khẩu mới', 'Nhập mã xác nhận và chọn mật khẩu mới ngay tại đây.'],
  ['Hoàn tất khôi phục', 'Đăng nhập và tiếp tục hành trình học tập của bạn.'],
]
const emptyFields = { code: '', newPassword: '', confirmPassword: '' }
const inputClassName = 'h-11 w-full min-w-0 scroll-mt-24 rounded-lg border border-border bg-surface pr-11 pl-11 text-base text-text transition-colors placeholder:text-placeholder hover:border-primary/50 focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/15 aria-invalid:border-error aria-invalid:focus:ring-error/15 sm:text-sm/normal'
const actionClassName = 'flex min-h-11 w-full items-center justify-center gap-2 rounded-lg bg-primary px-4 py-2 font-bold text-on-primary shadow-button transition-colors hover:bg-primary-hover disabled:opacity-60'

function RecoveryField({ name, label, error, hint, password, ...props }) {
  const [visible, setVisible] = useState(false)
  const Icon = name === 'email' ? LuMail : password ? LuLock : LuKeyRound
  return <div>
    <label className="mb-1.5 block text-xs/normal font-semibold" htmlFor={`recovery-${name}`}>{label} <span className="text-primary" aria-hidden="true">*</span></label>
    <div className="relative flex items-center">
      <Icon size={20} className="pointer-events-none absolute left-3.5 text-muted" aria-hidden="true" />
      <input {...props} className={inputClassName} id={`recovery-${name}`} name={name} type={password ? (visible ? 'text' : 'password') : props.type || 'text'} required aria-invalid={!!error} aria-describedby={[error && `recovery-${name}-error`, hint && `recovery-${name}-hint`].filter(Boolean).join(' ') || undefined} />
      {password && <button className="absolute right-0 grid size-11 place-items-center rounded-lg text-muted hover:text-primary-hover" type="button" aria-label={`${visible ? 'Ẩn' : 'Hiện'} ${label.toLowerCase()}`} aria-pressed={visible} onClick={() => setVisible(value => !value)}>{visible ? <LuEyeOff size={20} aria-hidden="true" /> : <LuEye size={20} aria-hidden="true" />}</button>}
    </div>
    {hint && <p className="mt-2 text-xs/normal text-muted" id={`recovery-${name}-hint`}>{hint}</p>}
    {error && <p className="mt-1.5 text-xs/normal text-error" id={`recovery-${name}-error`}>{error}</p>}
  </div>
}

export default function ForgotPasswordPage() {
  const [params] = useSearchParams()
  const [stage, setStage] = useState('email')
  const [email, setEmail] = useState(() => params.get('email') || '')
  const [sentEmail, setSentEmail] = useState('')
  const [fields, setFields] = useState(emptyFields)
  const [errors, setErrors] = useState({})
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState('')
  const [cooldown, setCooldown] = useState(0)
  const [expiresInSeconds, setExpiresInSeconds] = useState(0)
  const formRef = useRef(null)
  const pendingFocus = useRef(null)
  const isCoolingDown = cooldown > 0
  const title = stage === 'email' ? 'Quên mật khẩu?' : stage === 'reset' ? 'Tạo mật khẩu mới' : 'Đặt lại mật khẩu thành công'

  useEffect(() => {
    document.title = `${title} | Dumio`
  }, [title])

  useEffect(() => {
    if (!isCoolingDown) return
    const interval = setInterval(() => setCooldown(value => Math.max(0, value - 1)), 1000)
    return () => clearInterval(interval)
  }, [isCoolingDown])

  useEffect(() => {
    if (busy || !pendingFocus.current) return
    const target = pendingFocus.current === 'invalid'
      ? formRef.current?.querySelector('[aria-invalid="true"]')
      : document.getElementById(`recovery-${pendingFocus.current}`)
    target?.focus()
    pendingFocus.current = null
  })
  const focus = name => { pendingFocus.current = name }
  const focusErrors = () => { pendingFocus.current = 'invalid' }
  const updateField = event => {
    const { name, value } = event.target
    setFields(current => ({ ...current, [name]: name === 'code' ? value.replace(/\D/g, '').slice(0, 6) : value }))
    setErrors(current => ({ ...current, [name]: null }))
  }

  async function requestCode(event) {
    event?.preventDefault()
    if (busy || (stage === 'reset' && isCoolingDown)) return
    const normalizedEmail = stage === 'email' ? normalizeEmail(email) : sentEmail
    const invalid = validateEmail(normalizedEmail)
    setNotice('')
    setErrors(invalid ? { email: invalid } : {})
    if (invalid) { focus('email'); return }
    setBusy('send')
    try {
      const response = await authApi.forgotPassword({ email: normalizedEmail })
      setSentEmail(normalizedEmail)
      setFields(current => ({ ...current, code: '' }))
      setExpiresInSeconds(Number(response?.expiresInSeconds) || 0)
      setCooldown(60)
      setNotice(stage === 'reset' ? 'Đã gửi lại yêu cầu. Hãy sử dụng mã mới nhất trong email.' : 'Nếu tài khoản đủ điều kiện, mã xác nhận sẽ được gửi đến email của bạn.')
      setStage('reset')
      focus('code')
    } catch (failure) {
      const emailError = stage === 'email' ? getFieldErrors(failure).email : null
      setErrors(emailError ? { email: emailError } : {})
      if (emailError) {
        focusErrors()
      } else {
        const text = failure.message || 'Chưa thể gửi yêu cầu. Vui lòng thử lại.'
        notify.error(text, { toastId: text })
      }
    } finally { setBusy('') }
  }

  async function resetPassword(event) {
    event.preventDefault()
    if (busy) return
    const input = { email: sentEmail, ...fields }
    const invalid = validateAuthInput('reset-password', input)
    setErrors(invalid)
    if (Object.keys(invalid).length) { focusErrors(); return }
    setBusy('reset')
    try {
      await authApi.resetPassword(input)
      setFields(emptyFields)
      setNotice('')
      setErrors({})
      setCooldown(0)
      setStage('complete')
      focus('complete')
    } catch (failure) {
      const invalidFields = Object.fromEntries(Object.entries(getFieldErrors(failure)).filter(([name, text]) => Object.hasOwn(fields, name) && text))
      if (failure.code === 'INVALID_OTP') invalidFields.code = 'Mã xác nhận không hợp lệ hoặc đã hết hạn. Vui lòng kiểm tra mã hoặc gửi lại mã mới.'
      setErrors(invalidFields)
      if (Object.keys(invalidFields).length) {
        focusErrors()
      } else {
        const text = failure.message || 'Chưa thể đặt lại mật khẩu. Vui lòng thử lại.'
        notify.error(text, { toastId: text })
      }
    } finally { setBusy('') }
  }

  function changeEmail() {
    setStage('email')
    setFields(emptyFields)
    setErrors({})
    setNotice('')
    setSentEmail('')
    focus('email')
  }

  const currentStep = stage === 'email' ? 0 : stage === 'reset' ? 1 : 2
  const fieldProps = name => ({ name, value: fields[name], onChange: updateField, error: errors[name], disabled: !!busy })
  return <div className="grid overflow-hidden rounded-xl bg-surface shadow-panel lg:grid-cols-[5fr_7fr]">
    <aside className="flex flex-col justify-between gap-6 bg-surface-soft p-6 sm:p-8" aria-label="Các bước khôi phục tài khoản">
      <div>
        <span className="mb-4 inline-flex items-center gap-2 rounded-full bg-surface/75 px-3 py-1.5 text-xs/normal font-semibold text-brand"><LuShieldCheck size={16} aria-hidden="true" />Khôi phục tài khoản</span>
        <h2 className="text-[28px] leading-[1.25] font-bold tracking-[-.8px] sm:text-[30px]">Lấy lại mật khẩu,<br className="hidden lg:block" /> tiếp tục hành trình cùng <span className="text-brand">dumio</span></h2>
        <p className="mt-3 leading-[1.65] text-muted">Chỉ vài bước để lấy lại quyền truy cập và tiếp tục mục tiêu Anh ngữ của bạn.</p>
        <ol className="mt-6 space-y-4">
          {steps.map(([label, description], index) => <li className="flex items-start gap-3" key={label} aria-current={index === currentStep ? 'step' : undefined}>
            <span className={`grid size-8 shrink-0 place-items-center rounded-full text-xs/normal font-bold ${index <= currentStep ? 'bg-primary text-on-primary' : 'bg-surface/85 text-brand'}`} aria-hidden="true">{index < currentStep ? <LuCircleCheck size={18} /> : String(index + 1).padStart(2, '0')}</span>
            <div><h3 className="font-semibold">{label}</h3><p className="mt-1 text-xs/normal text-muted">{description}</p></div>
          </li>)}
        </ol>
      </div>
      {stage !== 'email' && <div className="flex items-start gap-3 rounded-lg bg-surface/80 p-4 text-xs/normal text-muted"><LuShieldCheck size={20} className="mt-0.5 text-brand" aria-hidden="true" /><p>Giữ mã xác nhận cho riêng bạn. dumio không yêu cầu bạn chia sẻ mật khẩu hoặc mã xác nhận với người khác.</p></div>}
    </aside>
    <section className="flex min-w-0 flex-col p-6 sm:p-8" aria-labelledby="recovery-title">
      <div className={`mx-auto w-full max-w-112 ${stage === 'complete' ? 'flex flex-1 flex-col' : ''}`}>
        <div className={`flex items-center ${stage === 'complete' ? 'gap-2 sm:gap-3' : 'gap-3'}`}>
          <span className={`grid shrink-0 place-items-center rounded-xl bg-surface-soft text-primary ${stage === 'complete' ? 'size-10 sm:size-12' : 'size-12'}`}>{stage === 'complete' ? <LuCircleCheck size={26} aria-hidden="true" /> : <LuKeyRound size={24} aria-hidden="true" />}</span>
          <h1 className={`min-w-0 leading-tight font-bold tracking-[-.6px] ${stage === 'complete' ? 'text-[clamp(12px,4vw,22px)] whitespace-nowrap sm:text-[22px]' : 'text-[28px] sm:text-[30px]'}`} id="recovery-title">{title}</h1>
        </div>

        {stage === 'complete' ? <div id="recovery-complete" className="mt-5 flex flex-1 scroll-mt-24 flex-col rounded-xl focus:outline-none" tabIndex={-1}>
          <p className="leading-relaxed text-muted" role="status">Mật khẩu của bạn đã được cập nhật. Bạn có thể đăng nhập bằng mật khẩu mới để tiếp tục học tập.</p>
          <div className="mt-auto pt-7"><Link className={`${actionClassName} hover:text-on-primary!`} to={PATHS.LOGIN}>Đăng nhập ngay<LuArrowRight size={18} aria-hidden="true" /></Link></div>
        </div> : <>
          <p className="mt-3 leading-[1.65] text-muted">{stage === 'email' ? 'Nhập email đã đăng ký. Chúng mình sẽ gửi mã xác nhận để bạn tạo mật khẩu mới.' : 'Nhập mã 6 số trong email và tạo mật khẩu mới cho tài khoản của bạn.'}</p>
          {stage === 'reset' && <div className="mt-5 flex flex-wrap items-center gap-x-3 gap-y-1 rounded-lg bg-surface-soft px-4 py-3 text-xs/normal"><span className="min-w-0 flex-1 [overflow-wrap:anywhere]">Email: <strong className="font-semibold">{sentEmail}</strong></span><button className="min-h-8 font-semibold text-primary hover:underline" type="button" disabled={!!busy} onClick={changeEmail}>Đổi email</button></div>}

          <form className="mt-6 space-y-4" ref={formRef} noValidate onSubmit={stage === 'email' ? requestCode : resetPassword} aria-busy={!!busy}>
            {stage === 'email' ? <RecoveryField name="email" label="Địa chỉ Email" type="email" autoComplete="email" autoCapitalize="none" spellCheck={false} placeholder="ví dụ: nguyenvana@gmail.com" value={email} onChange={event => { setEmail(event.target.value); setErrors({}) }} disabled={!!busy} error={errors.email} hint="Sử dụng email bạn đã dùng để tạo tài khoản dumio." /> : <>
              <RecoveryField {...fieldProps('code')} label="Mã xác nhận" inputMode="numeric" autoComplete="one-time-code" maxLength={6} placeholder="Nhập mã gồm 6 chữ số" hint={expiresInSeconds > 0 ? `Mã có hiệu lực trong ${Math.ceil(expiresInSeconds / 60)} phút kể từ khi gửi. Kiểm tra cả mục Spam hoặc Thư rác.` : 'Kiểm tra cả mục Spam hoặc Thư rác nếu chưa thấy email.'} />
              <RecoveryField {...fieldProps('newPassword')} label="Mật khẩu mới" password autoComplete="new-password" placeholder="Nhập mật khẩu mới" hint="Ít nhất 8 ký tự, gồm chữ hoa, chữ thường, chữ số và ký hiệu đặc biệt." />
              <RecoveryField {...fieldProps('confirmPassword')} label="Xác nhận mật khẩu mới" password autoComplete="new-password" placeholder="Nhập lại mật khẩu mới" />
            </>}
            <button className={actionClassName} type="submit" disabled={!!busy}>{busy ? <><LuLoaderCircle size={18} className="animate-spin" aria-hidden="true" />{busy === 'reset' ? 'Đang đặt lại mật khẩu...' : 'Đang gửi yêu cầu...'}</> : <>{stage === 'email' ? 'Gửi mã xác nhận' : 'Đặt lại mật khẩu'}<LuArrowRight size={18} aria-hidden="true" /></>}</button>
          </form>

          {stage === 'email' && <div className="mt-5 flex items-start gap-3 rounded-lg bg-surface-soft/65 p-4 text-xs/normal text-muted"><LuShieldCheck size={18} className="mt-0.5 text-primary" aria-hidden="true" /><p>Giữ mã xác nhận cho riêng bạn. dumio không yêu cầu bạn chia sẻ mật khẩu hoặc mã xác nhận với người khác.</p></div>}

          {stage === 'reset' && <div className="mt-4 text-center">
            <p className="text-xs/normal text-muted">Chưa nhận được mã?</p>
            <button className="inline-flex min-h-11 items-center justify-center px-3 text-xs/normal font-semibold text-primary hover:underline disabled:opacity-60" type="button" disabled={!!busy || isCoolingDown} onClick={() => requestCode()}>{isCoolingDown ? `Gửi lại mã sau ${cooldown}s` : 'Gửi lại mã xác nhận'}</button>
          </div>}
          {notice && <p className="mt-3 text-xs/normal leading-relaxed text-muted" role="status">{notice}</p>}
          <div className="mt-5 border-t border-border/30 pt-3 text-center"><Link className="inline-flex min-h-11 items-center justify-center gap-2 font-semibold text-primary" to={PATHS.LOGIN}><LuArrowLeft size={18} aria-hidden="true" />Trở về đăng nhập</Link></div>
        </>}
      </div>
    </section>
  </div>
}
