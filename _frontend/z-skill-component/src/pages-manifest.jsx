import { AppstoreOutlined, HomeOutlined, ReadOutlined } from '@ant-design/icons'
import Catalog from './pages/Catalog'


export {default as Catalog} from './pages/Catalog'
import HomePage from './pages/HomePage'
import ComponentDocPage from './pages/ComponentDoc.jsx'

/** 菜单 + 路由清单（lead 008 §10/§14/§16 批量落地）。App 壳在 suit 侧组装。 */
export const appMeta = { title: 'z-skill 技能库', short: 'z-skill' }

export const menuItems = [
    { key: '/z-skill/home', label: '首页', icon: <HomeOutlined /> },
    { key: '/z-skill/catalog', label: '技能目录', icon: <AppstoreOutlined /> },
    { key: '/z-skill/docs', label: '组件文档', icon: <ReadOutlined /> },
]

export const routes = [
    { path: '/z-skill/home', Component: HomePage },
    { path: '/z-skill/catalog', Component: Catalog },
    { path: '/z-skill/docs', Component: ComponentDocPage },
]

export { default as HomePage } from './pages/HomePage'
export { default as LoginPage } from './pages/LoginPage'
// 主壳接线用：文档页统一以 ComponentDocPage 这个名字从主入口导出。
export { default as ComponentDocPage } from './pages/ComponentDoc.jsx'
