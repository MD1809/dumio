import { useEffect, useRef, useState } from 'react'
import { Link, NavLink, Outlet, useLocation } from 'react-router'
import { LuBell, LuBookOpen, LuHouse, LuMenu, LuSearch, LuSettings, LuX } from 'react-icons/lu'
import AccountMenu from './AccountMenu.jsx'
import { PATHS } from '../../route/paths.js'

const copyrightYear = new Date().getFullYear()
const navigation = [
  { label: 'Trang chủ', icon: LuHouse, href: '/' },
]
const navigationClass = 'flex min-h-11 items-center gap-3 rounded-lg px-4 py-2 text-sm font-medium text-on-primary/80 transition-colors hover:bg-on-primary/10 hover:text-on-primary'

function SidebarContent({ onNavigate, mobile = false }) {
  return <div className="flex h-full min-h-0 flex-col justify-between">
    <div>
      <div className="flex items-start justify-between gap-2 px-6 pt-6 pb-5">
        <div><Link className="text-2xl font-extrabold tracking-tight text-on-primary" to={PATHS.ROOT} onClick={() => onNavigate('/')} aria-label="Dumio — Trang chủ">dumio</Link><p className="mt-1 text-[11px] leading-relaxed text-border">Học tiếng Anh &amp; Luyện thi TOEIC</p></div>
        {mobile && <button className="-mr-3 grid size-11 cursor-pointer place-items-center rounded-lg text-on-primary/80 hover:bg-on-primary/10" type="button" aria-label="Đóng điều hướng" onClick={() => onNavigate()}><LuX size={21} aria-hidden="true" /></button>}
      </div>
      <nav className="space-y-1 px-2" aria-label={mobile ? 'Điều hướng chính trên điện thoại' : 'Điều hướng chính'}>
        {navigation.map(({ label, icon: Icon, href }) => href === '/' ? <NavLink className="flex min-h-11 items-center gap-3 rounded-lg bg-surface px-4 py-2 text-sm font-bold text-brand shadow-stat" key={label} to={PATHS.ROOT} end onClick={() => onNavigate('/')}><Icon size={20} aria-hidden="true" />{label}</NavLink> : <a className={navigationClass} key={label} href={href} onClick={() => onNavigate(href)}><Icon size={20} aria-hidden="true" />{label}</a>)}
      </nav>
    </div>
    <div className="px-2 pt-6 pb-2">
      <button className={navigationClass + ' mb-1 w-full cursor-not-allowed text-left'} type="button" disabled title="Chưa khả dụng"><LuSettings size={20} aria-hidden="true" />Cài đặt</button>
    </div>
  </div>
}

export default function AppLayout() {
  const [menuOpen, setMenuOpen] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const [scrolled, setScrolled] = useState(() => window.scrollY > 8)
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
    document.title = 'Trang chủ | Dumio'
    main.current?.focus({ preventScroll: true })
  }, [pathname])

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
    if (href === '/') window.scrollTo(0, 0)
    else if (href) requestAnimationFrame(() => document.getElementById(href.slice(1))?.focus({ preventScroll: true }))
  }

  return <div className="min-h-dvh bg-background font-sans text-sm leading-normal text-text lg:pl-65 [&_svg]:shrink-0 [&_:is(a,button,input):focus-visible]:outline-2 [&_:is(a,button,input):focus-visible]:outline-primary [&_:is(a,button,input):focus-visible]:outline-offset-3 motion-reduce:[&_*]:transition-none">
    <a className="fixed top-2 left-2 z-50 -translate-y-[200%] rounded-lg bg-primary px-4 py-3 text-on-primary focus:translate-y-0" href="#home-main">Đến nội dung chính</a>
    <aside className="fixed inset-y-0 left-0 z-40 hidden w-65 border-r border-brand-deep bg-brand shadow-header lg:block" aria-label="Thanh điều hướng"><SidebarContent onNavigate={navigateSection} /></aside>
    <dialog className="fixed inset-y-0 left-0 m-0 h-dvh max-h-none w-72 max-w-[calc(100dvw_-_2rem)] border-0 bg-brand p-0 text-on-primary shadow-panel backdrop:bg-overlay/40 backdrop:backdrop-blur-[2px]" ref={drawer} id="mobile-navigation" aria-label="Điều hướng chính" onCancel={event => { event.preventDefault(); setMenuOpen(false) }} onClick={event => { if (event.target === event.currentTarget) setMenuOpen(false) }}><SidebarContent onNavigate={navigateSection} mobile /></dialog>
    <header className={`fixed top-0 right-0 left-0 z-30 h-30 border-b border-brand/10 bg-surface transition-shadow duration-200 motion-reduce:transition-none lg:left-65 lg:h-20 ${scrolled ? 'shadow-header-scrolled' : 'shadow-header'}`}>
      <div className="mx-auto grid h-full max-w-350 grid-cols-[minmax(0,1fr)_auto] grid-rows-2 items-center gap-x-3 gap-y-4 px-4 py-2 sm:px-6 lg:grid-cols-[minmax(0,1fr)_auto] lg:grid-rows-1 lg:gap-x-8 lg:py-4">
        <div className="flex min-w-0 items-center gap-2 lg:hidden">
          <button className="-ml-2 grid size-11 shrink-0 cursor-pointer place-items-center rounded-xl text-brand transition-colors hover:bg-surface-soft lg:hidden" type="button" ref={toggle} aria-label="Mở điều hướng" aria-controls="mobile-navigation" aria-expanded={menuOpen} onClick={() => setMenuOpen(true)}><LuMenu size={21} aria-hidden="true" /></button>
        </div>
        <form className="col-span-2 row-start-2 flex h-11 min-w-0 items-center gap-3 rounded-xl border border-brand/10 bg-background px-3 transition-colors focus-within:border-primary/50 focus-within:bg-surface focus-within:ring-3 focus-within:ring-primary/10 lg:col-span-1 lg:col-start-1 lg:row-start-1 lg:w-full lg:max-w-112" role="search" onSubmit={event => event.preventDefault()}>
          <LuSearch size={19} className="text-brand/65" aria-hidden="true" />
          <input className="h-full w-full min-w-0 appearance-none bg-transparent text-base text-text outline-none placeholder:text-muted lg:text-sm [&::-webkit-search-cancel-button]:appearance-none" id="home-search" type="search" value={searchQuery} onChange={event => setSearchQuery(event.target.value)} aria-label="Tìm kiếm bài học, từ vựng, đề thi và bài viết" placeholder="Tìm bài học, từ vựng, đề thi..." />
          {searchQuery && <button className="-mr-3 grid size-11 shrink-0 cursor-pointer place-items-center rounded-xl text-muted hover:text-brand" type="button" aria-label="Xóa tìm kiếm" onClick={() => { setSearchQuery(''); document.getElementById('home-search')?.focus() }}><LuX size={17} aria-hidden="true" /></button>}
        </form>
        <div className="col-start-2 row-start-1 flex shrink-0 items-center gap-2 lg:col-start-2 lg:gap-4">
          <button className="grid size-11 cursor-not-allowed place-items-center rounded-xl border border-brand/10 bg-surface text-muted" type="button" disabled title="Chưa khả dụng" aria-label="Thông báo"><LuBell size={20} aria-hidden="true" /></button>
          <div className="border-l border-brand/10 pl-2 lg:pl-4"><AccountMenu /></div>
        </div>
      </div>
    </header>
    <div className="pt-30 lg:pt-20"><main className="mx-auto w-full max-w-350 px-4 py-6 outline-none sm:px-6" id="home-main" tabIndex={-1} ref={main}><Outlet context={{ searchQuery }} /></main>
      <footer className="mx-auto max-w-350 px-4 pb-6 sm:px-6"><div className="flex flex-col justify-between gap-4 border-t border-border/30 pt-6 text-[11px] text-muted sm:flex-row sm:items-center"><p className="flex items-center gap-2"><LuBookOpen size={15} className="text-primary-strong" aria-hidden="true" />© {copyrightYear} dumio. All rights reserved.</p><div className="flex flex-wrap items-center gap-4"><span>Giới thiệu</span><span className="text-border" aria-hidden="true">•</span><span>Chính sách bảo mật</span><span className="text-border" aria-hidden="true">•</span><span>Liên hệ</span></div></div></footer>
    </div>
  </div>
}
