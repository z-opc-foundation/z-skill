# 项目总览

## 1. 项目简介

z-skill 是 z-opc-foundation 的 **Skill 平台聚合器**：不重新发明 Skill 定义，而是把各家平台已有的 Skill 形状
**收进来归一化**，再按各家 API 形状**发出去**。

给已经按 `SKILL.md` / `AGENTS.md` / `.mdc` / `.instructions.md` / `.well-known` 索引 / skills.sh API
组织自己 Skill 库的产品，z-skill 可以当成自己的 **Skill 注册中心与 Marketplace**：负责发现、归一化、
体检、检索、静态风险判级，并用兼容层把结果按外部客户端本来就认识的形状吐回去。

定位边界：**本仓是「库 + starter」，不是可执行服务**——全仓 `src/main` 里没有 `@SpringBootApplication`、
没有 `application*.yml`，端口与鉴权都由宿主应用决定。

**技术栈：** Java 8 · Spring Boot 2.7.18（父链 `z-boot-parent` → `z-boot-dependencies` 地板供给，本仓不写版本钉）·
jackson-databind（`ObjectMapper` 由宿主注入，缺省时自建）· spring-web / spring-webmvc（只用注解）·
HttpURLConnection 远端取数 · slf4j-api · React 19 + Ant Design 6 + Vite 6 前端。

## 2. 模块架构

```
z-skill/
├── pom.xml            # 聚合 POM（packaging=pom）：parent z-boot-parent:1.1.0，<revision>=0.2.2，4 个 module
├── z-skill-api/       # 纯契约层：7 个 DTO + 异常 + SkillFormat / SkillSpec（零第三方依赖，无测试类）
├── z-skill-core/      # 引擎层：discover / normalize / aggregate / registry / search / scan / content / spec / controller
├── z-skill-starter/   # 宿主接入层：ZSkillAutoConfiguration + spring.factories（不依赖 admin）
├── z-skill-admin/     # 控制面：ZSkillAdminAutoConfiguration + AdminController + AdminQueryService + 静态控制台页
├── _frontend/         # 两个 npm 工程：z-skill-component（library）+ z-skill-suit（运行壳）
└── _doc/              # 文档收口目录
```

`z-skill-core`（包名 `com.zifang.z.skill.core`）：

- `discover/` — `FilesystemScanner`（扫目录）/ `HttpFetcher`（取远端）/ `PluginManifestReader`（读插件清单）/
  `PlatformPresets`（本机已装平台的自动发现落点）/ `RawSkill` / `SkillSource`
- `normalize/` — `SkillNormalizer`（接口）+ `MarkdownSkillNormalizer` / `RegistryIndexNormalizer` /
  `SkillsShApiNormalizer` / `JsonSupport`
- `aggregate/SkillAggregator` — 聚合流水线
- `registry/SkillRegistry` — 注册中心
- `search/SkillSearchEngine` — 检索
- `scan/SkillSecurityScanner` — 静态风险体检
- `content/SkillContentReader` — 正文预览
- `spec/` — `Frontmatter` / `SkillFiles`
- `controller/` — `SkillController`（市场面）/ `SkillCompatController`（兼容面）/ `PublicSurface` / `SkillErrorAdvice`
- `properties/SkillProperties` — `z.skill.*` 的载体

## 3. 核心功能

### 3.1 平台形状识别

- `SkillFormat` 枚举 **9 种** format：
  `agent-skills`（`SKILL.md`）/ `flat-markdown`（`*.md`）/ `cursor-rules`（`*.mdc`）/ `agents-md`（`AGENTS.md`）/
  `copilot-instructions`（`copilot-instructions.md`）/ `registry-index`（`index.json`）/ `skills-sh-api`（api/json）/
  `plugin-manifest`（`plugin.json`）/ `unknown`。
- `FilesystemScanner` 按目录/文件名形状判别；`SKIP_DIRS` 与 `NON_SKILL_DIRS` 避免把 `references/`、`scripts/`
  里的 `.md` 当成独立 skill。

### 3.2 宽松 frontmatter 解析

- `core/spec/Frontmatter` 兜住 CRLF / BOM / 块标量 / 无引号冒号 / 行内与逗号列表 / 一层嵌套 metadata；
  **解析失败不抛异常**，问题进 `issues` 列表。

### 3.3 规范校验与清洗

- `api/spec/SkillSpec` 的口径（实测常量）：
  `NAME` 正则 `^[a-z0-9]+(?:-[a-z0-9]+)*$`、`NAME_MAX = 64`、`DESCRIPTION_MAX = 1024`、
  `COMPATIBILITY_MAX = 500`、`BODY_LINES_SOFT_MAX = 500`、`MAX_SCAN_DEPTH = 6`、
  `MAX_SKILLS_PER_SOURCE = 2000`、`MIN_QUERY_LENGTH = 2`。
- `slugify()` 把任意写法洗成合规 slug（`NON_SLUG_CHARS` 去非法字符、`MULTI_HYPHEN` 折叠连续连字符）。

### 3.4 聚合与冲突消解

- `SkillAggregator` 的流水线（源码注释原话）：**来源队列 → 扫描/取数 → 归一化 → 命名空间 → 冲突消解 →
  静态风险判级 → 原子换表**，产出 `AggregateReportDto`。
- 冲突消解的落点是：跨来源撞名时 id 长成 `sourceId/slug`。

### 3.5 注册中心

- `SkillRegistry` 以 `id` 为唯一键 + slug / name 二级索引；
  `replaceAll(next, nextSources, …)` **原子换表**并算出 `added` / `changed` / `removed`；
  另有 `register` / `unregister` / `clear` / `get` / `require` / `listAll` / `snapshot` /
  `skillCount` / `installedCount` / `getSources` / `getLastReport`。

### 3.6 检索

- `SkillSearchEngine` 支持 `q` / `category` / `tag` / `source` / `format` / `risk` / `installed` 过滤 + facets；
  `sort` 取 `relevance`（默认）/ `name` / `installs` / `updated`。
- 另有 `filter` / `categoryCounts` / `tagCounts` / `listCategories` / `listTags`。

### 3.7 静态风险体检

- `SkillSecurityScanner` 是**纯文本正则规则**，给出 `safe` / `low` / `unscanned` / `medium` / `high` / `critical`
  六档（源码里 `order` 数组就是这六个）；**从不 import、加载或执行被扫描的内容**。

### 3.8 正文预览（沙箱）

- `SkillContentReader` 只读已登记条目的相对路径，任何路径必须落在该 skill 自己目录内，
  单次预览上限 `MAX_PREVIEW_BYTES = 512KB`。

### 3.9 本机平台自动发现

- `PlatformPresets` 覆盖家目录 `~/.{claude,agents,codex,cursor,qoder,copilot,gemini,amp,cline}/skills`、
  `~/.qoder-cn/{plugins,skills}`、`~/.agents/skills`；项目目录 `.<client>/skills`、`.agents/skills`、
  `.cursor/rules`、`.github`、根 `AGENTS.md`；**不存在的落点直接跳过**。

### 3.10 远端取数

- `HttpFetcher.Jdk` 走 `HttpURLConnection`：连接/读超时各 **8000ms**，跟随跳转，
  响应体 **8MB 上限**（`响应体超过 8MB 上限` 时抛 `IOException`），按 UTF-8 解码。

### 3.11 对外兼容输出

- `SkillCompatController`（`/skill/compat/**` 与 `/.well-known/**`）：skills.sh 三组端点 + `.well-known` 索引 + Qoder 侧形状。

### 3.12 控制面

- `AdminController`（`/skill/admin`）7 个 GET：`/overview` / `/skills` / `/installed` / `/categories` /
  `/sources` / `/issues` / `/health`。
- 静态控制台页 `z-skill-admin/src/main/resources/static/skill-admin/index.html` 直接 fetch 上面这七个端点，
  **无前端工程、无模板引擎**。

### 3.13 自动装配

- `ZSkillAutoConfiguration`：`@ConditionalOnProperty(name = "z.skill.enabled", havingValue = "true", matchIfMissing = false)`
  + `@ComponentScan("com.zifang.z.skill.core.controller")` ⇒ **总开关默认关**，不写 `z.skill.enabled=true` 就什么都不装。
- `ZSkillAdminAutoConfiguration`：条件是 `{"z.skill.enabled", "z.skill.expose-admin"}` 同时为 `true`
  ⇒ 控制面是**第二道钥匙**。
- 两个 `@Configuration` 分别注册在各自的 `META-INF/spring.factories`（admin 还多一份 `AutoConfiguration.imports`）。
- starter 里的 `SkillStartupRunner`（`CommandLineRunner`）在启动时跑一次聚合。

## 4. 对外集成 / 上下游

**上游（谁调它）：**

- 宿主应用在 classpath 上加 `io.github.yuku123:z-skill-starter`（或聚合入口 `io.github.yuku123:z-boot-skill-starter`）即装上市场面；
  需要控制面再加 `io.github.yuku123:z-skill-admin`。
- 外部 agent 客户端按各自认识的形状调兼容面（skills.sh / Qoder / `.well-known`）。

**下游（本仓调谁）：**

- **本机文件系统**：`FilesystemScanner` 扫配置的来源目录与 `PlatformPresets` 的落点。
- **HTTP(S)**：`HttpFetcher` 取 `z.skill.sources[].url` 指向的远端（`http(s)` 来源一律走它）。
- **不写数据库**：注册中心在内存里（`SkillRegistry` 是个进程内 Map）。

**HTTP 边界：本仓自身不监听任何端口。** 仓内没有 `application*.yml`、没有 `@SpringBootApplication`；
生产端口由宿主的 `server.port` 决定。测试宿主 `SkillHostLauncher`（在 `src/test` 域）读 `-Dserver.port`，缺省 `18099`。

**REST 前缀（2 条 `@RequestMapping` 实测）：**

| 前缀 | Controller | 端点 |
|---|---|---|
| `/skill` | `SkillController` | GET `/search` `/list` `/installed` `/categories` `/sources` `/stats` `/updates` `/detail` `/{id}` `/content` `/{id}/content`；POST `/install` `/refresh`；DELETE `/uninstall` `/uninstall/{id}` |
| `/skill/admin` | `AdminController` | GET `/overview` `/skills` `/installed` `/categories` `/sources` `/issues` `/health` |

**兼容面（`SkillCompatController`）**：`/skill/compat/skills-sh/api/search`、`/skill/compat/skills-sh/api/v1/skills`、
`/skill/compat/skills-sh/api/v1/skills/**`、`/skill/compat/skills-sh/api/v1/skills/audit/**`、
`/.well-known/agent-skills/index.json`、`/.well-known/skills/index.json`、
`/skill/compat/skills-sh/.well-known/agent-skills/index.json`、`/skill/compat/qoder/skills`。

**带斜杠的 id 一律走查询串**：聚合来的 id 天然长成 `source/slug`，Tomcat 默认拒收路径里的 `%2F`、
不编码又会被切成多段 ⇒ `@DeleteMapping("/uninstall/{id}")` 对绝大多数聚合条目是死的，`?id=` 才是可用形。

## 5. 技术特点

1. **不重新发明 Skill 定义**：只认「一个目录 + 一份带 YAML frontmatter 的说明文件」这个事实，
   以及各平台已有的等价载体（`*.mdc`、`AGENTS.md`、`.github/copilot-instructions.md`、
   `.well-known/agent-skills/index.json`、插件清单等），全部收进同一个 `SkillDto` 口径。
2. **唯一口径是 `SkillDto`，三个身份字段不混**：`name` 是来源原样写法（可能不合规，只用于展示）、
   `slug` 是清洗后的标识、`id` 是聚合后的全局键（跨来源撞名时为 `sourceId/slug`），注册中心按 `id` 索引。
3. **原子换表**：聚合流水线最后一步是 `SkillRegistry.replaceAll`，读侧不会看到半张目录；
   `added` / `changed` / `removed` 也是这一步算出来的。
4. **版本分两层，不做 semver**：`SkillDto.version` 是来源 frontmatter 原样带过来的字符串，
   本仓不维护版本序列；真正的"上游漂移"靠 `contentHash`（换 hash 即 `changed`，消失即 `removed`，
   已装但消失的进 `danglingInstalls`）。安装层由 `SkillInstallDto` 快照 version + contentHash + installRef。
5. **静态风险判级不执行被扫描内容**：`SkillSecurityScanner` 只做文本正则，从不 import / 加载 / 执行 skill。
6. **core 主代码不引用 servlet API**：这是 `CoreStaysServletFreeTest` 钉住的，让非 web 宿主也能安全 component-scan core。
7. **两把钥匙 + 默认关**：`z.skill.enabled`（总开关，`matchIfMissing = false`）与 `z.skill.expose-admin`（控制面）；
   且 `z-skill-admin` 不在 starter 的依赖里 ⇒ 控制面默认拿不到。
8. **错误包络不污染宿主**：`SkillErrorAdvice` 用 `basePackages` 限定作用域，
   响应体 `{error, message, code}`，HTTP 状态码取 `SkillException.getCode()`。
9. **死键要如实说**：`z.skill.scan-remote-on-startup` 在 `SkillProperties` 里有字段与 getter，
   但 core/starter 主代码**没有任何读取点**——它**不**关闭远端拉取（`refresh()` 对 `http(s)` 来源一律走 `HttpFetcher`）。

## 6. 目录结构

```
z-skill/
├── pom.xml                                    # 聚合 POM：<revision>0.2.2</revision>，4 个 module，5 个坐标自钉
├── z-skill-api/src/main/java/com/zifang/z/skill/api/
│   ├── dto/                                   # AggregateReportDto / SkillDto / SkillInstallDto / SkillIssueDto /
│   │                                          #   SkillResourceDto / SkillSearchResultDto / SkillSourceDto
│   ├── exception/SkillException.java
│   └── spec/                                  # SkillFormat（9 种）/ SkillSpec（校验与清洗口径）
├── z-skill-core/src/main/java/com/zifang/z/skill/core/
│   ├── discover/                              # FilesystemScanner / HttpFetcher / PlatformPresets / PluginManifestReader / RawSkill / SkillSource
│   ├── normalize/                             # SkillNormalizer + Markdown / RegistryIndex / SkillsShApi 三个实现 + JsonSupport
│   ├── aggregate/SkillAggregator.java         # 聚合流水线
│   ├── registry/SkillRegistry.java            # 注册中心（原子换表）
│   ├── search/SkillSearchEngine.java         # 检索 + facets
│   ├── scan/SkillSecurityScanner.java        # 静态风险判级
│   ├── content/SkillContentReader.java       # 正文预览（沙箱，512KB 上限）
│   ├── spec/                                  # Frontmatter / SkillFiles
│   ├── controller/                            # SkillController / SkillCompatController / PublicSurface / SkillErrorAdvice
│   └── properties/SkillProperties.java        # z.skill.* 的载体
├── z-skill-starter/src/main/java/com/zifang/z/skill/starter/
│   ├── autoconfig/ZSkillAutoConfiguration.java  # 总开关 + ComponentScan + SkillStartupRunner
│   └── resources/META-INF/spring.factories
├── z-skill-admin/src/main/java/com/zifang/z/skill/admin/
│   ├── autoconfig/ZSkillAdminAutoConfiguration.java
│   ├── controller/AdminController.java        # /skill/admin 7 个 GET
│   ├── service/AdminQueryService.java
│   └── resources/static/skill-admin/index.html + META-INF/spring.factories + AutoConfiguration.imports
├── _frontend/
│   ├── z-skill-component/                     # library 工程 @yuku123/z-skill-component 0.1.0
│   └── z-skill-suit/                          # 运行壳工程 @yuku123/z-skill-suit 0.1.0
└── _doc/
    ├── 001_arch/                              # 架构文档
    ├── 006_release/001_0.2.0发布与对账.md      # 0.2.0 那一趟 Central 发布的全程判据（已闭合）
    ├── 007_backlog/                           # 待办索引 + feature002_downstream_pins/ + feature003_gauge_gaps/
    └── 008_troubleshooting/01-test-corpus-dependency.md
```

**测试**：`z-skill-api` 无测试类；`z-skill-core` 295 个 `@Test`、`z-skill-starter` 12 个、`z-skill-admin` 23 个。
`z-skill-admin` 侧另有 `adminharness/` 三个真宿主夹具（`SkillHostLauncher` / `HostConfigs`），
在真 Tomcat 上做「装 → 读回 → 卸 → 读回」往返。

**部署现状**：本仓没有 `Dockerfile`、`docker-compose*.yml`、`deploy/`、`k8s/`、`Makefile`——产物只有 Maven 构件。
`z-skill-admin` **没有** `maven.deploy.skip`（实测 0 处），"控制面默认拿不到"靠的是两道装配条件 + 不在 starter 依赖里。
