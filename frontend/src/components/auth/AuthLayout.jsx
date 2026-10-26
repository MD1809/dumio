import { useEffect, useRef } from 'react'
import { Link, NavLink, Outlet, useLocation } from 'react-router'
import { PATHS } from '../../route/paths.js'
import { LuChevronRight, LuHouse, LuUserRound } from 'react-icons/lu'

const copyrightYear = new Date().getFullYear()
const pageLabels = {
  [PATHS.LOGIN]: 'Đăng nhập tài khoản',
  [PATHS.REGISTER]: 'Đăng ký tài khoản',
  [PATHS.FORGOT_PASSWORD]: 'Quên mật khẩu',
  [PATHS.VERIFY_EMAIL]: 'Xác minh email',
}

export default function AuthLayout() {
  const { pathname } = useLocation()
  const main = useRef(null)

  useEffect(() => {
    document.title = `${main.current?.querySelector('h1')?.textContent ?? 'Tài khoản'} | Dumio`
    main.current?.focus({ preventScroll: true })
  }, [pathname])

  return (
    <div className="flex min-h-dvh flex-col bg-background font-sans text-sm/normal leading-normal text-text [&_a]:no-underline [&_a:hover]:text-brand [&_svg]:shrink-0 [&_button]:cursor-pointer [&_button:disabled]:cursor-not-allowed [&_:is(button,input):focus-visible]:outline-2 [&_:is(button,input):focus-visible]:outline-primary [&_:is(button,input):focus-visible]:outline-offset-3 motion-reduce:[&_*]:animate-none motion-reduce:[&_*]:transition-none">
      <a className="fixed top-2 left-2 z-100 -translate-y-[200%] bg-surface px-4 py-3 focus:translate-y-0" href="#auth-main">Đến nội dung chính</a>
      <header className="sticky top-0 z-10 flex h-16 items-center justify-between gap-3 bg-background/93 px-5 shadow-header backdrop-blur-lg sm:gap-4 sm:px-[max(24px,calc((100%_-_1184px)/2))]">
        <Link className="text-2xl leading-[1.4] font-extrabold tracking-[-1px] text-brand" to={PATHS.ROOT} aria-label="Dumio — Trang chủ">dumio</Link>
          <nav className="ml-auto flex items-center gap-4 sm:ml-0 sm:gap-6" aria-label="Điều hướng tài khoản">
            <NavLink className="inline-flex min-h-11 items-center text-xs/normal font-semibold text-muted aria-[current=page]:font-bold aria-[current=page]:text-brand sm:text-sm/normal" to={PATHS.LOGIN}>Đăng nhập</NavLink>
            <NavLink className="inline-flex min-h-11 items-center text-xs/normal font-semibold text-muted aria-[current=page]:font-bold aria-[current=page]:text-brand sm:text-sm/normal" to={PATHS.REGISTER}>Đăng ký tài khoản</NavLink>
          </nav>
          <span className="hidden size-8 place-items-center rounded-full bg-brand text-on-primary sm:grid"><LuUserRound size={20} aria-hidden="true" /></span>
      </header>
      <div className="flex flex-1 flex-col items-center px-4 pt-6 pb-8 sm:px-6 sm:pt-8 sm:pb-12 lg:p-16 lg:px-12">
        <main id="auth-main" className={`w-full focus:outline-none focus-visible:outline-2 focus-visible:outline-primary focus-visible:outline-offset-6 ${pathname === PATHS.VERIFY_EMAIL ? 'max-w-120 rounded-2xl border border-border/40 bg-surface p-6 [overflow-wrap:anywhere] sm:p-10' : `max-w-180 ${pathname === PATHS.LOGIN ? 'lg:max-w-296' : 'lg:max-w-256'}`}`} ref={main} tabIndex={-1}>
          <nav className={`mb-5 flex flex-wrap items-center gap-2 text-xs/normal font-semibold text-muted ${pathname === PATHS.REGISTER ? 'sm:mb-10 sm:text-sm/normal' : 'sm:mb-8'}`} aria-label="Đường dẫn">
            <Link className="inline-flex min-h-6 items-center gap-[5px]" to={PATHS.ROOT}><LuHouse size={16} aria-hidden="true" />Trang chủ</Link>
            <LuChevronRight size={12} className="text-border" aria-hidden="true" />
            {pathname === PATHS.FORGOT_PASSWORD && <>
              <Link className="inline-flex min-h-6 items-center" to={PATHS.LOGIN}>Đăng nhập</Link>
              <LuChevronRight size={12} className="text-border" aria-hidden="true" />
            </>}
            <span className="text-brand" aria-current="page">{pageLabels[pathname] || 'Tài khoản'}</span>
          </nav>
          <Outlet />
        </main>
      </div>
      <footer className="flex flex-col items-center justify-between gap-3 bg-surface-soft px-[max(24px,calc((100%_-_1184px)/2))] py-6 text-center text-xs/normal text-muted shadow-footer lg:flex-row lg:gap-6">
        <div className="flex flex-wrap items-center justify-center gap-1.5 sm:flex-nowrap"><strong className="text-lg/normal font-bold tracking-[-.5px] text-brand">dumio</strong><span>— Học tiếng anh thích ứng và thông minh. © {copyrightYear}.</span></div><div className="flex flex-wrap items-center justify-center gap-4 sm:flex-nowrap sm:gap-6"><span>Điều khoản dịch vụ</span><span>Chính sách bảo mật</span><span>Hỗ trợ</span></div>
      </footer>
    </div>
  )
}
