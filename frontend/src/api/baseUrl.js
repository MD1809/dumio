export const API_BASE_URL = import.meta.env?.VITE_API_BASE_URL || 'http://localhost:8080'

export class ApiError extends Error {
  constructor(status, { code, message, fieldErrors = [] }) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.fieldErrors = Array.isArray(fieldErrors) ? fieldErrors : []
  }
}

export function createApiClient({ baseUrl = API_BASE_URL, transport = fetch } = {}) {
  let csrf = null
  let csrfPromise = null

  async function send(path, options = {}) {
    let response
    try {
      response = await transport(`${baseUrl.replace(/\/$/, '')}/api/v1/auth/${path}`, {
        ...options,
        credentials: 'include',
        cache: 'no-store',
        signal: AbortSignal.timeout(10000),
      })
    } catch {
      throw new ApiError(0, {
        code: 'NETWORK_ERROR',
        message: 'Không thể xác nhận kết quả từ máy chủ. Vui lòng kiểm tra kết nối.',
      })
    }
    if (response.status === 204) return null
    const data = await response.json().catch(() => null)
    if (!response.ok) {
      throw new ApiError(response.status, data?.code && data?.message ? data : {
        code: 'API_ERROR', message: 'Không thể xử lý yêu cầu. Vui lòng thử lại.',
      })
    }
    if (!data) throw new ApiError(response.status, { code: 'API_ERROR', message: 'Phản hồi máy chủ không hợp lệ.' })
    return data
  }

  async function getCsrf() {
    if (csrf) return csrf
    if (!csrfPromise) {
      csrfPromise = send('csrf').then((data) => {
        if (typeof data.token !== 'string' || !data.token || data.headerName !== 'X-XSRF-TOKEN') {
          throw new ApiError(0, { code: 'API_ERROR', message: 'Thông tin CSRF không hợp lệ.' })
        }
        csrf = data
        return data
      }).finally(() => { csrfPromise = null })
    }
    return csrfPromise
  }

  async function post(path, body) {
    const token = await getCsrf()
    try {
      return await send(path, {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          [token.headerName]: token.token,
          ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      })
    } catch (error) {
      if (error.code === 'CSRF_INVALID') csrf = null
      // Never retry a mutation automatically, especially refresh rotation.
      throw error
    } finally {
      if (['login', 'logout', 'reset-password'].includes(path)) csrf = null
    }
  }

  return { getCsrf, post }
}

export const apiClient = createApiClient()
