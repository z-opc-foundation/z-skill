import {Navigate, Route, Routes} from 'react-router-dom'
import {AppLayout} from '@yuku123/z-frontend-common'
import {menuItems, routeTable} from '@yuku123/z-skill-component/pages'

export default function App() {
    return (
        <Routes>
            <Route path="/" element={<Navigate to="/catalog" replace/>}/>
            <Route path="/" element={
                <AppLayout menuItems={menuItems} appTitle="z-skill 技能库" appShort="SKL"/>
            }>
                {routeTable.map((r) => (
                    <Route key={r.path} path={r.path} element={<r.Component/>}/>
                ))}
            </Route>
        </Routes>
    )
}
