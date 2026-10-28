import { useEffect, useRef, useState } from 'react'
import { LuChevronDown, LuLoaderCircle, LuLogOut, LuMail, LuUserRound, LuX } from 'react-icons/lu'
import { useAuth } from '../../auth/useAuth.js'
import { notify } from '../../utils/notifications.js'

export default function AccountMenu({ compact = false }) {
  const { user, logout, operation } = useAuth()
  const loggingOut = operation === 'logout'
  const [open, setOpen] = useState(false)
  const container = useRef(null)
  const trigger = useRef(null)
  const panel = useRef(null)
  const name = user?.username.trim() || 'Bạn'
  const initials = name.split(/\s+/).filter(Boolean).map(word => Array.from(word)[0]).filter(Boolean)

  const handleLogout = async () => {
    try {
      await logout()
      notify.success('Đăng xuất thành công.', { toastId: 'logout-success' })
    } catch (error) {
      notify.error(error.message || 'Không thể đăng xuất. Vui lòng thử lại.', { toastId: 'logout-error' })
    }
  }

  useEffect(() => {
    if (!open) return
    panel.current?.focus()
    const outside = event => {
      if (!container.current?.contains(event.target)) setOpen(false)
    }
    const escape = event => {
      if (event.key === 'Escape') {
        setOpen(false)
        trigger.current?.focus()
      }
    }
    document.addEventListener('pointerdown', outside)
    document.addEventListener('keydown', escape)
    return () => {
      document.removeEventListener('pointerdown', outside)
      document.removeEventListener('keydown', escape)
    }
  }, [open])

  return <div className="relative min-w-0" ref={container} onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false) }}>
    <button className={compact ? 'grid size-11 cursor-pointer place-items-center rounded-full text-text focus-visible:outline-2 focus-visible:outline-primary focus-visible:outline-offset-3' : 'flex min-h-11 max-w-full cursor-pointer items-center gap-2 rounded-xl border border-brand/10 bg-surface py-1 pr-3 pl-1 text-brand transition-colors hover:bg-surface-soft focus-visible:outline-2 focus-visible:outline-primary focus-visible:outline-offset-3'} type="button" ref={trigger} aria-label="Thông tin tài khoản" aria-expanded={open} aria-controls="account-panel" aria-haspopup="dialog" onClick={() => setOpen(value => !value)}>
      <span className="grid size-8 shrink-0 place-items-center rounded-full bg-brand text-xs font-bold text-on-primary" aria-hidden="true">{compact ? <LuUserRound size={18} /> : (initials.length > 1 ? initials[0] + initials.at(-1) : initials[0] || 'D').toUpperCase()}</span>
      {!compact && <><span className="hidden max-w-32 truncate text-sm font-semibold sm:block">{name}</span><LuChevronDown size={15} className={`shrink-0 text-muted transition-transform ${open ? 'rotate-180' : ''}`} aria-hidden="true" /></>}
    </button>
    {open && <div className="absolute top-full right-0 z-30 mt-3 w-72 max-w-[calc(100dvw_-_2rem)] rounded-2xl border border-border/60 bg-surface p-5 text-text shadow-panel outline-none" id="account-panel" role="dialog" aria-labelledby="account-panel-title" tabIndex={-1} ref={panel}>
      <div className="flex items-center justify-between gap-3"><h2 className="text-sm font-bold" id="account-panel-title">Tài khoản của bạn</h2><button className="-mr-2 grid size-11 cursor-pointer place-items-center rounded-lg text-muted hover:bg-surface-soft focus-visible:outline-2 focus-visible:outline-primary" type="button" aria-label="Đóng thông tin tài khoản" onClick={() => { setOpen(false); trigger.current?.focus() }}><LuX size={18} aria-hidden="true" /></button></div>
      <div className="mt-3 flex items-start gap-3 border-t border-border/40 pt-4"><LuUserRound size={18} className="mt-0.5 shrink-0 text-primary" aria-hidden="true" /><p className="min-w-0 text-sm font-semibold [overflow-wrap:anywhere]">{name}</p></div>
      <div className="mt-3 flex items-start gap-3"><LuMail size={18} className="mt-0.5 shrink-0 text-primary" aria-hidden="true" /><p className="min-w-0 text-sm leading-relaxed text-muted [overflow-wrap:anywhere]">{user?.email}</p></div>
      <div className="mt-5 border-t border-border/40 pt-3"><button className="flex min-h-11 w-full cursor-pointer items-center justify-center gap-2 rounded-lg bg-surface-soft px-4 py-2 text-sm font-semibold text-primary transition-colors hover:bg-primary-soft focus-visible:outline-2 focus-visible:outline-primary disabled:cursor-wait" type="button" disabled={loggingOut} aria-busy={loggingOut} onClick={handleLogout}>{loggingOut ? <LuLoaderCircle size={18} className="animate-spin motion-reduce:animate-none" aria-hidden="true" /> : <LuLogOut size={18} aria-hidden="true" />}<span role="status">{loggingOut ? 'Đang đăng xuất...' : 'Đăng xuất'}</span></button></div>
    </div>}
  </div>
}
