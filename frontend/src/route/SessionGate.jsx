import { Link } from 'react-router'
import { LuBookOpen } from 'react-icons/lu'
import { useAuth } from '../auth/useAuth.js'
import AuthSessionStatus from '../components/auth/AuthSessionStatus.jsx'
import { PATHS } from './paths.js'

export default function SessionGate() {
  const auth = useAuth()
  return <main className="grid min-h-dvh place-items-center bg-background px-5 py-12 font-sans text-text">
    <section className="w-full max-w-md rounded-2xl border border-border/50 bg-surface p-7 shadow-stat sm:p-10" aria-labelledby="session-title" aria-busy={auth.status === 'initializing'}>
      <Link className="text-3xl font-extrabold tracking-tight text-brand" to={PATHS.ROOT} aria-label="Dumio — Trang chủ">dumio</Link>
      <span className="mt-8 grid size-12 place-items-center rounded-xl bg-surface-soft text-primary"><LuBookOpen size={25} aria-hidden="true" /></span>
      <h1 className="mt-5 mb-4 text-2xl font-bold tracking-tight" id="session-title">{auth.status === 'error' ? 'Chưa thể mở không gian học tập' : 'Chuẩn bị không gian học tập'}</h1>
      {auth.status === 'authenticated' ? <p className="text-sm text-muted" role="status">Đang mở Trang chủ...</p> : <AuthSessionStatus />}
    </section>
  </main>
}
