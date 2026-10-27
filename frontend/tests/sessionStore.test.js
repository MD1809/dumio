import assert from 'node:assert/strict'
import test from 'node:test'
import { createSessionStore } from '../src/auth/sessionStore.js'
import { ApiError } from '../src/api/baseUrl.js'
import { createApiClient } from '../src/api/baseUrl.js'
import { createAuthApi } from '../src/api/authApi.js'

const failure = (status, code = 'API_ERROR') => new ApiError(status, { code, message: `Failure ${status}` })
const tokenResponse = (accessToken = 'access-1') => ({
  accessToken, tokenType: 'Bearer', expiresIn: 900,
  user: { id: 'a4e8c9d0-1551-4f8b-b330-9d7ee6eab104', username: 'Nguyễn An', email: 'an@example.com', role: 'USER' },
})
const deferred = () => Promise.withResolvers()
const tick = () => new Promise(resolve => setImmediate(resolve))

function fixture(overrides = {}) {
  const calls = { csrf: 0, refresh: 0, login: 0 }
  const api = {
    getCsrf: async () => { calls.csrf++; return { token: 'csrf' } },
    refresh: async () => { calls.refresh++; return tokenResponse() },
    login: async () => { calls.login++; return tokenResponse('login-access') },
    ...overrides,
  }
  return { api, calls }
}

test('initialization shares one refresh across StrictMode subscriptions and repeated calls', async () => {
  const pending = deferred()
  const { api, calls } = fixture({ refresh: async () => { calls.refresh++; return pending.promise } })
  const store = createSessionStore({ api, now: () => 1000 })
  let updates = 0
  const unsubscribe = store.subscribe(() => updates++)
  const first = store.initialize()
  unsubscribe() // StrictMode cleanup only unsubscribes; it must not cancel/rotate again.
  const unsubscribeAgain = store.subscribe(() => updates++)
  assert.equal(first, store.initialize())
  await tick()
  assert.equal(calls.refresh, 1)
  assert.equal(store.getSnapshot().status, 'initializing')
  pending.resolve(tokenResponse())
  await first
  assert.equal(store.getSnapshot().status, 'authenticated')
  assert.equal(store.getSnapshot().expiresAt, 901000)
  assert.equal(store.getSnapshot().user.username, 'Nguyễn An')
  await store.initialize()
  assert.equal(calls.refresh, 1)
  assert.equal(updates, 2)
  unsubscribeAgain()
})

test('missing/expired refresh becomes unauthenticated without an error or an automatic loop', async () => {
  const { api, calls } = fixture({ refresh: async () => { calls.refresh++; throw failure(401, 'INVALID_REFRESH_TOKEN') } })
  const store = createSessionStore({ api })
  await store.initialize()
  assert.equal(store.getSnapshot().status, 'unauthenticated')
  assert.equal(store.getSnapshot().error, null)
  assert.equal(await store.ensureSession(), null)
  await store.initialize()
  assert.equal(calls.refresh, 1)
})

test('CSRF failure before refresh permits only explicit retry; synchronous failures do not leave a stuck promise', async () => {
  let attempts = 0
  const { api, calls } = fixture({ getCsrf: () => { if (++attempts === 1) throw failure(0, 'NETWORK_ERROR'); return Promise.resolve({ token: 'csrf' }) } })
  const store = createSessionStore({ api })
  await store.initialize()
  assert.equal(store.getSnapshot().canRetry, true)
  assert.equal(calls.refresh, 0)
  await assert.rejects(store.ensureSession(), { code: 'NETWORK_ERROR' })
  assert.equal(attempts, 1)
  await store.retryInitialization()
  assert.equal(store.getSnapshot().status, 'authenticated')
  assert.equal(calls.refresh, 1)
})

for (const [status, code] of [[503, 'SERVICE_UNAVAILABLE'], [403, 'CSRF_INVALID']]) {
  test(`refresh ${status} allows explicit retry without automatic POST replay`, async () => {
    const { api, calls } = fixture({ refresh: async () => { if (++calls.refresh === 1) throw failure(status, code); return tokenResponse() } })
    const store = createSessionStore({ api })
    await store.initialize()
    assert.equal(store.getSnapshot().status, 'error')
    assert.equal(store.getSnapshot().canRetry, true)
    await assert.rejects(store.ensureSession(), { code })
    assert.equal(calls.refresh, 1)
    await store.retryInitialization()
    assert.equal(calls.refresh, 2)
    assert.equal(store.getSnapshot().status, 'authenticated')
  })
}

test('lost refresh response blocks further rotation; explicit reauthentication can establish a new session', async () => {
  const { api, calls } = fixture({ refresh: async () => { calls.refresh++; throw failure(0, 'NETWORK_ERROR') } })
  const store = createSessionStore({ api })
  await store.initialize()
  assert.equal(store.getSnapshot().canRetry, false)
  await assert.rejects(store.retryInitialization(), { code: 'NETWORK_ERROR' })
  await assert.rejects(store.ensureSession(), { code: 'NETWORK_ERROR' })
  assert.equal(calls.refresh, 1)
  await store.beginLogin()
  await store.login({ email: 'an@example.com', password: 'Never stored here' })
  assert.equal(store.getSnapshot().accessToken, 'login-access')
  assert.equal(calls.refresh, 1)
  assert.equal('password' in store.getSnapshot(), false)
})

test('malformed refresh responses never authenticate and cannot be retried automatically', async () => {
  const valid = tokenResponse()
  for (const bad of [null, {}, { ...valid, accessToken: '' }, { ...valid, tokenType: 'Basic' },
    { ...valid, expiresIn: 0 }, { ...valid, expiresIn: '900' }, { ...valid, user: null },
    { ...valid, user: { ...valid.user, id: 'invalid' } }, { ...valid, user: { ...valid.user, role: 'OWNER' } }]) {
    const { api } = fixture({ refresh: async () => bad })
    const store = createSessionStore({ api })
    await store.initialize()
    assert.equal(store.getSnapshot().status, 'error')
    assert.equal(store.getSnapshot().error.code, 'INVALID_AUTH_RESPONSE')
    assert.equal(store.getSnapshot().canRetry, false)
    assert.equal(store.getSnapshot().accessToken, null)
  }
})

test('login waits for initialization and deduplicates duplicate submissions', async () => {
  const pending = deferred()
  const { api, calls } = fixture({ refresh: async () => { calls.refresh++; return pending.promise } })
  const store = createSessionStore({ api })
  const first = store.login({ email: 'an@example.com', password: 'Password1!' })
  assert.equal(first, store.login({}))
  await tick()
  assert.equal(calls.login, 0)
  pending.reject(failure(401))
  await first
  assert.equal(calls.login, 1)
  assert.equal(store.getSnapshot().accessToken, 'login-access')
})

test('failed or malformed login never exposes a successful session', async () => {
  const { api } = fixture({ refresh: async () => { throw failure(401) }, login: async () => { throw failure(401, 'AUTHENTICATION_FAILED') } })
  const store = createSessionStore({ api })
  await assert.rejects(store.login({}), { code: 'AUTHENTICATION_FAILED' })
  assert.equal(store.getSnapshot().status, 'unauthenticated')
  assert.equal(store.getSnapshot().user, null)
  api.login = async () => ({ message: 'ok' })
  await assert.rejects(store.login({}), { code: 'INVALID_AUTH_RESPONSE' })
  assert.equal(store.getSnapshot().status, 'error')
  assert.equal(store.getSnapshot().user, null)
})

test('expiry checks reuse valid access and coalesce renewals within the 30-second margin', async () => {
  let clock = 0
  const { api, calls } = fixture()
  const store = createSessionStore({ api, now: () => clock })
  await store.initialize()
  clock = 869000
  await store.ensureSession()
  assert.equal(calls.refresh, 1)
  clock = 870000
  const first = store.ensureSession()
  assert.equal(first, store.ensureSession())
  await first
  assert.equal(calls.refresh, 2)
})

test('late 401 uses the refreshed access without rotating again; old generations are rejected', async () => {
  const { api, calls } = fixture({ refresh: async () => tokenResponse(`access-${++calls.refresh}`) })
  const store = createSessionStore({ api })
  await store.initialize()
  const { accessToken, generation } = store.getSnapshot()
  await Promise.all([store.recoverSession(accessToken, generation), store.recoverSession(accessToken, generation)])
  assert.equal(calls.refresh, 2)
  assert.equal((await store.recoverSession(accessToken, generation)).accessToken, 'access-2')
  assert.equal(calls.refresh, 2)
  store.clearSession()
  await assert.rejects(store.recoverSession(accessToken, generation), { code: 'SESSION_CHANGED' })
})

test('late refresh cannot restore a cleared session; reauthentication waits for its cookie response', async () => {
  const pending = deferred()
  const { api, calls } = fixture({ refresh: async () => { calls.refresh++; return pending.promise } })
  const store = createSessionStore({ api })
  const initialization = store.initialize()
  await tick()
  store.clearSession()
  const reauthenticate = store.beginLogin()
  const login = store.login({})
  await tick()
  assert.equal(calls.login, 0)
  pending.resolve(tokenResponse('old-access'))
  await initialization
  await reauthenticate
  await login
  assert.equal(store.getSnapshot().accessToken, 'login-access')
  assert.equal(calls.refresh, 1)
})

test('clearing an in-flight login rejects its result instead of reporting login success', async () => {
  const pending = deferred()
  const { api } = fixture({ refresh: async () => { throw failure(401) }, login: () => pending.promise })
  const store = createSessionStore({ api })
  const login = store.login({})
  await tick()
  store.clearSession()
  pending.resolve(tokenResponse())
  await assert.rejects(login, { code: 'SESSION_CHANGED' })
  assert.equal(store.getSnapshot().status, 'unauthenticated')
})

test('session keeps only public user fields and never persists credentials', async () => {
  const { api } = fixture({ refresh: async () => ({ ...tokenResponse(), password: 'secret', refreshToken: 'private', user: { ...tokenResponse().user, passwordHash: 'private' } }) })
  const store = createSessionStore({ api })
  await store.initialize()
  assert.deepEqual(Object.keys(store.getSnapshot().user).sort(), ['email', 'id', 'role', 'username'])
  assert.equal('refreshToken' in store.getSnapshot(), false)
  assert.equal('password' in store.getSnapshot(), false)
})

test('session coordinator preserves real API-client CSRF invalidation and explicit retry ordering', async () => {
  const calls = []
  let csrfReads = 0
  let refreshes = 0
  const api = createAuthApi(createApiClient({ transport: async (url, options) => {
    calls.push({ action: url.split('/').at(-1), headers: options.headers })
    if (url.endsWith('/csrf')) return Response.json({ token: `csrf-${++csrfReads}`, headerName: 'X-XSRF-TOKEN' })
    if (++refreshes === 1) return Response.json({ code: 'CSRF_INVALID', message: 'Try again' }, { status: 403 })
    return Response.json(tokenResponse())
  } }))
  const store = createSessionStore({ api })
  await store.initialize()
  assert.equal(store.getSnapshot().canRetry, true)
  assert.equal(refreshes, 1)
  await store.retryInitialization()
  assert.deepEqual(calls.map(call => call.action), ['csrf', 'refresh', 'csrf', 'refresh'])
  assert.equal(calls[1].headers['X-XSRF-TOKEN'], 'csrf-1')
  assert.equal(calls[3].headers['X-XSRF-TOKEN'], 'csrf-2')
  assert.equal(calls[3].headers.Authorization, undefined)
  assert.equal(store.getSnapshot().status, 'authenticated')
})

test('choosing reauthentication prevents a competing manual retry from rotating the cookie', async () => {
  const { api, calls } = fixture({ refresh: async () => { calls.refresh++; throw failure(503) } })
  const store = createSessionStore({ api })
  await store.initialize()
  const reauthentication = store.beginLogin()
  assert.equal(reauthentication, store.beginLogin())
  await assert.rejects(store.retryInitialization(), { code: 'SESSION_CHANGED' })
  await reauthentication
  assert.equal(calls.refresh, 1)
  assert.equal(store.getSnapshot().status, 'unauthenticated')
})

test('invalidating initialization never publishes the late user, even briefly', async () => {
  const pending = deferred()
  const { api } = fixture({ refresh: () => pending.promise })
  const store = createSessionStore({ api })
  const seenUsers = []
  store.subscribe(() => seenUsers.push(store.getSnapshot().user))
  const initialization = store.initialize()
  await tick()
  store.clearSession()
  pending.resolve(tokenResponse())
  await initialization
  assert.ok(seenUsers.every(user => user === null))
  assert.equal(store.getSnapshot().status, 'unauthenticated')
})
