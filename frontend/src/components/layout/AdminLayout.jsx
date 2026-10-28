import { LuLayoutDashboard } from 'react-icons/lu'
import { PATHS } from '../../route/paths.js'
import DashboardLayout from './DashboardLayout.jsx'

const config = {
  homePath: PATHS.ADMIN_DASHBOARD,
  homeLabel: 'Tổng quan quản trị',
  pageTitle: 'Tổng quan quản trị | Dumio',
  sidebarDescription: 'Không gian quản trị',
  navigationLabel: 'Điều hướng quản trị',
  navigation: [{ label: 'Tổng quan', icon: LuLayoutDashboard, href: PATHS.ADMIN_DASHBOARD }],
  mainId: 'admin-main',
  menuId: 'admin-mobile-navigation',
  headerTitle: 'Quản Trị Hệ Thống',
}

export default function AdminLayout() {
  return <DashboardLayout config={config} />
}
