import { ToastContainer } from 'react-toastify'
import { LuCircleCheck, LuCircleX, LuTriangleAlert, LuX } from 'react-icons/lu'

function ToastIcon({ type }) {
  if (type === 'success') return <LuCircleCheck size={22} className="text-success" aria-hidden="true" />
  if (type === 'error') return <LuCircleX size={22} className="text-error" aria-hidden="true" />
  if (type === 'warning') return <LuTriangleAlert size={22} className="text-warning" aria-hidden="true" />
  return null
}

export default function ToastNotifications({ containerId }) {
  return <ToastContainer
    containerId={containerId}
    position="top-right"
    theme="light"
    autoClose={6000}
    limit={3}
    newestOnTop
    closeOnClick={false}
    pauseOnHover
    pauseOnFocusLoss
    hideProgressBar={false}
    ariaLabel="Thông báo"
    className="[--toastify-color-light:var(--color-surface)] [--toastify-color-success:var(--color-success)] [--toastify-color-error:var(--color-error)] [--toastify-color-warning:var(--color-warning)] [--toastify-color-progress-success:var(--color-success)] [--toastify-color-progress-error:var(--color-error)] [--toastify-color-progress-warning:var(--color-warning)] [--toastify-font-family:var(--font-sans)] [--toastify-text-color-light:var(--color-text)] motion-reduce:[&_.Toastify__toast]:[animation-duration:1ms]"
    icon={ToastIcon}
    toastClassName="rounded-xl! border border-border/40 bg-surface! font-sans text-sm/normal text-text shadow-panel!"
    closeButton={({ closeToast }) => <button className="-mt-2 -mr-2 grid size-11 shrink-0 cursor-pointer place-items-center self-start rounded-lg text-muted hover:bg-surface-soft focus-visible:outline-2 focus-visible:outline-primary" type="button" aria-label="Đóng thông báo" onClick={closeToast}><LuX size={18} aria-hidden="true" /></button>}
  />
}
