import {Navigate, Route, Routes} from 'react-router-dom'
import {AppLayout} from '../../../../_shared/z-frontend-common-local/dist/z-frontend-common.es.js'
import {menuItems, routeTable} from '@yuku123/z-skill-component/pages'

export default function App() {
    return (
        <Routes>
            <Route path="/" element={<Navigate to="/catalog" replace/>}/>
            <Route path="/" element={
                <AppLayout menuItems={menuItems} appTitle="z-skill 技能库" appShort="SKL" appIcon={{icon: <img src="/icon.png" alt="SKILL" style={{width: "100%", height: "100%", objectFit: "cover", borderRadius: 8}}/>, color: '#eab308', label: 'SKILL'}}/>
            }>
                {routeTable.map((r) => (
                    <Route key={r.path} path={r.path} element={<r.Component/>}/>
                ))}
            </Route>
        </Routes>
    )
}
