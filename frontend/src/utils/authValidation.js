// Java's (?U)\s uses Unicode White_Space; JavaScript trim() differs for U+0085/U+FEFF.
const whitespace = /\p{White_Space}/u
const edgeWhitespace = /^\p{White_Space}+|\p{White_Space}+$/gu
const controls = /\p{Cc}/u
const byteLength = (value) => new TextEncoder().encode(value).length
const isBlank = (value) => typeof value !== 'string' || !value || /^\s+$/u.test(value)

export function normalizeEmail(value) {
  return typeof value === 'string' ? value.replace(edgeWhitespace, '').toLowerCase() : value
}

export function normalizeUsername(value) {
  if (typeof value !== 'string' || controls.test(value)) return value
  return value.replace(edgeWhitespace, '').replace(/\p{White_Space}+/gu, ' ')
}

export function normalizeAuthInput(values) {
  return {
    ...values,
    ...(Object.hasOwn(values, 'email') ? { email: normalizeEmail(values.email) } : {}),
    ...(Object.hasOwn(values, 'username') ? { username: normalizeUsername(values.username) } : {}),
  }
}

export function validateEmail(value) {
  const email = normalizeEmail(value)
  if (isBlank(email)) return 'Email không được để trống'
  if (email.length > 254) return 'Email tối đa 254 ký tự'
  // Early feedback for the form. Spring @Email remains the final authority.
  if (whitespace.test(email) || controls.test(email) || /\p{Z}/u.test(email)
    || !/^[^@]+@[^@]+$/u.test(email)) return 'Email không đúng định dạng'
  const [local, domain] = email.split('@')
  if (local.length > 64 || local.startsWith('.') || local.endsWith('.') || local.includes('..')
    || /[<>(),;:\\"[\]]/u.test(local)
    || !domain.split('.').every((label) => /^[\p{L}\p{N}](?:[\p{L}\p{N}-]*[\p{L}\p{N}])?$/u.test(label)
      && label.length <= 63)) return 'Email không đúng định dạng'
  return null
}

export function validateUsername(value) {
  const name = normalizeUsername(value)
  if (isBlank(name)) return 'Tên người dùng không được để trống'
  if (controls.test(name)) return 'Tên người dùng không được chứa ký tự điều khiển'
  if ([...name].length > 100) return 'Tên người dùng tối đa 100 ký tự'
  return null
}

export function validatePassword(value, { checkComplexity = true } = {}) {
  if (isBlank(value)) return 'Mật khẩu không được để trống'
  if (byteLength(value) > 72) return 'Mật khẩu không được vượt quá 72 byte UTF-8'
  if (checkComplexity && ([...value].length < 8 || !/[A-Z]/.test(value)
    || !/[a-z]/.test(value) || !/[0-9]/.test(value)
    || !/[!-/:-@[-`{-~]/.test(value))) {
    return 'Mật khẩu cần ít nhất 8 ký tự, gồm chữ hoa, chữ thường, chữ số và ký hiệu đặc biệt'
  }
  return null
}

export function validateOtp(value) {
  return typeof value === 'string' && /^[0-9]{6}$/.test(value)
    ? null : 'Mã xác nhận phải gồm đúng 6 chữ số'
}

// Returns { field: message }; forms can display these next to their inputs.
export function validateAuthInput(action, input) {
  const values = normalizeAuthInput(input)
  const errors = {}
  const check = (field, message) => { if (message) errors[field] = message }
  check('email', validateEmail(values.email))
  if (action === 'register') check('username', validateUsername(values.username))
  if (action === 'register' || action === 'login' || action === 'reset-password') {
    const field = action === 'reset-password' ? 'newPassword' : 'password'
    check(field, validatePassword(values[field], { checkComplexity: action !== 'login' }))
    if (action !== 'login') {
      if (isBlank(values.confirmPassword)) check('confirmPassword', 'Vui lòng xác nhận mật khẩu')
      else if (values.confirmPassword !== values[field]) check('confirmPassword', 'Mật khẩu xác nhận không khớp')
    }
  }
  if (action === 'verify-email' || action === 'reset-password') check('code', validateOtp(values.code))
  return errors
}

export function getFieldErrors(error) {
  return Object.fromEntries((error?.fieldErrors ?? []).map(({ field, message }) => [field, message]))
}
