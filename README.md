# z-skill — Skill 平台聚合器

> 不重新发明 Skill 定义。把各家平台的 Skill 形状**收进来**归一化，再按各家 API 形状**发出去**。
> 定位是聚合层 + 兼容层：任何已经按 `SKILL.md` / `AGENTS.md` / `.mdc` / `.instructions.md` /
> `.well-known` 索引 / skills.sh API 组织自己 Skill 库的产品，都可以直接把 z-skill 当自己的
> Skill 注册中心与 Marketplace 用。

Maven 坐标：`io.github.yuku123:z-skill-{api,core,starter,admin}`（一行集成：`io.github.yuku123:z-boot-skill-starter`）

---

## 1. 支持的平台形状

| format id | 平台 | 磁盘/API 形状 | 入向解析 | 出向兼容 |
|---|---|---|---|---|
| `agent-skills` | Anthropic / agentskills.io | `<name>/SKILL.md` + `scripts/` `references/` `assets/` | ✅ | ✅ `.well-known/agent-skills/index.json` |
| `flat-markdown` | z-skill 0.1.x 遗留 | 目录下一堆平铺 `.md` | ✅ | — |
| `cursor-rules` | Cursor | `.cursor/rules/**/*.mdc`（`description` / `globs` / `alwaysApply`） | ✅ | — |
| `agents-md` | OpenAI Codex / 通用 | `AGENTS.md`（就近覆盖）+ `AGENTS.override.md` | ✅ | — |
| `copilot-instructions` | GitHub Copilot | `.github/copilot-instructions.md`、`.github/instructions/*.instructions.md`（`applyTo`） | ✅ | — |
| `registry-index` | 规范注册表 | `.well-known/agent-skills/index.json` | ✅ | ✅ |
| `skills-sh-api` | skills.sh 风格 | `{data:[],pagination:{}}` / `{query,skills[],count,duration_ms}` / 详情 `{hash,files[]}` / `{audits:[]}` | ✅ | ✅ |
| `plugin-manifest` | Qoder / Cursor 插件 | `installed_plugins_v2.json`、`.qoder-plugin/plugin.json` | ✅ | ✅ `<extensionId>:<skillName>` |
| `unknown` | 未识别 | — | 记 issue，不猜 | — |

新平台接入 = 一个 `SkillNormalizer` 适配 + `SkillFormat` 一个枚举值；`SkillDto` 不动。

## 2. 归一化模型

`SkillDto` 是全系统唯一口径，30 余个字段里三个身份字段不要混：

- `name` — 来源平台原样写法（可能不合规，如 `RedBookSkills`），只用于展示
- `slug` — 按 Agent Skills 规范清洗后的标识（`^[a-z0-9]([a-z0-9-]*[a-z0-9])?$`，≤64）
- `id` — 聚合后的全局唯一键；跨来源撞名时为 `sourceId/slug`，注册中心按它索引

其余字段按用途分组：规范字段（`description` `version` `license` `compatibility` `allowedTools`
`metadata`）、检索字段（`category` `tags` `trigger` `paths`）、来源字段（`source` `sourceType`
`origin` `skillFilePath` `format` `resources` `contentHash`）、Marketplace 字段（`installCount`
`installed` `pinnedVersion` `riskLevel` `issueCount`）、血缘字段（`aliases` `issues` `discoveredAt`
`updatedAt`）。不可变，`toBuilder()` 派生。

## 3. 快速开始

```xml
<dependency>
    <groupId>io.github.yuku123</groupId>
    <artifactId>z-boot-skill-starter</artifactId>
</dependency>
```

```yaml
z:
  skill:
    enabled: true                  # 默认关：必须显式开，见 §6
    refresh-on-startup: true       # 启动即聚合一次，Marketplace 开箱有内容
    discover-installed-platforms: true
    max-depth: 6
    sources:
      - id: anthropic-official
        format: agent-skills
        path: /Users/me/official-skills
        priority: 10
      - id: team-agents
        format: agents-md
        path: /repo/mono
        category: engineering
      - id: community
        format: skills-sh-api
        url: https://skills.sh/api/search?q=pdf
        priority: 200
```

`z.skill.sources[]` 支持 `id/format/path/url/priority/enabled/category/max-depth`。
`format` 省略时按目录形状自行判别。声明的路径不存在时跳过该来源并记日志，不影响其它来源。

## 4. 本机平台自动发现

`discover-installed-platforms=true` 时，除显式声明的来源外，自动挂上本机已装平台的落点
（不存在即跳过）：

- 家目录：`~/.<client>/skills`（各家 client）、`~/.qoder-cn/plugins`（带清单）、
  `~/.qoder-cn/skills`、`~/.agents/skills`
- 项目目录：`.<client>/skills`、`.agents/skills`、`.cursor/rules`、`.github`

优先级低于显式声明的来源，因此显式配置永远覆盖自动发现。

自动发现的目录只能以 `format=unknown` 声明进场（谁的机器上装了什么，配置里写不出来），形状靠扫。
所以来源视图里有两个字段，别混：`format` 是**声明**的形状，`observedFormats` 是这次扫描**实际认出**
的平台形状（可复现排序，一个目录混放两种形状就都给）。控制台的来源表显示的是后者 —— 只回显声明值
的话，Anthropic/Codex/Qoder 的目录会全列成 `unknown`，等于聚合器在自己的报表上否认"聚合了这些平台"。
什么都没扫到时 `observedFormats` 为空，此时前端退回显示声明值，不拿声明冒充观察。

## 5. REST 面

### Marketplace（`/skill/*`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/skill/search` | `q` `category` `tag` `source` `format` `risk` `installed` `sort` `page` `perPage`，带 facets |
| GET | `/skill/list` | 全量列表（`category` `tag` `source` 过滤） |
| GET | `/skill/installed` | 已安装 + 安装记录 + 失效安装 |
| GET | `/skill/categories` | 分类聚合 |
| GET | `/skill/sources` | 本次聚合的来源清单 |
| GET | `/skill/stats` | 计数与分布 |
| GET | `/skill/updates` | 上游漂移（安装记录的 `contentHash` 与目录比对） |
| GET | `/skill/detail` / `/skill/{id}` | 详情，id/slug/name/alias 均可寻址 |
| GET | `/skill/{id}/content` · `/skill/content?id=` | SKILL.md 与资源预览（沙箱内，见 §6）；斜杠 id 只有后者可用 |
| POST | `/skill/install` · DELETE `/skill/uninstall?id=` | 安装/卸载，409 重复安装、404 不存在 |
| POST | `/skill/refresh` | 触发一次聚合，返回体检报告 |

**带斜杠的 id 一律走查询串，不要拼进路径段。** 聚合来的 id 天然长成 `source/slug`（`anthropics/skills/pdf`），
而 Tomcat 默认拒收路径里的 `%2F`——实测 `DELETE /skill/uninstall/anthropics%2Fskills%2Fpdf` 直接回 400，
请求根本到不了 Spring；不编码又会被切成多段（404）。所以 `@DeleteMapping("/uninstall/{id}")` 那条对
**绝大多数**聚合条目是死的，`?id=` 才是可用形（`/skill/detail?id=` 同理）。控制台页按这条走，
`SkillHostOverHttpTest` 在真 Tomcat 上做「装→读回→卸→读回」的往返，`AdminControllerExposureTest`
则钉住页面不许把 id 拼回路径段。

### 对外兼容输出（第三方客户端不改代码即可对接）

| 路径 | 形状 |
|---|---|
| `/skill/compat/skills-sh/api/search` | skills.sh 搜索响应：`query` `searchType` `skills[]` `count` `duration_ms` |
| `/skill/compat/skills-sh/api/v1/skills` | `{data[], pagination{}}` |
| `/skill/compat/skills-sh/api/v1/skills/<id>` | 详情：`hash` + `files[]`；`?files=true` 才带正文 |
| `/skill/compat/skills-sh/api/v1/skills/audit/<id>` | `{audits:[{provider,slug,status,riskLevel,categories,summary}]}`，`status` 用外部字典 `pass/warn/fail`，未扫描给 `pending` |
| `/.well-known/agent-skills/index.json`（含 `/.well-known/skills/index.json`） | 规范注册表索引 |
| `/skill/compat/qoder/skills` | Qoder 侧形状，`<extensionId>:<skillName>` 命名空间 |

`<id>` 取外部标识 `source/slug`，本身就可能带斜杠（`anthropics/skills/pdf`），而路径变量在
AntPathMatcher 与 PathPatternParser 下都跨不过 `/` —— 所以这两个端点吃 `/**` 的剩余路径，
从 Spring MVC 已解码的 `pathWithinHandlerMapping` 请求属性取值（两种匹配器下都实测一致；
也因此 core 的主代码不引用 servlet API，非 web 宿主才能安全地把 core 整个 component-scan 进容器）。
错误统一 `{error, message}` 包络（+ `errorId`），HTTP 状态码取自 `SkillException.getCode()`。

### 控制面（`/skill/admin/*`，默认关闭）

`overview` `skills` `installed` `categories` `sources` `issues` `health`，
附带一个控制台页 `/skill-admin/index.html`（静态资源，无需模板引擎）。

## 6. 安全默认

- **总开关默认关**：`@ConditionalOnProperty(matchIfMissing = false)`，没写 `z.skill.enabled=true`
  就什么都不会装配。这是为了不和宿主里遗留的 skill-center 抢控制器映射。
- **控制面默认关**：`z.skill.enabled=true` **且** `z.skill.expose-admin=true`，并把 `z-skill-admin`
  显式加进 classpath，才会注册 `/skill/admin/*`。两个条件写在同一个 `@ConditionalOnProperty` 里，
  避免只开控制面时容器以 `UnsatisfiedDependency` 起不来。聚合结果里含第三方仓库的描述与本机
  绝对路径，不该在没鉴权的端口上裸露。
- **公共面过一遍 `PublicSurface`**：`z.skill.enabled=true` 就把 `/skill/*` 全开在没鉴权的端口上，
  而本机平台自动发现种出来的 `origin` 是真 `file:///Users/<name>/<项目>/...`。所以每个公共出口
  （`search` `list` `installed` `sources` `stats` `updates` `detail` `content` `content-resource`
  `refresh` 与 skills.sh 详情的 `installUrl`）都只抹**本机目录**：`file:` 地址、`/` 开头、`\\`
  开头、盘符开头一律回 `null`；skills.sh 那道按他们自己的口径回落到条目 id `{source}/{slug}`。
  正文预览的 `path` 给的是 skill 目录内的相对写法（`pdf-processing/SKILL.md`），不是磁盘真路径 ——
  这条曾经漏了：只给 `detail` 抹了 `origin`，`/skill/{id}/content` 却把 `FileSystems` 解析出的
  绝对路径原样回出去；`POST /skill/install` 的回执也一样（它带着本机 `installRef`，而它是那批
  "写出口"里唯一没过这道手的）。今天这份清单不再靠人记：`SkillControllerTest` 从 Spring 的映射
  注解推导全部公共出口，每条都必须在"出口账本"上落一笔（写明由哪条测试检、且那条测试必须真存在），
  新增出口不落账就红、账上挂着已删的出口也红。相对路径（`skillFilePath`、
  `resources[].path`、`files[]`）、远端 URL、`owner/repo@skill` 这类不透明引用、`contentHash`
  全部照给 —— 抹的是宿主机布局，不是客户端要用的信息。真路径只留在 `/skill/admin/*`，
  沙箱读正文也用真路径（所以抹完公共面详情依然能读到 `contentAvailable=true`）。
- **内容读取是沙箱**：`SkillContentReader` 只接受已登记 skill 的相对路径，拒绝 `../`、绝对路径、
  编码穿越；越界返回 4xx 而不是 5xx。
- **静态扫描，不执行**：`SkillSecurityScanner` 只做规则匹配给出 `safe/low/medium/high/critical`
  分级，从不 import、加载或执行被扫描内容；未扫描的默认 `unscanned`。
- **控制台不拼 `innerHTML`**：第三方 description/path 全部走 `textContent`，避免存储型 XSS。
- **远程来源默认关**：`scan-remote-on-startup=false`；`http(s)` 来源不会在启动时被拉取。
- 鉴权不在本模块职责内：请由宿主网关/`z-ctc` 统一拦截，z-skill 只保证默认不主动暴露。

## 7. 聚合语义

- 一次 `refresh()` 产出 `AggregateReportDto`：来源数、原始候选数、产出数、丢弃数、去重数、
  冲突数、问题数、按 format 的分布、按 severity 的分布，逐来源的耗时与状态。
- 报告里恒等的只有两条：`producedCount == skillCount + dedupedCount`、
  `Σ 逐来源产出行 == producedCount`。`rawCount` 按**发现层交给适配器的东西**计数
  （一份远端响应 = 一个候选，展开成 3 条是产出侧的事），所以 `producedCount` 可以大于
  `rawCount`，`rawCount == producedCount + droppedCount` 只在"没有候选多产出、没有清单派生"时成立。
- 严重级只按"有没有进目录"分：`error` = 这条没进目录（或整个来源失败）；`warning` = 进了但不规范；
  `info` = 进了、留痕（例如描述是从正文猜的）。issue 的 `code` 是有界裸码，不带 severity、
  不带 `spec:` 命名空间、不带取值 —— 取值在 `message` 里，否则"按码计数"会碎成一条一名。
- 目录整体原子换表，读侧不会看到半张目录。
- **`installed` 是注册表自己的状态，不是来源给的数据**：谁出口谁盖章 —— `SkillRegistry` 的每条读路径
  （`get` / `require` / `listAll` / `snapshot` / `installedSkills`）都按本地安装记录改写它，上游 payload
  里自称 `installed=true` 同样被本地事实盖掉。原本只有检索层在盖，于是绕过它的两个出口
  （`/skill/detail`、`/skill/installed`）稳定回答"没装"，而同一份响应旁边就躺着那条安装记录。
- 同内容（同 `contentHash`）跨来源重复 → 折叠成一条，落选方的写法进 `aliases`，不丢信息。
- 不同内容撞同一标识 → 不覆盖、不丢弃，用 `sourceId/slug` 命名空间并存，并计入 `conflictCount`。
- 上游漂移：再聚合一次即可得 `added` / `changed` / `removed`；已安装但目录里消失的 skill
  保留安装记录并标记 `danglingInstalls`，不静默回收用户装过的东西。
- 守卫：单来源候选上限、扫描深度上限、正文行数软上限；触顶时**记 issue 后截断**，不静默丢弃。

## 8. 构建与测试

```bash
mvn -o clean test    # 全 reactor，离线可跑
```

`clean` 不要省：控制台页"打包在哪个目录"这类断言读的是 classpath，`target/` 里两天前的陈货
能让一条已经作废的断言继续绿。

测试分层：frontmatter/规范解析边界 → 各家形状适配器 → 文件系统扫描与插件清单派生 →
远端来源在**真 socket** 上的取数（`HttpFetcherJdkLiveTest`：状态码、超时、8MB 上限、跟跳转、
字符集，以及"聚合器默认就用这个取数器"这一句 —— 流水线层的远端测试塞的是桩，桩不经过真代码）→
聚合/检索/扫描/内容读取 → REST 与兼容形状（含两种路径匹配器下的多段 id 路由）→ 本仓真实
SKILL.md 语料端到端。夹具在 `z-skill-core/src/test/resources/fixtures/`。

## 9. 已知边界

- 注册中心是内存实现，本模块刻意不带任何 DB 依赖；要换持久化就顶掉 `skillRegistry` 这个 bean
  （所有装配点都带 `@ConditionalOnMissingBean`）。
- `allowed-tools` 在规范里仍是 experimental，各家写法不一（空格 / 逗号 / 分号），此处按宽松规则切分。
- 兼容输出覆盖到形状层，不代替各平台的执行语义（例如 Cursor 的 `alwaysApply` 生效逻辑仍在宿主侧）。
- skills.sh 那两个端点吃的是**原样斜杠**的 `source/slug`（`/api/v1/skills/anthropics/skills/pdf`）。
  把斜杠百分号编码成 `%2F` 会被 servlet 容器在路由之前拒掉（实测 400），这不是本模块的解析行为。
