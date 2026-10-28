import { useEffect, useRef, useState } from 'react'
import { Link, NavLink, Outlet, useLocation } from 'react-router'
import { LuBell, LuBookOpen, LuMenu, LuSearch, LuSettings, LuX } from 'react-icons/lu'
import AccountMenu from './AccountMenu.jsx'
const copyrightYear = new Date().getFullYear()
const navigationClass = 'flex min-h-11 items-center gap-3 rounded-lg px-4 py-2 text-sm font-medium text-on-primary/80 transition-colors hover:bg-on-primary/10 hover:text-on-primary'

function SidebarContent({ config, onNavigate, mobile = false }) {
  return <div className="flex h-full min-h-0 flex-col justify-between">
    <div>
      <div className="flex items-start justify-between gap-2 px-6 pt-6 pb-5">
        <div><Link className="text-2xl font-extrabold tracking-tight text-on-primary" to={config.homePath} onClick={() => onNavigate(config.homePath)} aria-label={`Dumio — ${config.homeLabel}`}>dumio</Link><p className="mt-1 text-[11px] leading-relaxed text-border">{config.sidebarDescription}</p></div>
        {mobile && <button className="-mr-3 grid size-11 cursor-pointer place-items-center rounded-lg text-on-primary/80 hover:bg-on-primary/10" type="button" aria-label="Đóng điều hướng" onClick={() => onNavigate()}><LuX size={21} aria-hidden="true" /></button>}
      </div>
      <nav className="space-y-1 px-2" aria-label={mobile ? `${config.navigationLabel} trên điện thoại` : config.navigationLabel}>
        {config.navigation.map(({ label, icon: Icon, href }) => href.startsWith('#') ? <a className={navigationClass} key={href} href={href} onClick={() => onNavigate(href)}><Icon size={20} aria-hidden="true" />{label}</a> : <NavLink className={({ isActive }) => isActive ? 'flex min-h-11 items-center gap-3 rounded-lg bg-surface px-4 py-2 text-sm font-bold text-brand shadow-stat' : navigationClass} key={href} to={href} end onClick={() => onNavigate(href)}><Icon size={20} aria-hidden="true" />{label}</NavLink>)}
      </nav>
    </div>
    <div className="px-2 pt-6 pb-2">
      <button className={navigationClass + ' mb-1 w-full cursor-not-allowed text-left'} type="button" disabled title="Chưa khả dụng"><LuSettings size={20} aria-hidden="true" />Cài đặt</button>
    </div>
  </div>
}

export default function DashboardLayout({ config }) {
  const [menuOpen, setMenuOpen] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const [scrolled, setScrolled] = useState(false)
  const toggle = useRef(null)
  const drawer = useRef(null)
  const main = useRef(null)
  const { pathname } = useLocation()

  useEffect(() => {
    const updateScroll = () => setScrolled(window.scrollY > 8)
    updateScroll()
    window.addEventListener('scroll', updateScroll, { passive: true })
    return () => window.removeEventListener('scroll', updateScroll)
  }, [])

  useEffect(() => {
    document.title = config.pageTitle
    main.current?.focus({ preventScroll: true })
  }, [pathname, config.pageTitle])

  useEffect(() => {
    if (!menuOpen) return
    const element = drawer.current
    const trigger = toggle.current
    const wasLocked = document.documentElement.classList.contains('overflow-hidden')
    element.showModal()
    document.documentElement.classList.add('overflow-hidden')
    return () => {
      if (element.open) element.close()
      if (!wasLocked) document.documentElement.classList.remove('overflow-hidden')
      trigger?.focus()
    }
  }, [menuOpen])

  const navigateSection = href => {
    setMenuOpen(false)
    if (href && !href.startsWith('#')) window.scrollTo(0, 0)
    else if (href) requestAnimationFrame(() => document.getElementById(href.slice(1))?.focus({ preventScroll: true }))
  }

  return <div className="min-h-dvh bg-background font-sans text-sm leading-normal text-text lg:pl-65 [&_svg]:shrink-0 [&_:is(a,button,input):focus-visible]:outline-2 [&_:is(a,button,input):focus-visible]:outline-primary [&_:is(a,button,input):focus-visible]:outline-offset-3 motion-reduce:[&_*]:transition-none">
    <a className="fixed top-2 left-2 z-50 -translate-y-[200%] rounded-lg bg-primary px-4 py-3 text-on-primary focus:translate-y-0" href={`#${config.mainId}`}>Đến nội dung chính</a>
    <aside className="fixed inset-y-0 left-0 z-40 hidden w-65 border-r border-brand-deep bg-brand shadow-header lg:block [&_:is(a,button):focus-visible]:outline-on-primary" aria-label={config.navigationLabel}><SidebarContent config={config} onNavigate={navigateSection} /></aside>
    <dialog className="fixed inset-y-0 left-0 m-0 h-dvh max-h-none w-72 max-w-[calc(100dvw_-_2rem)] border-0 bg-brand p-0 text-on-primary shadow-panel backdrop:bg-overlay/40 backdrop:backdrop-blur-[2px] [&_:is(a,button):focus-visible]:outline-on-primary" ref={drawer} id={config.menuId} aria-label={config.navigationLabel} onCancel={event => { event.preventDefault(); setMenuOpen(false) }} onClick={event => {
      if (event.target !== event.currentTarget) return
      const bounds = event.currentTarget.getBoundingClientRect()
      if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) setMenuOpen(false)
    }}><SidebarContent config={config} onNavigate={navigateSection} mobile /></dialog>
    <header className={`fixed top-0 right-0 left-0 z-30 ${config.headerTitle ? 'h-20' : 'h-30 lg:h-20'} border-b border-brand/10 bg-surface transition-shadow duration-200 motion-reduce:transition-none lg:left-65 ${scrolled ? 'shadow-header-scrolled' : 'shadow-header'}`}>
      <div className={config.headerTitle ? 'mx-auto flex h-full max-w-350 items-center justify-between gap-2 px-4 sm:gap-6 sm:px-6' : 'mx-auto grid h-full max-w-350 grid-cols-[minmax(0,1fr)_auto] grid-rows-2 items-center gap-x-3 gap-y-4 px-4 py-2 sm:px-6 lg:grid-cols-[minmax(0,1fr)_auto] lg:grid-rows-1 lg:gap-x-8 lg:py-4'}>
        <div className={`flex min-w-0 items-center gap-2 ${config.headerTitle ? '' : 'lg:hidden'}`}>
          <button className="-ml-2 grid size-11 shrink-0 cursor-pointer place-items-center rounded-xl text-brand transition-colors hover:bg-surface-soft lg:hidden" type="button" ref={toggle} aria-label="Mở điều hướng" aria-controls={config.menuId} aria-expanded={menuOpen} onClick={() => setMenuOpen(true)}><LuMenu size={21} aria-hidden="true" /></button>
          {config.headerTitle && <p className="text-sm leading-5 font-semibold text-brand sm:text-lg">{config.headerTitle}</p>}
        </div>
        {!config.headerTitle && <form className="col-span-2 row-start-2 flex h-11 min-w-0 items-center gap-3 rounded-xl border border-brand/10 bg-background px-3 transition-colors focus-within:border-primary/50 focus-within:bg-surface focus-within:ring-3 focus-within:ring-primary/10 lg:col-span-1 lg:col-start-1 lg:row-start-1 lg:w-full lg:max-w-112" role="search" onSubmit={event => event.preventDefault()}>
          <LuSearch size={19} className="text-brand/65" aria-hidden="true" />
          <input className="h-full w-full min-w-0 appearance-none bg-transparent text-base text-text outline-none placeholder:text-muted disabled:cursor-not-allowed lg:text-sm [&::-webkit-search-cancel-button]:appearance-none" id={config.search.id} type="search" disabled={!config.search.enabled} title={config.search.enabled ? undefined : 'Chưa khả dụng'} value={searchQuery} onChange={event => setSearchQuery(event.target.value)} aria-label={config.search.label} placeholder={config.search.placeholder} />
          {searchQuery && <button className="-mr-3 grid size-11 shrink-0 cursor-pointer place-items-center rounded-xl text-muted hover:text-brand" type="button" aria-label="Xóa tìm kiếm" onClick={() => { setSearchQuery(''); document.getElementById(config.search.id)?.focus() }}><LuX size={17} aria-hidden="true" /></button>}
        </form>}
        <div className={`flex shrink-0 items-center ${config.headerTitle ? 'gap-1 sm:gap-4' : 'col-start-2 row-start-1 gap-2 lg:col-start-2 lg:gap-4'}`}>
          <button className="grid size-11 cursor-not-allowed place-items-center rounded-xl border border-brand/10 bg-surface text-muted" type="button" disabled title="Chưa khả dụng" aria-label="Thông báo"><LuBell size={20} aria-hidden="true" /></button>
          <div className="border-l border-brand/10 pl-2 lg:pl-4"><AccountMenu /></div>
        </div>
      </div>
    </header>
    <div className={config.headerTitle ? 'pt-20' : 'pt-30 lg:pt-20'}><main className="mx-auto w-full max-w-350 px-4 py-6 outline-none sm:px-6" id={config.mainId} tabIndex={-1} ref={main}><Outlet context={{ searchQuery }} /></main>
      <footer className="mx-auto max-w-350 px-4 pb-6 sm:px-6"><div className="flex flex-col justify-between gap-4 border-t border-border/30 pt-6 text-[11px] text-muted sm:flex-row sm:items-center"><p className="flex items-center gap-2"><LuBookOpen size={15} className="text-primary-strong" aria-hidden="true" />© {copyrightYear} dumio. All rights reserved.</p><div className="flex flex-wrap items-center gap-4"><span>Giới thiệu</span><span className="text-border" aria-hidden="true">•</span><span>Chính sách bảo mật</span><span className="text-border" aria-hidden="true">•</span><span>Liên hệ</span></div></div></footer>
    </div>
  </div>
}
