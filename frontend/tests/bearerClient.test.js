import assert from 'node:assert/strict'
import test from 'node:test'
import { createBearerClient } from '../src/api/bearerClient.js'
import { createSessionStore } from '../src/auth/sessionStore.js'
import { ApiError } from '../src/api/baseUrl.js'

const path = '/api/v1/admin/vocabulary/topics'
const tick = () => new Promise(resolve => setImmediate(resolve))
const token = (accessToken = 'first') => ({
  accessToken, tokenType: 'Bearer', expiresIn: 900,
  user: { id: 'a4e8c9d0-1551-4f8b-b330-9d7ee6eab104', email: 'admin@example.com', role: 'ADMIN' },
})
const unauthorized = () => Response.json({ code: 'INVALID_ACCESS_TOKEN', message: 'Expired' }, { status: 401 })

async function fixture(transport, overrides = {}, clientOptions = {}) {
  let refreshes = 0
  let clock = 1000
  const store = createSessionStore({
    now: () => clock,
    api: {
      getCsrf: async () => ({}),
      refresh: async () => token(++refreshes === 1 ? 'first' : `refresh-${refreshes}`),
      login: async () => token('new-login'), logout: async () => {},
      ...overrides,
    },
  })
  await store.initialize()
  return {
    store, client: createBearerClient({ store, transport, baseUrl: 'http://localhost:8080/', ...clientOptions }),
    refreshes: () => refreshes, advance: () => { clock += 880000 },
  }
}

test('business paths use current Bearer, omit cookies and never send CSRF', async () => {
  const calls = []
  const { client, advance, refreshes } = await fixture(async (url, options) => {
    calls.push({ url, options })
    return Response.json({ items: [] })
  })
  await client.get(`${path}?page=0&size=20`)
  advance()
  await client.get(path)
  assert.equal(refreshes(), 2)
  assert.equal(calls[0].url, `http://localhost:8080${path}?page=0&size=20`)
  assert.equal(calls[0].options.headers.Authorization, 'Bearer first')
  assert.equal(calls[1].options.headers.Authorization, 'Bearer refresh-2')
  for (const { options } of calls) {
    assert.equal(options.credentials, 'omit')
    assert.equal(options.cache, 'no-store')
    assert.equal(options.redirect, 'error')
    assert.equal(new Headers(options.headers).has('X-XSRF-TOKEN'), false)
  }
})

test('concurrent GET 401 share rotation; a late old-token 401 reuses the newer token', async () => {
  const rotation = Promise.withResolvers()
  const late = Promise.withResolvers()
  let rotations = 0
  const attempts = []
  const { client } = await fixture(async (url, options) => {
    attempts.push(options.headers.Authorization)
    if (url.endsWith('?late=1') && options.headers.Authorization === 'Bearer first') return late.promise
    return options.headers.Authorization === 'Bearer first' ? unauthorized() : Response.json({ ok: true })
  }, { refresh: async () => ++rotations === 1 ? token() : rotation.promise })
  const lateRequest = client.get(`${path}?late=1`)
  const first = client.get(path)
  const second = client.get(path)
  await tick()
  assert.equal(rotations, 2)
  rotation.resolve(token('second'))
  await Promise.all([first, second])
  late.resolve(unauthorized())
  assert.deepEqual(await lateRequest, { ok: true })
  assert.equal(rotations, 2)
  assert.equal(attempts.length, 6)
})

test('a replayed GET 401 stops after one retry', async () => {
  let calls = 0
  const { client, refreshes } = await fixture(async () => { calls++; return unauthorized() })
  await assert.rejects(client.get(path), { status: 401, code: 'INVALID_ACCESS_TOKEN' })
  assert.equal(calls, 2)
  assert.equal(refreshes(), 2)
})

test('concurrent requests near expiry wait for one proactive refresh before sending', async () => {
  const rotation = Promise.withResolvers()
  let rotations = 0
  const access = []
  const { client, advance } = await fixture(async (url, options) => {
    access.push(options.headers.Authorization)
    return Response.json({ ok: true })
  }, { refresh: async () => ++rotations === 1 ? token() : rotation.promise })
  advance()
  const reads = [client.get(path), client.get(path)]
  await tick()
  assert.deepEqual(access, [])
  assert.equal(rotations, 2)
  rotation.resolve(token('proactive'))
  await Promise.all(reads)
  assert.deepEqual(access, ['Bearer proactive', 'Bearer proactive'])
})

test('logout during GET recovery prevents the replay and discards the old result', async () => {
  const rotation = Promise.withResolvers()
  let rotations = 0
  let calls = 0
  const { client, store } = await fixture(async () => { calls++; return unauthorized() }, {
    refresh: async () => ++rotations === 1 ? token() : rotation.promise,
  })
  const read = client.get(path)
  const rejected = assert.rejects(read, { code: 'SESSION_CHANGED' })
  await tick()
  assert.equal(rotations, 2)
  const signingOut = store.logout()
  rotation.resolve(token('discarded'))
  await rejected
  await signingOut
  assert.equal(calls, 1)
  assert.equal(store.getSnapshot().status, 'unauthenticated')
})

test('a same-session response remains valid while another request refreshes its token', async () => {
  const rotation = Promise.withResolvers()
  const response = Promise.withResolvers()
  let rotations = 0
  const { client, store } = await fixture(async () => response.promise, {
    refresh: async () => ++rotations === 1 ? token() : rotation.promise,
  })
  const read = client.get(path)
  await tick()
  const recovery = store.recoverSession('first', store.getSnapshot().generation)
  response.resolve(Response.json({ ok: true }))
  assert.deepEqual(await read, { ok: true })
  rotation.resolve(token('next'))
  await recovery
})

test('protected blob bodies are discarded if the session is cleared during download', async () => {
  const body = Promise.withResolvers()
  const { client, store } = await fixture(async () => ({ ok: true, status: 200, blob: () => body.promise }))
  const download = client.getBlob('/api/v1/admin/media/asset/content')
  const rejected = assert.rejects(download, { code: 'SESSION_CHANGED' })
  await tick()
  store.clearSession()
  body.resolve(new Blob(['private-image']))
  await rejected
})

test('refresh rejection stays unauthenticated without replaying the GET', async () => {
  let rotations = 0
  let calls = 0
  const { client, store } = await fixture(async () => { calls++; return unauthorized() }, {
    refresh: async () => {
      if (++rotations === 1) return token()
      throw new ApiError(401, { code: 'INVALID_REFRESH_TOKEN', message: 'Expired' })
    },
  })
  await assert.rejects(client.get(path), { status: 401 })
  assert.equal(calls, 1)
  assert.equal(store.getSnapshot().status, 'unauthenticated')
})

test('refresh service failure is preserved and never causes another automatic rotation', async () => {
  let rotations = 0
  const { client } = await fixture(async () => unauthorized(), {
    refresh: async () => {
      if (++rotations === 1) return token()
      throw new ApiError(503, { code: 'SERVICE_UNAVAILABLE', message: 'Unavailable' })
    },
  })
  await assert.rejects(client.get(path), { status: 503, code: 'SERVICE_UNAVAILABLE' })
  await assert.rejects(client.get(path), { status: 503 })
  assert.equal(rotations, 2)
})

for (const method of ['post', 'put', 'patch', 'delete']) {
  for (const failure of ['401', 'timeout']) {
    test(`${method} ${failure} is sent once without refresh/replay`, async () => {
      let calls = 0
      const { client, refreshes } = await fixture(async () => {
        calls++
        if (failure === 'timeout') throw new DOMException('Timeout', 'TimeoutError')
        return unauthorized()
      })
      await assert.rejects(client[method](path, method === 'delete' ? undefined : { name: 'Travel', version: 0 }),
        { code: failure === '401' ? 'INVALID_ACCESS_TOKEN' : 'NETWORK_ERROR' })
      assert.equal(calls, 1)
      assert.equal(refreshes(), 1)
    })
  }
}

test('403 and field validation errors retain code/message/fields without refreshing', async () => {
  const fieldErrors = [{ field: 'name', message: 'Tên đã tồn tại.' }]
  let status = 403
  const { client, refreshes } = await fixture(async () => Response.json({
    code: status === 403 ? 'ACCESS_DENIED' : 'VOCABULARY_TOPIC_NAME_EXISTS', message: 'Không thể lưu.', fieldErrors,
  }, { status }))
  await assert.rejects(client.get(path), { status: 403, code: 'ACCESS_DENIED', fieldErrors })
  status = 409
  await assert.rejects(client.post(path, { name: 'Travel' }), { status: 409, code: 'VOCABULARY_TOPIC_NAME_EXISTS', fieldErrors })
  assert.equal(refreshes(), 1)
})

test('JSON, multipart, protected blobs and empty responses use correct bodies/headers', async () => {
  const calls = []
  const { client } = await fixture(async (url, options) => {
    calls.push({ url, options })
    if (options.method === 'GET') return new Response(new Blob(['image'], { type: 'image/png' }))
    return new Response(null, { status: 204 })
  })
  assert.equal(await client.put(path, { name: 'Travel', version: 2 }), null)
  const form = new FormData()
  form.append('file', new Blob(['image'], { type: 'image/png' }), 'image.png')
  await client.post('/api/v1/admin/media', form)
  const blob = await client.getBlob('/api/v1/admin/media/asset/content')
  assert.equal(await blob.text(), 'image')
  assert.equal(calls[0].options.body, JSON.stringify({ name: 'Travel', version: 2 }))
  assert.equal(calls[0].options.headers['Content-Type'], 'application/json')
  assert.equal(calls[1].options.body, form)
  assert.equal('Content-Type' in calls[1].options.headers, false)
  assert.equal(calls[2].options.headers.Authorization, 'Bearer first')
})

for (const status of [200, 500]) {
  test(`late ${status} response is discarded as soon as logout begins`, async () => {
    const response = Promise.withResolvers()
    const logout = Promise.withResolvers()
    const { client, store } = await fixture(async () => response.promise, { logout: () => logout.promise })
    const read = client.get(path)
    const rejected = assert.rejects(read, { code: 'SESSION_CHANGED' })
    await tick()
    const signingOut = store.logout()
    response.resolve(Response.json({ privateData: true }, { status }))
    await rejected
    logout.resolve()
    await signingOut
  })
}

test('switching accounts invalidates an old body even after response headers arrived', async () => {
  const body = Promise.withResolvers()
  const { client, store } = await fixture(async () => ({ ok: true, status: 200, json: () => body.promise }))
  const read = client.get(path)
  const rejected = assert.rejects(read, { code: 'SESSION_CHANGED' })
  await tick()
  await store.beginLogin()
  await store.login({})
  body.resolve({ privateData: true })
  await rejected
})

test('beginLogin invalidates requests immediately, before its pending cookie operation finishes', async () => {
  const rotation = Promise.withResolvers()
  const response = Promise.withResolvers()
  let rotations = 0
  const { client, store } = await fixture(async () => response.promise, {
    refresh: async () => ++rotations === 1 ? token() : rotation.promise,
  })
  const read = client.get(path)
  const rejected = assert.rejects(read, { code: 'SESSION_CHANGED' })
  await tick()
  const pendingRefresh = store.recoverSession('first', store.getSnapshot().generation)
  const login = store.beginLogin()
  response.resolve(Response.json({ privateData: true }))
  await rejected
  rotation.resolve(token('discarded'))
  await Promise.all([pendingRefresh, login])
})

test('an old request remains invalid even when logout fails and the session is restored', async () => {
  const response = Promise.withResolvers()
  const { client, store } = await fixture(async () => response.promise, {
    logout: async () => { throw new ApiError(503, { code: 'SERVICE_UNAVAILABLE', message: 'Unavailable' }) },
  })
  const read = client.get(path)
  const rejected = assert.rejects(read, { code: 'SESSION_CHANGED' })
  await tick()
  await assert.rejects(store.logout(), { status: 503 })
  response.resolve(Response.json({ privateData: true }))
  await rejected
  assert.equal(store.getSnapshot().status, 'authenticated')
})

test('cancelling a superseded search aborts its transport and discards its result', async () => {
  const response = Promise.withResolvers()
  let receivedSignal
  const { client } = await fixture(async (url, options) => { receivedSignal = options.signal; return response.promise })
  const controller = new AbortController()
  const read = client.get(`${path}?q=old`, { signal: controller.signal })
  const rejected = assert.rejects(read, { code: 'REQUEST_CANCELLED' })
  await tick()
  controller.abort()
  assert.equal(receivedSignal.aborted, true)
  response.resolve(Response.json({ items: ['old'] }))
  await rejected
})

test('timeout cancels the actual transport and never resends the mutation', async () => {
  let calls = 0
  const { client } = await fixture((url, options) => new Promise((resolve, reject) => {
    calls++
    options.signal.addEventListener('abort', () => reject(options.signal.reason), { once: true })
  }), {}, { timeoutMs: 10 })
  // AbortSignal.timeout is unref'ed in Node; keep the test alive while waiting.
  const keepAlive = setTimeout(() => {}, 1000)
  try { await assert.rejects(client.post(path, { name: 'Travel' }), { code: 'NETWORK_ERROR' }) }
  finally { clearTimeout(keepAlive) }
  assert.equal(calls, 1)
})

test('invalid JSON and non-JSON errors are normalized without retrying writes', async () => {
  let status = 200
  let calls = 0
  const { client } = await fixture(async () => { calls++; return new Response('<html>invalid</html>', { status }) })
  await assert.rejects(client.post(path, {}), { status: 200, code: 'API_ERROR' })
  status = 502
  await assert.rejects(client.post(path, {}), { status: 502, code: 'API_ERROR' })
  assert.equal(calls, 2)
})

test('unauthenticated, pre-cancelled and unsafe paths never send a Bearer request', async () => {
  let calls = 0
  const { client, store } = await fixture(async () => { calls++; return Response.json({}) })
  for (const unsafe of ['https://other.example/api/v1/admin/media', '//other.example', '/api/v1/auth/refresh',
    '/api/v1/admin/../auth/refresh', '/api/v1/admin/%2e%2e/auth/refresh']) {
    await assert.rejects(client.get(unsafe), TypeError)
  }
  await assert.rejects(client.get(path, { signal: AbortSignal.abort() }), { code: 'REQUEST_CANCELLED' })
  store.clearSession()
  await assert.rejects(client.get(path), { status: 401 })
  assert.equal(calls, 0)
})

test('initialization failure is preserved instead of being reported as missing credentials', async () => {
  let refreshes = 0
  let requests = 0
  const store = createSessionStore({ api: {
    getCsrf: async () => ({}),
    refresh: async () => {
      refreshes++
      throw new ApiError(0, { code: 'NETWORK_ERROR', message: 'Unknown refresh result' })
    },
  } })
  const client = createBearerClient({ store, transport: async () => { requests++; return Response.json({}) } })
  await assert.rejects(client.get(path), { code: 'NETWORK_ERROR', status: 0 })
  await assert.rejects(client.get(path), { code: 'NETWORK_ERROR', status: 0 })
  assert.equal(refreshes, 1)
  assert.equal(requests, 0)
})
