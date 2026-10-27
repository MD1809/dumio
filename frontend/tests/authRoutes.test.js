import assert from 'node:assert/strict'
import test from 'node:test'
import { createElement } from 'react'
import { renderToString } from 'react-dom/server'
import { MemoryRouter } from 'react-router'
import { createServer } from 'vite'

test('auth routes render their heading, navigation and appropriate form', async () => {
  const server = await createServer({
    server: { middlewareMode: true, hmr: false },
    appType: 'custom',
    logLevel: 'error',
  })
  try {
    const { default: App } = await server.ssrLoadModule('/src/App.jsx')
    const { default: AuthProvider } = await server.ssrLoadModule('/src/auth/AuthProvider.jsx')
    const { createSessionStore } = await server.ssrLoadModule('/src/auth/sessionStore.js')
    const session = createSessionStore()
    session.clearSession()
    const routes = [
      ['/login', 'Đăng nhập tài khoản'], ['/register', 'Tạo tài khoản mới'],
      ['/verify-email', 'Xác minh email'], ['/forgot-password', 'Quên mật khẩu?'],
    ]
    for (const [path, heading] of routes) {
      const html = renderToString(createElement(MemoryRouter, { initialEntries: [path] }, createElement(AuthProvider, { session }, createElement(App))))
      assert.ok(html.includes(`>${heading}</h1>`), path)
      assert.match(html, /aria-label="Điều hướng tài khoản"/)
      assert.match(html, /aria-current="page"/)
      assert.match(html, /id="auth-main"/)
      assert.equal(html.includes('<form'), true)
      if (path === '/verify-email') {
        assert.match(html, /for="verification-email"/)
        assert.match(html, /Nhập mã xác nhận/)
        assert.equal(html.includes('sẽ được bổ sung'), false)
      }
      if (path === '/forgot-password') {
        assert.match(html, /for="recovery-email"/)
        assert.match(html, /autocomplete="email"/i)
        assert.match(html, /Gửi mã xác nhận/)
        assert.equal(html.includes('href="/reset-password"'), false)
      }
      if (path === '/login' || path === '/register') {
        assert.match(html, /autocomplete="email"/i)
        assert.match(html, /for="auth-password"/)
        assert.match(html, /type="password"/)
        assert.equal(html.includes('Ch3rryBl0ssom!'), false)
      }
      assert.equal(html.includes('/me'), false)
    }
  } finally {
    await server.close()
  }
})
