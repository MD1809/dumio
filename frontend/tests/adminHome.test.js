import assert from 'node:assert/strict'
import test from 'node:test'
import { createElement } from 'react'
import { renderToString } from 'react-dom/server'
import { MemoryRouter, Route, Routes } from 'react-router'
import { createServer } from 'vite'
import { getRoleHomePath } from '../src/auth/roleRedirect.js'

test('role destinations accept only the roles in the authentication contract', () => {
  assert.equal(getRoleHomePath('ADMIN'), '/admin/dashboard')
  assert.equal(getRoleHomePath('USER'), '/')
  for (const role of [undefined, null, '', 'admin', 'OWNER', 'ROLE_ADMIN', '/admin']) {
    assert.equal(getRoleHomePath(role), null)
  }
})

test('admin layout uses the account greeting safely and has only the overview destination', async () => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom', logLevel: 'error' })
  try {
    const { default: AdminLayout } = await server.ssrLoadModule('/src/components/layout/AdminLayout.jsx')
    const { default: AppLayout } = await server.ssrLoadModule('/src/components/layout/AppLayout.jsx')
    const { default: AdminHomePage } = await server.ssrLoadModule('/src/pages/admin/AdminHomePage.jsx')
    const { default: AuthProvider } = await server.ssrLoadModule('/src/auth/AuthProvider.jsx')
    const { createSessionStore } = await server.ssrLoadModule('/src/auth/sessionStore.js')
    for (const username of ['<script>unexpected()</script>', '   ']) {
      const user = { id: 'a4e8c9d0-1551-4f8b-b330-9d7ee6eab104', username, email: 'admin@example.com', role: 'ADMIN' }
      const session = createSessionStore({ api: { getCsrf: async () => ({}), refresh: async () => ({ user, accessToken: 'test', tokenType: 'Bearer', expiresIn: 900 }) } })
      await session.initialize()
      const html = renderToString(createElement(MemoryRouter, { initialEntries: ['/admin/dashboard'] }, createElement(AuthProvider, { session },
        createElement(Routes, null, createElement(Route, { element: createElement(AdminLayout) }, createElement(Route, { path: '/admin/dashboard', element: createElement(AdminHomePage) }))))))
      assert.equal(html.includes('<script>unexpected()'), false)
      assert.ok(html.replace(/<!--.*?-->/g, '').includes(username.trim() ? '&lt;script&gt;unexpected()&lt;/script&gt;' : 'Xin chào, bạn!'))
      assert.ok(html.includes('id="admin-main"'))
      assert.equal((html.match(/<h1/g) || []).length, 1)
      assert.equal(html.includes('role="search"'), false)
      assert.ok(html.includes('Quản Trị Hệ Thống'))
      assert.equal(html.includes('<img'), false)
      assert.equal(html.includes('role="progressbar"'), false)
      assert.equal(html.includes('href="/"'), false)
      const userLayoutHtml = renderToString(createElement(MemoryRouter, null, createElement(AuthProvider, { session }, createElement(AppLayout))))
      for (const tag of ['aside', 'dialog']) {
        const classPattern = new RegExp(`<${tag} class="([^"]*)"`)
        assert.equal(html.match(classPattern)?.[1], userLayoutHtml.match(classPattern)?.[1], `${tag} shares layout styles`)
      }
      assert.equal(html.match(/<footer\b.*?<\/footer>/s)?.[0], userLayoutHtml.match(/<footer\b.*?<\/footer>/s)?.[0])
      assert.match(userLayoutHtml, /<input[^>]+id="home-search"/)
      assert.equal(/<input[^>]+id="home-search"[^>]+disabled/.test(userLayoutHtml), false)
      const navs = Array.from(html.matchAll(/<nav\b[^>]*>(.*?)<\/nav>/gs), match => match[1])
      assert.equal(navs.length, 2) // Desktop and native mobile dialog.
      for (const nav of navs) {
        assert.equal((nav.match(/<a\b/g) || []).length, 1)
        assert.ok(nav.includes('href="/admin/dashboard"'))
        assert.ok(nav.includes('aria-current="page"'))
      }
    }
  } finally { await server.close() }
})
