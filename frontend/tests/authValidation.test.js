import assert from 'node:assert/strict'
import test from 'node:test'
import {
  getFieldErrors, normalizeAuthInput, normalizeEmail, normalizeUsername,
  validateAuthInput, validateEmail, validateOtp, validatePassword, validateUsername,
} from '../src/utils/authValidation.js'

test('normalizes name/email without changing passwords, OTP or email tags', () => {
  const password = ' Abcde1!x '
  assert.deepEqual(normalizeAuthInput({
    username: '  Nguyễn\u00a0  An  ', email: '\u0085An+Tag@Example.COM\u0085',
    password, confirmPassword: password, code: '012345',
  }), {
    username: 'Nguyễn An', email: 'an+tag@example.com',
    password, confirmPassword: password, code: '012345',
  })
  assert.equal(normalizeEmail('\ufeffan@example.com'), '\ufeffan@example.com')
  assert.equal(normalizeUsername(' An\n '), ' An\n ')
  assert.ok(validateUsername(' An\n '))
  assert.equal(validateUsername('😀'.repeat(100)), null)
  assert.ok(validateUsername('😀'.repeat(101)))
})

test('email rejects malformed/internal whitespace and permits non-Gmail domains', () => {
  for (const value of [null, 12, '', 'invalid', 'a@@example.com', 'a b@example.com',
    'a@exa mple.com', 'a\u00a0b@example.com', 'a@-example.com', 'a@exam..com']) {
    assert.ok(validateEmail(value), String(value))
  }
  assert.equal(validateEmail(' An+Tag@university.education '), null)
})

test('password checks code points, ASCII complexity and 72 UTF-8 bytes', () => {
  assert.equal(validatePassword('Abcde1!x'), null)
  assert.equal(validatePassword('Aa1!' + 'é'.repeat(34)), null)
  assert.ok(validatePassword('Aa1!' + 'é'.repeat(35)))
  assert.ok(validatePassword('Aa1!😀😀')) // 6 code points, despite 8 UTF-16 units
  for (const password of ['Abcde1 x', 'abcde1!x', 'ABCDE1!X', 'Abcdef!x', 'Ab1!', 'Abcde1✨x']) {
    assert.ok(validatePassword(password), password)
  }
  assert.equal(validatePassword('old', { checkComplexity: false }), null)
  assert.ok(validatePassword(' ', { checkComplexity: false }))
})

test('OTP remains an exact six digit string including a leading zero', () => {
  assert.equal(validateOtp('012345'), null)
  for (const value of [123456, '12345', '1234567', ' 123456', '１２３４５６', '12345a']) {
    assert.ok(validateOtp(value))
  }
})

test('registration/reset require exact confirmation; login skips complexity', () => {
  const values = { username: 'An', email: 'an@example.com', password: 'Abcde1!x', confirmPassword: 'Abcde1!x' }
  assert.deepEqual(validateAuthInput('register', values), {})
  assert.ok(validateAuthInput('register', { ...values, confirmPassword: 'Abcde1!x ' }).confirmPassword)
  assert.deepEqual(validateAuthInput('login', { email: values.email, password: 'old' }), {})
  assert.deepEqual(validateAuthInput('reset-password', {
    email: values.email, code: '012345', newPassword: values.password, confirmPassword: values.password,
  }), {})
  assert.ok(validateAuthInput('verify-email', { email: values.email, code: 123456 }).code)
  assert.deepEqual(getFieldErrors({ fieldErrors: [{ field: 'email', message: 'Email sai' }] }), { email: 'Email sai' })
})
