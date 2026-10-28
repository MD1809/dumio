import assert from 'node:assert/strict'
import test from 'node:test'
import { createElement } from 'react'
import { renderToString } from 'react-dom/server'
import { MemoryRouter } from 'react-router'
import { createServer } from 'vite'

test('home renders the real account safely without local fixtures or unavailable module links', async () => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom', logLevel: 'error' })
  try {
    const { default: HomePage } = await server.ssrLoadModule('/src/pages/home/HomePage.jsx')
    const { default: AuthProvider } = await server.ssrLoadModule('/src/auth/AuthProvider.jsx')
    const { createSessionStore } = await server.ssrLoadModule('/src/auth/sessionStore.js')
    const user = { id: 'a4e8c9d0-1551-4f8b-b330-9d7ee6eab104', username: '<script>unexpected()</script>', email: 'student@example.com', role: 'USER' }
    const session = createSessionStore({ api: { getCsrf: async () => ({}), refresh: async () => ({ user, accessToken: 'test', tokenType: 'Bearer', expiresIn: 900 }) } })
    await session.initialize()
    const { emptyHomeData, filterHomeItems } = await server.ssrLoadModule('/src/pages/home/homeData.js')
    const html = renderToString(createElement(MemoryRouter, null, createElement(AuthProvider, { session }, createElement(HomePage, { data: emptyHomeData }))))
    assert.ok(html.includes('&lt;script&gt;unexpected()&lt;/script&gt;'))
    assert.equal(html.includes('<script>unexpected()'), false)
    assert.equal((html.match(/<article/g) || []).length, 0)
    assert.equal((html.match(/<img/g) || []).length, 0)
    const links = Array.from(html.matchAll(/href="([^"]+)"/g), match => match[1])
    assert.deepEqual(links, [])
    assert.equal(html.includes('role="progressbar"'), false)
    assert.equal(html.includes('undefined'), false)
    const items = [{ title: 'Ghi nhớ', description: '' }]
    assert.equal(filterHomeItems(items, 'GHI NHO').length, 1)
    assert.equal(filterHomeItems(items, 'unmatched-query').length, 0)
    assert.equal(items.length, 1)
  } finally { await server.close() }
})
