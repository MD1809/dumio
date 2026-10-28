import { useNavigate } from 'react-router'
import { LuLoaderCircle } from 'react-icons/lu'
import { useAuth } from '../../auth/useAuth.js'
import { PATHS } from '../../route/paths.js'

export default function AuthSessionStatus() {
  const auth = useAuth()
  const navigate = useNavigate()
  if (auth.status === 'initializing') {
    return <p className="mb-5 flex items-center gap-2 text-xs/normal text-muted" role="status"><LuLoaderCircle size={16} className="animate-spin motion-reduce:animate-none" aria-hidden="true" />{auth.operation === 'login' ? 'Đang đăng nhập...' : auth.operation === 'reset' ? 'Đang cập nhật mật khẩu...' : 'Đang kiểm tra phiên đăng nhập...'}</p>
  }
  if (auth.status !== 'error') return null
  return <div className="mb-5 text-sm/normal text-muted">
    <p role="alert">{auth.error?.message || 'Chưa thể kiểm tra phiên đăng nhập. Vui lòng thử lại.'}</p>
    <div className="mt-2 flex flex-wrap gap-x-4">
      {auth.canRetry && <button className="min-h-11 font-semibold text-primary hover:underline" type="button" onClick={() => auth.retryInitialization().catch(() => {})}>Thử lại</button>}
      <button className="min-h-11 font-semibold text-primary hover:underline" type="button" onClick={() => auth.beginLogin().then(() => navigate(PATHS.LOGIN, { replace: true })).catch(() => {})}>Đăng nhập lại</button>
    </div>
  </div>
}
