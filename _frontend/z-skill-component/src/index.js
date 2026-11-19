export * from './services/api.js'
// 组件文档页（lead 008 §7）：主壳按统一名字取，各仓一致
export {default as ComponentDocPage} from './pages/ComponentDoc.jsx'
// 页面命名导出：宿主壳（z-team 工作台 SystemShell/SmartWorkbench 等）用
// lazyNamed(() => import('@yuku123/z-skill-component'), 'Catalog') 直接取页。
export {Catalog, HomePage, Leaderboard, LoginPage} from './pages-manifest.jsx'
