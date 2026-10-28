import { PATHS } from '../route/paths.js'

export function getRoleHomePath(role) {
  if (role === 'ADMIN') return PATHS.ADMIN_DASHBOARD
  if (role === 'USER') return PATHS.ROOT
  return null
}
