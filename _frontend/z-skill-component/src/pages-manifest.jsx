import {AppstoreOutlined} from '@ant-design/icons'
import Catalog from './pages/Catalog'

export const menuItems = [
    {key: '/catalog', icon: <AppstoreOutlined/>, label: '技能目录'},
]

const routeTable = [
    {path: 'catalog', Component: Catalog},
]
export {routeTable}
export {default as Catalog} from './pages/Catalog'
