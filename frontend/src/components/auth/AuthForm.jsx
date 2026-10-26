import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { authApi } from '../../api/authApi.js'
import { PATHS } from '../../route/paths.js'
import { getFieldErrors, normalizeAuthInput, validateAuthInput } from '../../utils/authValidation.js'
import { LuArrowRight, LuEye, LuEyeOff, LuLock, LuMail, LuShieldCheck, LuUserRound } from 'react-icons/lu'
import { FcGoogle } from 'react-icons/fc'
import { notify } from '../../utils/notifications.js'
import AuthShowcase from './AuthShowcase.jsx'
import EmailVerificationDialog from './EmailVerificationDialog.jsx'

function rememberedEmail() {
  try { return typeof window === 'undefined' ? '' : window.localStorage.getItem('dumio.rememberedEmail') || '' }
  catch { return '' }
}

function Field({ name, label, icon: Icon, error, hint, password, ...props }) {
  const [visible, setVisible] = useState(false)
  return <div>
    <label className="mb-1.5 block text-xs/normal font-semibold" htmlFor={`auth-${name}`}>{label} <span className="text-primary" aria-hidden="true">*</span></label>
    <div className="relative flex items-center">
      <Icon className="pointer-events-none absolute left-3.5 text-muted" size={20} aria-hidden="true" />
      <input {...props} className="h-11 w-full min-w-0 rounded-lg border border-border bg-surface px-11 text-base text-text transition-colors duration-150 placeholder:text-placeholder placeholder:opacity-100 hover:border-primary/50 focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/15 aria-invalid:border-error aria-invalid:focus:ring-error/15 sm:text-sm/normal" id={`auth-${name}`} name={name} type={password ? (visible ? 'text' : 'password') : props.type}
        aria-invalid={!!error} aria-describedby={[error && `error-${name}`, hint && `hint-${name}`].filter(Boolean).join(' ') || undefined} />
      {password && <button className="absolute top-0 right-0 grid size-11 place-items-center rounded-lg border-0 bg-transparent text-muted hover:text-primary-hover" type="button" aria-label={`${visible ? 'Ẩn' : 'Hiện'} ${label.toLowerCase()}`} aria-pressed={visible} onClick={() => setVisible(!visible)}>{visible ? <LuEyeOff size={20} aria-hidden="true" /> : <LuEye size={20} aria-hidden="true" />}</button>}
    </div>
    {hint && <p className="mt-1.5 text-[11px] text-muted" id={`hint-${name}`}>{hint}</p>}
    {error && <p className="mt-1.5 text-xs/normal text-error" id={`error-${name}`}>{error}</p>}
  </div>
}

export default function AuthForm({ mode }) {
  const register = mode === 'register'
  const [values, setValues] = useState(() => ({ username: '', email: register ? '' : rememberedEmail(), password: '', confirmPassword: '' }))
  const [remember, setRemember] = useState(() => !register && !!rememberedEmail())
  const [consent, setConsent] = useState(false)
  const [errors, setErrors] = useState({})
  const [busy, setBusy] = useState(false)
  const [verification, setVerification] = useState(null)
  const form = useRef(null)
  const pendingFocus = useRef(false)
  useEffect(() => {
    if (busy || !pendingFocus.current) return
    form.current?.querySelector('[aria-invalid="true"]')?.focus()
    pendingFocus.current = false
  })
  const update = event => {
    const { name, value } = event.target
    setValues(current => ({ ...current, [name]: value }))
    setErrors(current => ({ ...current, [name]: null }))
    setVerification(null)
  }
  const fieldProps = name => ({ name, value: values[name], onChange: update, error: errors[name], required: true, disabled: busy })
  const focusErrors = () => { pendingFocus.current = true }

  async function submit(event) {
    event.preventDefault()
    if (busy) return
    const input = normalizeAuthInput(values)
    const invalid = validateAuthInput(mode, input)
    if (register && !consent) invalid.consent = 'Vui lòng đồng ý với điều khoản và chính sách bảo mật.'
    setErrors(invalid)
    if (Object.keys(invalid).length) { focusErrors(); return }
    setBusy(true)
    try {
      if (register) {
        const response = await authApi.register(input)
        if (response?.verificationRequired !== false) {
          setVerification({ email: input.email, expiresInSeconds: Number(response?.expiresInSeconds) || 0 })
        } else {
          setVerification(null)
          notify.success('Tạo tài khoản thành công. Bạn có thể đăng nhập ngay.')
        }
      } else {
        await authApi.login({ email: input.email, password: input.password })
        try {
          if (remember) window.localStorage.setItem('dumio.rememberedEmail', input.email)
          else window.localStorage.removeItem('dumio.rememberedEmail')
        } catch { /* Login remains usable when browser storage is unavailable. */ }
        notify.success('Đăng nhập thành công.', { toastId: 'login-success' })
      }
      setValues(current => ({ ...current, password: '', confirmPassword: '' }))
    } catch (error) {
      const fieldErrors = Object.fromEntries(Object.entries(getFieldErrors(error)).filter(([name, text]) => Object.hasOwn(values, name) && text))
      setErrors(fieldErrors)
      if (Object.keys(fieldErrors).length) {
        focusErrors()
      } else {
        const text = error.message || 'Không thể xử lý yêu cầu. Vui lòng thử lại.'
        notify.error(text, { toastId: text })
      }
    } finally { setBusy(false) }
  }

  return <div className="group/auth grid min-h-165 grid-cols-1 overflow-hidden rounded-xl bg-surface shadow-panel lg:grid-cols-[5fr_7fr]" data-register={register}>
    <AuthShowcase register={register} />
    <section className={`flex min-w-0 items-center px-6 py-8 sm:px-8 sm:py-10 ${register ? 'lg:p-12' : 'lg:p-14'} lg:max-[1101px]:p-9`} aria-labelledby="auth-title">
      <div className="mx-auto w-full lg:max-w-112">
        <div className={register ? 'mb-6' : 'mb-8'}>
          <h1 className="mb-2.5 text-[26px] leading-[1.27] font-bold tracking-[-.6px] sm:text-[30px]" id="auth-title">{register ? 'Tạo tài khoản mới' : 'Đăng nhập tài khoản'}</h1>
          <p className="text-muted">{register ? 'Bắt đầu hành trình nâng cao phản xạ tiếng Anh chỉ trong 1 phút.' : 'Chào mừng bạn quay trở lại! Vui lòng nhập thông tin để tiếp tục chuỗi bài học hôm nay.'}</p>
        </div>
        <button className="flex min-h-11 w-full items-center justify-center gap-2.5 rounded-lg border border-border/25 bg-surface text-xs/normal font-semibold text-text shadow-google disabled:text-muted" type="button" disabled aria-describedby="google-unavailable"><FcGoogle size={20} aria-hidden="true" />{register ? 'Đăng ký nhanh với Google' : 'Tiếp tục với Google'}</button>
        <div className="my-[26px] flex items-center gap-3 text-center text-[11px] font-semibold tracking-[.5px] text-muted uppercase before:h-px before:flex-1 before:bg-surface-muted before:content-[''] after:h-px after:flex-1 after:bg-surface-muted after:content-['']"><span className="max-w-4/5">Hoặc {register ? 'đăng ký' : 'đăng nhập'} bằng email</span></div>
        <form className={`flex flex-col ${register ? 'gap-4' : 'gap-5'}`} ref={form} noValidate onSubmit={submit} aria-busy={busy}>
          {register && <Field {...fieldProps('username')} label="Họ và tên" icon={LuUserRound} type="text" autoComplete="name" placeholder="Ví dụ: Nguyễn Văn A" />}
          <Field {...fieldProps('email')} label="Địa chỉ Email" icon={LuMail} type="email" autoComplete="email" autoCapitalize="none" spellCheck={false} placeholder={register ? 'name@example.com' : 'ví dụ: nguyenvana@gmail.com'} />
          <Field {...fieldProps('password')} label="Mật khẩu" icon={LuLock} password autoComplete={register ? 'new-password' : 'current-password'} placeholder={register ? 'Tối thiểu 8 ký tự' : 'Nhập mật khẩu của bạn'} />
          {register && <>
            <Field {...fieldProps('confirmPassword')} label="Xác nhận mật khẩu" icon={LuShieldCheck} password autoComplete="new-password" placeholder="Nhập lại mật khẩu bảo mật" />
            <div><label className="flex cursor-pointer items-start gap-2.5 text-xs/normal text-muted" htmlFor="auth-consent"><input className="mt-[3px] size-4 shrink-0 cursor-pointer appearance-auto accent-primary" id="auth-consent" name="consent" type="checkbox" checked={consent} disabled={busy} onChange={event => { setConsent(event.target.checked); setErrors(current => ({ ...current, consent: null })) }} required aria-invalid={!!errors.consent} aria-describedby={errors.consent ? 'error-consent' : undefined} /><span>Tôi đồng ý với <strong className="font-semibold text-brand">Điều khoản dịch vụ</strong> và <strong className="font-semibold text-brand">Chính sách bảo mật</strong> của dumio.</span></label>{errors.consent && <p className="mt-1.5 text-xs/normal text-error" id="error-consent">{errors.consent}</p>}</div>
          </>}
          {!register && <div className="flex items-center justify-between gap-3 text-xs/normal"><label className="flex min-h-8 cursor-pointer items-center gap-2 text-muted"><input className="size-4 shrink-0 cursor-pointer appearance-auto accent-primary" type="checkbox" checked={remember} onChange={event => setRemember(event.target.checked)} disabled={busy} />Ghi nhớ email</label><Link className="py-2 font-semibold text-primary" to={PATHS.FORGOT_PASSWORD}>Quên mật khẩu?</Link></div>}
          <button className="flex min-h-11 w-full items-center justify-center gap-2.5 rounded-lg border-0 bg-primary font-bold text-on-primary shadow-button transition-colors duration-150 hover:bg-primary-hover disabled:opacity-65" type="submit" disabled={busy}>{busy ? <><span className="size-4 animate-spin rounded-full border-2 border-on-primary/40 border-t-on-primary [animation-duration:.8s]" />Đang xử lý...</> : <>{register ? 'Tạo tài khoản học tập' : 'Đăng nhập ngay'}<LuArrowRight size={20} aria-hidden="true" /></>}</button>
        </form>
        <p className="mt-8 text-center text-[13px] text-muted sm:text-sm/normal">{register ? 'Đã có tài khoản dumio?' : 'Bạn chưa có tài khoản?'} <Link className="inline-block py-2 pl-1 font-bold text-primary" to={register ? PATHS.LOGIN : PATHS.REGISTER}>{register ? 'Đăng nhập ngay' : 'Đăng ký ngay'}</Link></p>
      </div>
    </section>
    {verification && <EmailVerificationDialog email={verification.email} expiresInSeconds={verification.expiresInSeconds} initialCooldown={60} onClose={() => setVerification(null)} />}
  </div>
}
