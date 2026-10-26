import assert from 'node:assert/strict'
import test from 'node:test'
import { createApiClient, ApiError } from '../src/api/baseUrl.js'
import { createAuthApi } from '../src/api/authApi.js'

const json = (body, status = 200) => Response.json(body, { status })
const csrf = { token: 'masked-csrf', headerName: 'X-XSRF-TOKEN' }

test('auth methods use the contract, credentials and CSRF, never Bearer', async () => {
  const calls = []
  const api = createAuthApi(createApiClient({
    baseUrl: 'http://localhost:8080/',
    transport: async (url, options) => {
      calls.push({ url, options })
      if (url.endsWith('/csrf')) return json(csrf)
      if (url.endsWith('/logout') || url.endsWith('/cancel-registration')) return new Response(null, { status: 204 })
      return json({ message: 'Thao tác thành công' })
    },
  }))
  await api.getCsrf()
  const data = { email: 'an@example.com', password: ' Abcde1!x ', code: '012345' }
  await api.register(data)
  await api.verifyEmail(data)
  await api.resendVerification(data)
  await api.login(data)
  await api.refresh()
  assert.equal(await api.logout(), null)
  await api.forgotPassword(data)
  await api.resetPassword(data)
  assert.equal(await api.cancelRegistration(), null)
  const posts = calls.filter(({ options }) => options.method === 'POST')
  assert.deepEqual(posts.map(({ url }) => url.split('/').at(-1)), [
    'register', 'verify-email', 'resend-verification', 'login', 'refresh', 'logout', 'forgot-password', 'reset-password', 'cancel-registration',
  ])
  for (const { url, options } of calls) {
    assert.ok(url.startsWith('http://localhost:8080/api/v1/auth/'))
    assert.equal(options.credentials, 'include')
    assert.equal(options.cache, 'no-store')
    assert.equal(new Headers(options.headers).has('Authorization'), false)
    if (options.method === 'POST') assert.equal(options.headers['X-XSRF-TOKEN'], csrf.token)
  }
  assert.equal(posts[0].options.body, JSON.stringify(data))
  assert.equal(posts[4].options.body, undefined)
  assert.equal(posts[5].options.body, undefined)
  assert.equal(posts.at(-1).options.body, undefined)
})

test('CSRF deduplicates concurrent reads; login/logout/reset fetch again; refresh keeps it', async () => {
  let count = 0
  const api = createAuthApi(createApiClient({ transport: async (url) => {
    if (url.endsWith('/csrf')) return json({ ...csrf, token: `csrf-${++count}` })
    return json({ message: 'ok' })
  } }))
  await Promise.all([api.getCsrf(), api.getCsrf(), api.getCsrf()])
  assert.equal(count, 1)
  await api.refresh()
  await api.getCsrf()
  assert.equal(count, 1)
  for (const action of ['login', 'logout', 'resetPassword']) {
    await api[action]({})
    await api.getCsrf()
  }
  assert.equal(count, 4)
})

test('403 invalidates CSRF without retry; field errors remain available', async () => {
  let reads = 0
  let writes = 0
  const api = createAuthApi(createApiClient({ transport: async (url) => {
    if (url.endsWith('/csrf')) { reads++; return json(csrf) }
    writes++
    return json({ code: 'CSRF_INVALID', message: 'Vui lòng thử lại', fieldErrors: [] }, 403)
  } }))
  await assert.rejects(api.register({}), (error) => error instanceof ApiError && error.code === 'CSRF_INVALID' && error.status === 403)
  assert.equal(writes, 1)
  await api.getCsrf()
  assert.equal(reads, 2)
  const validation = createApiClient({ transport: async (url) => url.endsWith('/csrf') ? json(csrf)
    : json({ code: 'VALIDATION_ERROR', message: 'Dữ liệu không hợp lệ', fieldErrors: [{ field: 'email', message: 'Email sai' }] }, 400) })
  await assert.rejects(validation.post('register', {}), (error) => error.fieldErrors[0].field === 'email')
})

test('refresh network failure is reported once and never retried automatically', async () => {
  let writes = 0
  const api = createAuthApi(createApiClient({ transport: async (url) => {
    if (url.endsWith('/csrf')) return json(csrf)
    writes++
    throw new TypeError('Network failure')
  } }))
  await assert.rejects(api.refresh(), { code: 'NETWORK_ERROR', status: 0 })
  assert.equal(writes, 1)
})

test('handles non-JSON errors and rejects malformed CSRF before sending POST', async () => {
  const unavailable = createApiClient({ transport: async () => new Response('Unavailable', { status: 503 }) })
  await assert.rejects(unavailable.getCsrf(), { code: 'API_ERROR', status: 503 })
  let calls = 0
  const invalid = createApiClient({ transport: async () => { calls++; return json({ token: 'bad', headerName: 'Authorization' }) } })
  await assert.rejects(invalid.post('login', {}), { code: 'API_ERROR' })
  assert.equal(calls, 1)
})
