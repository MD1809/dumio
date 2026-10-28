import { useAuth } from '../../auth/useAuth.js'

export default function AdminHomePage() {
  const { user } = useAuth()
  const name = user?.username.trim() || 'bạn'

  return <section className="rounded-2xl border border-brand/10 border-l-4 border-l-brand bg-surface px-6 py-8 shadow-stat sm:px-8 sm:py-10" aria-labelledby="admin-home-title">
    <h1 className="text-2xl leading-snug font-bold tracking-tight text-text [overflow-wrap:anywhere] sm:text-3xl" id="admin-home-title">Xin chào, {name}!</h1>
  </section>
}
