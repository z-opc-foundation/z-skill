# z-skill-component

z-skill（技能库）跨仓唯一前端面：把「技能目录」页面收敛成一个可复用的 React 组件包。

- **npm 包名**：`@yuku123/z-skill-component`
- **宿主接入**：`@yuku123/z-skill-component/pages`（manifest + 页面 + API），主壳可只取 `ComponentDocPage`
- **后端**：技能服务 `/skill/**`（search / list / installed / categories / sources）
- **技术栈**：React 19 + antd 6 + react-router-dom 7 + vite（library mode，双入口 `.` / `./pages`）

---

## 一、能力概览

路由前缀 `/z-skill`，菜单与路由在 `src/pages-manifest.jsx` 里声明。

| 页面 | 路由（相对 `/z-skill/*`） | 源文件 | 能力 |
|------|--------------------------|--------|------|
| 登录 | `login` | `pages/LoginPage.jsx` | 登录页（suit 侧单独挂这条路由） |
| 首页 | `home` | `pages/HomePage.jsx` | 入口卡片页 |
| 技能目录 | `catalog` | `pages/Catalog.jsx` | 技能列表 + 关键词搜索 + 分类筛选 |
| 组件文档 | `docs` | `pages/ComponentDoc.jsx` | 本文件渲染页（左侧 240px TOC + 右侧正文） |

本包的 `routes` **没有**兜底通配（`*`）项，未列出的路径由宿主的 404 兜底。

## 二、导出面

```js
// 主入口（. 桶）
import {skillApi, configureSkill, ComponentDocPage} from '@yuku123/z-skill-component'

// 宿主组装面（./pages 桶）
import {menuItems, routes, HomePage, LoginPage, Catalog} from '@yuku123/z-skill-component/pages'
```

`./pages` 桶是**显式具名转发**（`export {Catalog, HomePage, LoginPage, appMeta, menuItems, routes} from './pages-manifest.jsx'`）。
新增页面时必须回来补一行，否则消费方构建会报 `"Xxx" is not exported by dist/pages.js`。

## 三、API 端点

请求实例由基座 `createRequest` 工厂创建（`src/services/api.js`），`baseURL` 默认空串
（路径自带 `/skill` 前缀），token 键 `zskill_token`。下表是 `skillApi` 里**实际定义**的路径。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/skill/search` | 技能搜索（query `q` + `category`） |
| GET | `/skill/list` | 技能列表（query `category`，不带则返回全部） |
| GET | `/skill/installed` | 已安装技能 |
| GET | `/skill/categories` | 技能分类 |
| GET | `/skill/sources` | 技能来源 |

## 四、页面说明

### 4.1 技能目录

- **列表**：有关键词走 `skillApi.search(kw, category)`，没有关键词走 `skillApi.list(category)` ——
  **两条路径要一起改**，否则会出现「搜得到、列表空」或反之；
- **分类**：`skillApi.categories()` 独立拉一次（`.catch(() => null)`，失败不影响列表）；
- 空态「暂无技能」；整页加载失败用基座 `ErrorState`（标题「后端未连接」）+ 重试。

### 4.2 组件文档

本文件。内容以 `?raw` 在编译期内联，零额外请求；左侧 240px TOC 随滚动联动。

## 五、配置

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `configureSkill({apiBase})` | `''` | 改写请求实例的 `baseURL`，宿主换挂载前缀时用 |
| token 存储键 | `zskill_token` | 独立命名，避免与其他套件串号 |
| dev 代理 | `/skill` → `localhost:8888` | suit 侧 vite proxy 配置（生产不走代理） |

## 六、已知限制

- `skillApi.installed` 与 `skillApi.sources` 两个方法在 API 层已定义，但页面上**零调用**；
  要做「已安装 / 来源」视图时可直接用。
- 搜索与列表是**两条不同的端点**（`/skill/search` vs `/skill/list`），返回结构要保持一致，
  否则分类筛选在两条路上的行为会不一致。
- `routes` 里没有 `*` 兜底 —— 宿主必须自己提供 404 路由。
- 页面统一把后端异常渲染成基座 `ErrorState`（标题「后端未连接」）—— 真因要看浏览器控制台。
- 组件包必须 external 基座；`vite.config.js` 的 `external` 是精确字符串匹配，
  `@yuku123/z-frontend-common/ui` 与 `/ui/echarts` 两个子路径必须逐个列出，漏了基座会被复制进 dist。

## 七、变更记录

| 版本 | 变更 |
|---|---|
| 0.1.0 | 新增组件文档页 `ComponentDocPage`（lead 008 §7）：`src/DOC.md` + 基座 `ComponentDoc` 薄包装 |
| 0.1.0 | 首版：技能目录页 + manifest + `skillApi` |
