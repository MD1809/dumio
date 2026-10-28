import { LuHouse } from 'react-icons/lu'
import { PATHS } from '../../route/paths.js'
import DashboardLayout from './DashboardLayout.jsx'

const config = {
  homePath: PATHS.ROOT,
  homeLabel: 'Trang chủ',
  pageTitle: 'Trang chủ | Dumio',
  sidebarDescription: 'Học tiếng Anh & Luyện thi TOEIC',
  navigationLabel: 'Điều hướng chính',
  navigation: [{ label: 'Trang chủ', icon: LuHouse, href: PATHS.ROOT }],
  mainId: 'home-main',
  menuId: 'mobile-navigation',
  search: { id: 'home-search', label: 'Tìm kiếm bài học, từ vựng, đề thi và bài viết', placeholder: 'Tìm bài học, từ vựng, đề thi...', enabled: true },
}

export default function AppLayout() {
  return <DashboardLayout config={config} />
}
