import { toast } from 'react-toastify'

export const notify = {
  success: (message, options = {}) => toast.success(message, { autoClose: 4000, role: 'status', ...options }),
  error: (message, options = {}) => toast.error(message, { autoClose: 6000, ...options }),
  warning: (message, options = {}) => toast.warning(message, { autoClose: 6000, ...options }),
}
