import { API_BASE_URL, ApiError } from './baseUrl.js'
import { sessionStore } from '../auth/sessionStore.js'

const sessionChanged = () => new ApiError(0, {
  code: 'SESSION_CHANGED', message: 'Phiên đăng nhập đã thay đổi. Vui lòng thử lại.',
})
const cancelled = () => new ApiError(0, {
  code: 'REQUEST_CANCELLED', message: 'Yêu cầu đã được hủy.',
})

// Cookie/CSRF auth remains in baseUrl.js. This client only sends in-memory Bearer tokens.
export function createBearerClient({ baseUrl = API_BASE_URL, transport = fetch, store = sessionStore, timeoutMs = 10000 } = {}) {
  async function request(path, { method = 'GET', body, responseType = 'json', signal } = {}) {
    // Keep credentials on the configured API origin; auth uses its separate cookie client.
    if (!/^\/api\/v1\/(?!auth(?:\/|\?|$))[a-zA-Z0-9/_-]+(?:\?[^#]*)?$/.test(path)
      || !['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].includes(method)
      || !['json', 'blob'].includes(responseType) || (method === 'GET' && body !== undefined)) {
      throw new TypeError('Invalid Bearer API request')
    }
    const generation = store.getSnapshot().generation
    function checkCurrent() {
      if (!store.isCurrentSession(generation)) throw sessionChanged()
      if (signal?.aborted) throw cancelled()
    }
    if (signal?.aborted) throw cancelled()
    const session = await store.ensureSession()
    if (!session) {
      if (generation !== store.getSnapshot().generation) throw sessionChanged()
      if (store.getSnapshot().status === 'error') throw store.getSnapshot().error
      throw new ApiError(401, { code: 'INVALID_ACCESS_TOKEN', message: 'Vui lòng đăng nhập để tiếp tục.' })
    }
    checkCurrent()

    async function send(accessToken) {
      checkCurrent()
      const requestSignal = signal
        ? AbortSignal.any([signal, AbortSignal.timeout(timeoutMs)]) : AbortSignal.timeout(timeoutMs)
      let response
      try {
        const multipart = body instanceof FormData
        response = await transport(`${baseUrl.replace(/\/$/, '')}${path}`, {
          method, credentials: 'omit', cache: 'no-store', redirect: 'error', signal: requestSignal,
          headers: {
            Accept: responseType === 'blob' ? '*/*' : 'application/json',
            Authorization: `Bearer ${accessToken}`,
            ...(body === undefined || multipart ? {} : { 'Content-Type': 'application/json' }),
          },
          ...(body === undefined ? {} : { body: multipart ? body : JSON.stringify(body) }),
        })
      } catch {
        checkCurrent()
        throw new ApiError(0, {
          code: 'NETWORK_ERROR',
          message: 'Không thể xác nhận kết quả từ máy chủ. Vui lòng kiểm tra lại dữ liệu trước khi gửi lại thao tác.',
        })
      }
      checkCurrent()
      if (!response.ok) {
        const data = await response.json().catch(() => null)
        checkCurrent()
        throw new ApiError(response.status,
          typeof data?.code === 'string' && typeof data?.message === 'string' ? data : {
            code: 'API_ERROR', message: 'Không thể xử lý yêu cầu. Vui lòng thử lại.',
          })
      }
      if (response.status === 204) return null
      let data
      try {
        data = responseType === 'blob' ? await response.blob() : await response.json()
      } catch {
        checkCurrent()
        throw new ApiError(response.status, { code: 'API_ERROR', message: 'Phản hồi máy chủ không hợp lệ. Vui lòng kiểm tra lại dữ liệu trước khi gửi lại thao tác.' })
      }
      checkCurrent()
      if (data === null) throw new ApiError(response.status, { code: 'API_ERROR', message: 'Phản hồi máy chủ không hợp lệ.' })
      return data
    }
    try {
      return await send(session.accessToken)
    } catch (error) {
      if (method !== 'GET' || error.status !== 401) throw error
    }
    checkCurrent()
    const recovered = await store.recoverSession(session.accessToken, generation)
    if (!recovered) {
      if (generation !== store.getSnapshot().generation) throw sessionChanged()
      throw new ApiError(401, { code: 'INVALID_ACCESS_TOKEN', message: 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.' })
    }
    return send(recovered.accessToken)
  }

  return {
    request,
    get: (path, options) => request(path, { ...options, method: 'GET' }),
    getBlob: (path, options) => request(path, { ...options, method: 'GET', responseType: 'blob' }),
    post: (path, body, options) => request(path, { ...options, method: 'POST', body }),
    put: (path, body, options) => request(path, { ...options, method: 'PUT', body }),
    patch: (path, body, options) => request(path, { ...options, method: 'PATCH', body }),
    delete: (path, options) => request(path, { ...options, method: 'DELETE' }),
  }
}

export const bearerClient = createBearerClient()
