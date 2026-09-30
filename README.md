# z-skill

> Skill 平台聚合器 —— 不重新发明 Skill 定义：把各家平台的 Skill 形状**收进来**归一化，再按各家 API 形状**发出去**。

任何已经按 `SKILL.md` / `AGENTS.md` / `.mdc` / `.instructions.md` / `.well-known` 索引 / skills.sh API
组织自己 Skill 库的产品，都可以把 z-skill 当成自己的 Skill 注册中心与 Marketplace：它负责发现、归一化、
体检、检索、静态风险判级，并用兼容层把结果按外部客户端本来就认识的形状吐回去。
本仓是**库 + starter**，不是可执行服务：全仓 `src/main` 里没有 `@SpringBootApplication`、没有
`application*.yml`，端口与鉴权都由宿主应用决定。

---

## 📋 基本信息

| 字段 | 值 |
|------|-----|
| **仓库** | `z-skill`（org agent family 成员） |
| **Maven 坐标** | `io.github.yuku123:z-skill{,-api,-core,-starter,-admin}:${revision}` |
| **当前版本** | `0.2.2`（根 POM `<revision>`，CI-friendly + flatten-maven-plugin `flattenMode=oss`，2026-09-29 从 `resolveCiFriendliesOnly` 改过来） |
| **父项目** | `io.github.yuku123:z-boot-parent:1.0.21`（`<relativePath/>` 留空；根 POM 顶部注释仍写着 1.0.19，那是上一轮消费者改造留下的**陈旧注释**，实测 `<parent>` 已是 1.0.21） |
| **模块数** | 4（`z-skill-api` / `z-skill-core` / `z-skill-starter` / `z-skill-admin`），5 个坐标含聚合 POM |
| **Maven Central** | **已发布**：`0.1.0`/`0.1.1`/`0.1.2`/`0.2.0`/`0.2.1`/`0.2.2` 六个版本的 api/core/starter/admin 的 `.pom` 与 `.jar` 均可从 repo1 取回（ranged GET 实测 206；`maven-metadata.xml` latest/release 均为 `0.2.2`；反向对照 `0.2.3` 回 404）。[`_doc/006_release/`](_doc/006_release/) 那篇记的是 **0.2.0 那一趟**，不是当前版本号 |
| **默认端口** | 本仓不带端口（无可启动应用、无 yml）。测试宿主 `SkillHostLauncher` 读 `-Dserver.port`，缺省 `18099`；生产端口由宿主 `server.port` 决定 |
| **运行口径** | Java 8 · Spring Boot 2.7.18（版本口径与第三方地板均由父链 `z-boot-parent` → `z-boot-dependencies` 供给，本仓 POM 不再重复声明） |
| **下游 pin** | `z-boot-fleet` 的 `z-skill.version` 现为 `0.2.2`（全组织 `pom.xml` 里唯一一处该 pin）；对外一行集成走 `io.github.yuku123:z-boot-skill-starter:1.0.21`（它只依赖 `z-skill-starter`） |
| **最近更新** | 2026-09-30 |

---

## 🎯 能力清单（逐条对应到代码）

| 能力 | 实现 | 说明 |
|------|------|------|
| 平台形状识别 | `discover/FilesystemScanner`、`discover/PluginManifestReader`、`api/spec/SkillFormat` | 按目录/文件名形状判别 9 种 format；`SKIP_DIRS` 与 `NON_SKILL_DIRS` 避免把 `references/`、`scripts/` 里的 `.md` 当成独立 skill |
| 宽松 frontmatter 解析 | `core/spec/Frontmatter` | 兜住 CRLF/BOM/块标量/无引号冒号/行内与逗号列表/一层嵌套 metadata；解析失败不抛异常，问题进 `issues` |
| 规范校验与清洗 | `api/spec/SkillSpec` | `name` = `^[a-z0-9]+(?:-[a-z0-9]+)*$` 且 ≤64；description ≤1024、compatibility ≤500、正文 500 行软上限、扫描深度 6、单来源 2000 条、关键词最短 2；`slugify()` 把任意写法洗成合规 slug |
| 聚合与冲突消解 | `core/aggregate/SkillAggregator` | 流水线：来源队列 → 扫描/取数 → 归一化 → 命名空间 → 同内容折叠 → 冲突并存 → 风险判级 → 原子换表；产出 `AggregateReportDto` |
| 注册中心 | `core/registry/SkillRegistry` | 以 `id` 为唯一键 + slug/name 二级索引；`replaceAll` 原子换表并算出 `added/changed/removed`；安装记录与 `danglingInstalls` 都在这里 |
| 检索 | `core/search/SkillSearchEngine` | `q/category/tag/source/format/risk/installed` 过滤 + facets；`sort` 取 `relevance`（默认）/`name`/`installs`/`updated` |
| 静态风险体检 | `core/scan/SkillSecurityScanner` | 纯文本正则规则给出 `safe/low/medium/high/critical`，未扫描为 `unscanned`；从不 import、加载或执行被扫描内容 |
| 正文预览（沙箱） | `core/content/SkillContentReader` | 只读已登记条目的相对路径，任何路径必须落在该 skill 自己目录内，单次预览上限 512KB |
| 本机平台自动发现 | `core/discover/PlatformPresets` | 家目录 `~/.{claude,agents,codex,cursor,qoder,copilot,gemini,amp,cline}/skills`、`~/.qoder-cn/{plugins,skills}`、`~/.agents/skills`；项目目录 `.<client>/skills`、`.agents/skills`、`.cursor/rules`、`.github`、根 `AGENTS.md`；不存在的落点直接跳过 |
| Marketplace REST | `core/controller/SkillController`（`@RequestMapping("/skill")`） | 见下方 API 表 |
| 对外兼容输出 | `core/controller/SkillCompatController` | skills.sh 三组端点 + `.well-known` 索引 + Qoder 侧形状 |
| 错误包络 | `core/controller/SkillErrorAdvice` + `api/exception/SkillException` | `{error, message, code}`，HTTP 状态码取 `SkillException.getCode()`；advice 用 `basePackages` 限定，不改宿主全局异常行为 |
| 控制面 | `admin/controller/AdminController`（`@RequestMapping("/skill/admin")`）+ `admin/service/AdminQueryService` + 静态页 `z-skill-admin/src/main/resources/static/skill-admin/index.html` | 默认关闭；页面直接 fetch `/skill/admin/**`，无前端工程、无模板引擎 |
| 自动装配 | `starter/autoconfig/ZSkillAutoConfiguration`、`admin/autoconfig/ZSkillAdminAutoConfiguration` | 两个 `@Configuration` + `@ComponentScan`，注册在各自 `META-INF/spring.factories`（admin 还多一份 `AutoConfiguration.imports`） |

---

## 🧩 Skill 模型（按实现写，不按规范理想写）

**1. 定义**：一条 skill 就是"一个目录 + 一份带 YAML frontmatter 的说明文件"，或某个平台已有的等价载体：
`<name>/SKILL.md`（Agent Skills 规范，随包文件放 `scripts/` `references/` `assets/`）、平铺 `*.md`（0.1.x 遗留）、
`.cursor/rules/**/*.mdc`、`AGENTS.md` + `AGENTS.override.md`、`.github/copilot-instructions.md` 与
`.github/instructions/*.instructions.md`、`.well-known/agent-skills/index.json`、skills.sh 风格 JSON、
`installed_plugins_v2.json` / `.qoder-plugin/plugin.json` 插件清单。规范外的键（`descriptionZh`/`model`/
`when_to_use`/`icon`）不丢，原样进 `metadata`。

**2. 归一化后的唯一口径是 `SkillDto`**（30+ 字段，不可变，`toBuilder()` 派生）。三个身份字段不要混：
`name` 是来源原样写法（可能不合规，如 `RedBookSkills`，只用于展示）；`slug` 是清洗后的标识；
`id` 是聚合后的全局键，跨来源撞名时为 `sourceId/slug`，注册中心按它索引。其余按用途分组：
规范字段（`description` `version` `license` `compatibility` `allowedTools` `metadata`）、
检索字段（`category` `tags` `trigger` `paths`）、来源字段（`source` `sourceType` `origin` `skillFilePath`
`format` `resources` `contentHash` `bodyLines` `bodyChars`）、Marketplace 字段（`installCount` `installed`
`riskLevel` `issueCount`）、血缘字段（`aliases` `issues` `discoveredAt` `updatedAt`）。

**3. 注册**：`refresh()` 是唯一入口（启动时由 starter 的 `SkillStartupRunner` 跑一次，之后 `POST /skill/refresh`）。
每条来源扫描完才写 `SkillSourceDto` 视图（含 `status`/`durationMs`/`observedFormats`），最后一次性
`SkillRegistry.replaceAll`，读侧不会看到半张目录。注意两个字段别混：`format` 是配置里**声明**的形状，
`observedFormats` 是这次扫描**实际认出**的形状——自动发现的目录只能以 `unknown` 声明进场，
形状靠扫；控制台回显的是后者。

**4. 版本**：分两层，不要当成一层。
- **条目层**：`SkillDto.version` 是从来源 frontmatter / payload 原样带过来的字符串（来源没写就是空），
  z-skill 不维护版本序列、不做 semver 比较。真正的"上游漂移"靠 `contentHash`：换 hash 即 `changed`，
  消失即 `removed`，已装但消失的保留安装记录并进 `danglingInstalls`。
- **安装层**：`SkillInstallDto` 在安装那一刻快照 `version` + `contentHash` + `installRef` + `installedBy` + `channel`。
- **构件层**：库自己的版本是根 POM `<revision>`（现 0.2.2），子 POM 用 `${revision}`，flatten 展开后才发布。
- ⚠ `SkillDto.pinnedVersion` 目前只是 DTO 上的槽位：本仓 `core`/`admin` 主代码没有任何地方写它
  （`SkillsShApiNormalizerTest` 还专门断言收录阶段必须为空）。要看"装的是哪一版"，读安装记录而不是这个字段。

**5. 消费**：agent 侧三种拿法，全在 `SkillCompatController` / `SkillController`。
- 按规范自发现：`GET /.well-known/agent-skills/index.json`（同时挂 `/skill/compat/skills-sh/.well-known/agent-skills/index.json`
  等 3 条路径），每条给 `name`(=slug)、`description`、`files[]`，来源写了版本才带 `metadata.version`。
- 不改代码接老客户端：skills.sh 形状 `/skill/compat/skills-sh/api/search`、`/api/v1/skills`（`{data[],pagination{}}`）、
  `/api/v1/skills/**`（详情给 `hash`，`?files=true` 才带正文）、`/api/v1/skills/audit/**`
  （`provider=z-skill-static`，`status` 用外部字典 `pass/warn/fail`，未扫描给扩展值 `pending`）；
  Qoder 形状 `/skill/compat/qoder/skills`，命名是 `<source 清洗>:<slug>`。
- 直接查市场：`/skill/search` → `/skill/detail?id=` → `/skill/content?id=&file=` 拿正文，
  `POST /skill/install` / `DELETE /skill/uninstall?id=` 落状态。
- 外部标识一律 `source/slug`（`externalId`），本身可能带斜杠，见下方"带斜杠的 id"。

**6. 依赖面**：`z-skill-core` 的 POM 声明了 `z-agent-kernel-{skill,message,types,tool}:0.1.0`
（用直接 DM 覆盖 `z-boot-fleet` 的 0.1.1 槽位），但实测**主代码没有一处 import 它们**——
唯一被真正用到的第三方是 jackson-databind、spring-web/webmvc、spring-boot(-autoconfigure)、slf4j。
根 POM 注释里"依赖 kernel.skill SPI"是历史设计，不是当前实现；主代码也不引用 servlet API
（`CoreStaysServletFreeTest` 钉住这条，好让非 web 宿主能安全 component-scan core）。

---

## 🏗️ 项目结构

```
z-skill/
├── pom.xml             # 聚合 POM：z-boot-parent:1.0.21、<revision>0.2.2</revision>、5 坐标自钉、central profile
├── LICENSE             # MIT
├── _doc/               # 非编号目录（本仓没按 org 的 001_arch 树收口），见文末「文档目录」
├── z-skill-api/        # DTO + 异常 + SkillFormat/SkillSpec，零第三方依赖；无测试类
├── z-skill-core/       # discover / normalize / aggregate / registry / search / scan / content / spec / controller
├── z-skill-starter/    # ZSkillAutoConfiguration + spring.factories；不依赖 admin
└── z-skill-admin/      # 控制面：ZSkillAdminAutoConfiguration + AdminController + AdminQueryService + 静态控制台页
```

`z-skill-admin` **没有** `maven.deploy.skip`：实测它的 `.pom`/`.jar` 就在 Maven Central 上（0.1.0~0.2.2 全在）。
"控制面默认拿不到"靠的是两道装配条件（模块不进 starter 依赖 + 属性双钥匙），不是靠不发布。
本仓没有 `Dockerfile`、`docker-compose*.yml`、`deploy/`、`k8s/`、`Makefile`——产物只有 Maven 构件。

---

## 🔧 技术栈

| 层级 | 技术（均来自 POM 与源码实测） |
|------|------|
| 语言 / 运行时 | Java 8（父链 `maven.compiler.source/target=8`、编码 UTF-8） |
| 框架 | Spring Boot 2.7.18（地板提供，本仓不写版本钉）；web 层只用 spring-web/spring-webmvc 注解 |
| JSON | jackson-databind（`ObjectMapper` 由宿主注入，缺省时自建） |
| 远端取数 | `HttpFetcher.Jdk`（`HttpURLConnection`，连接/读超时各 8000ms，跟随跳转，响应体 8MB 上限，按 UTF-8 解码） |
| 日志 | slf4j-api |
| 测试 | JUnit 4（`junit:junit`）+ JUnit 5 / spring-boot-starter-test（core 排除 vintage，starter 显式补回）；MockMvc + 真 Tomcat 宿主两种量具 |
| 构建 | Maven · flatten-maven-plugin 1.7.2（`oss`）· central-publishing-maven-plugin 0.8.0（`autoPublish=true`）· maven-release-plugin 3.1.1（`v@{project.version}`） |

---

## 🚀 快速开始

### 编译 / 测试

```bash
mvn -o clean install      # 全 reactor；clean 不要省（target/ 里的陈货能让已作废的断言继续绿）
```

### 作为库接入宿主

```xml
<!-- 方式一：z-boot 聚合 starter（版本由 z-boot-fleet 供给，现指向 z-skill 0.2.2） -->
<dependency>
    <groupId>io.github.yuku123</groupId>
    <artifactId>z-boot-skill-starter</artifactId>
</dependency>

<!-- 方式二：直接用本仓坐标 -->
<dependency>
    <groupId>io.github.yuku123</groupId>
    <artifactId>z-skill-starter</artifactId>
    <version>0.2.2</version>
</dependency>
```

控制面不在 starter 的依赖里，要它才加 `io.github.yuku123:z-skill-admin:0.2.2`。

### starter 的自动装配键（前缀 `z.skill`，全部实测自 `SkillProperties` 与两处 `@ConditionalOnProperty`）

| 配置键 | 类型/缺省 | 实际作用 |
|--------|-----------|----------|
| `z.skill.enabled` | 必须显式写 `true` | 总开关。`ZSkillAutoConfiguration` 的条件是 `havingValue="true", matchIfMissing=false`——没写就什么都不会装配（避免和宿主遗留的 skill-center 抢控制器映射）。注意 `SkillProperties.enabled` 字段本身缺省是 `true`，那个值只在直接 new bean 的测试路径里起作用 |
| `z.skill.expose-admin` | `false` | 控制面第二道钥匙，和 `z.skill.enabled` 写在同一个条件里（只开控制面会让容器以 `UnsatisfiedDependency` 起不来） |
| `z.skill.refresh-on-startup` | `true` | 关掉则跳过启动聚合，等 `POST /skill/refresh` |
| `z.skill.discover-installed-platforms` | `true` | 额外挂上 `PlatformPresets.detect()` 的本机落点，起始优先级 1000（低于显式来源 ⇒ 显式配置永远覆盖自动发现） |
| `z.skill.sources[]` | 空 | 每项支持 `id/format/path/url/priority/enabled/category/max-depth`；`format` 省略即 `unknown` 由扫描器判形；`priority` 字段缺省是 `100`，所以"按声明顺序给 10/20/30…"那条只在显式写 `priority<=0` 时才生效；本地路径不存在 → 跳过该来源并记日志，不影响其它来源 |
| `z.skill.sources[].max-depth` | ⚠ **`0`** | 见下方"深度口径"，这是最容易踩的一条 |
| `z.skill.max-depth` | `6` | **只作用于自动发现的来源**，而且缺省值 6 走的是"保留各 preset 自带深度"那一支（用户级 `-1` → 扫描器回退 `SkillSpec.MAX_SCAN_DEPTH=6`；`~/.qoder-cn/plugins` 钉 2；项目根 `AGENTS.md` 钉 0）。把它配成 `<=0` 反而会把所有自动发现来源压成 0 层 |
| `z.skill.project-root` | `""` | 项目侧落点的搜索根 |
| `z.skill.user-home` | `""` | 家目录侧的根；空则回退 `user.home` 系统属性 |
| `z.skill.scan-remote-on-startup` | `false` | ⚠ **死键**：`SkillProperties` 里有字段与 getter，但 core/starter 主代码没有任何读取点（实测 grep 只命中属性类自身）。它**不**关闭远端拉取——`refresh()` 对 `http(s)` 来源一律走 `HttpFetcher`。要真不在启动时打网络，只关 `z.skill.refresh-on-startup` |

yml 形状示例：

```yaml
z:
  skill:
    enabled: true
    refresh-on-startup: true
    discover-installed-platforms: true
    sources:
      - id: anthropic-official
        format: agent-skills
        path: /abs/path/official-skills
        max-depth: 2          # 必须写：规范形状是 <name>/SKILL.md，位于第 1 层
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

**深度口径（实测 `FilesystemScanner:55` + `SkillProperties.Source:76`）**：扫描器按
`source.maxDepth >= 0 ? source.maxDepth : 6` 取深度，而 `sources[].max-depth` 不写时是 `0` ——
也就是**只收来源根那一层的文件**。所以照旧 README 那样只写 `path: /abs/official-skills` 配
`format: agent-skills`，`<name>/SKILL.md` 一条都收不到（聚合报告会是 0 产出、也不报错）。
`-1` 才是"回退到 `SkillSpec.MAX_SCAN_DEPTH=6`"那一档，但它只在直接 `new SkillSource(...)` 时才是缺省。

### 环境变量名（只列键名，凭据不进 README）

本仓不读任何 `System.getenv`，全部经 Spring Boot 宽松绑定；宿主侧常见的形式：

| 环境变量 | 对应配置 |
|----------|----------|
| `Z_SKILL_ENABLED` | `z.skill.enabled` |
| `Z_SKILL_EXPOSE_ADMIN` | `z.skill.expose-admin` |
| `Z_SKILL_REFRESH_ON_STARTUP` | `z.skill.refresh-on-startup` |
| `Z_SKILL_DISCOVER_INSTALLED_PLATFORMS` | `z.skill.discover-installed-platforms` |
| `Z_SKILL_PROJECT_ROOT` / `Z_SKILL_USER_HOME` | `z.skill.project-root` / `z.skill.user-home` |
| `SERVER_PORT` | 宿主端口（本仓不决定） |

### 本机跑一个真宿主（只有测试路径，不进产物）

```bash
mvn -o -pl z-skill-admin -am clean test-compile
mvn -o -pl z-skill-admin dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -Dserver.port=0 \
     -cp "z-skill-admin/target/test-classes:z-skill-admin/target/classes:$(cat z-skill-admin/target/cp.txt)" \
     com.zifang.z.skill.adminharness.SkillHostLauncher
```

`SkillHostLauncher` 在 `src/test` 域，写死注入 `z.skill.enabled=true` 与 `z.skill.expose-admin=true`，
端口取 `-Dserver.port`（缺省 18099）。发布记录里踩过一条坑，值得照抄：**别固定端口复验**，
`bind(0)` 取空闲口——18099 曾被另一个会话的 SSH 隧道占走，探针会把别的服务的 404 当成自己的。

---

## 🔌 API 一览

全部路径实测自 `@RequestMapping` / `@GetMapping` / `@PostMapping` / `@DeleteMapping`，无 `context-path` 假设。

### Marketplace（`/skill/*`，随 `z.skill.enabled=true` 打开）

| 方法 | 路径 | 参数 |
|------|------|------|
| GET | `/skill/search` | `q` `category` `tag` `source` `format` `risk` `installed` `sort`（默认 relevance）`page`（默认 0）`perPage`（默认 20），返回带 facets |
| GET | `/skill/list` | `category` `tag` `source` |
| GET | `/skill/installed` | — |
| GET | `/skill/categories` | — |
| GET | `/skill/sources` | — |
| GET | `/skill/stats` | — |
| GET | `/skill/updates` | —（`added`/`changed`/`removed` + 失效安装） |
| GET | `/skill/detail` | `id`（必填，id/slug/name/alias 均可寻址） |
| GET | `/skill/{id}` | 同上，路径形 |
| GET | `/skill/content` | `id` + 可选 `file`（资源相对路径） |
| GET | `/skill/{id}/content` | `file` 可选 |
| POST | `/skill/install` | JSON body：`id` 或 `name`，可选 `installedBy`（缺省 `system`）、`source`/`channel`（缺省 `marketplace`）；重复安装 409，找不到 404；回执过 `PublicSurface` |
| DELETE | `/skill/uninstall` | `id`（**可用形**） |
| DELETE | `/skill/uninstall/{id}` | 路径形，见下方警告 |
| POST | `/skill/refresh` | — |

**带斜杠的 id 一律走查询串。** 聚合来的 id 天然长成 `source/slug`（`anthropics/skills/pdf`），
而 Tomcat 默认拒收路径里的 `%2F`（实测 400，请求根本到不了 Spring），不编码又会被切成多段（404）。
所以 `@DeleteMapping("/uninstall/{id}")` 对**绝大多数**聚合条目是死的，`?id=` 才是可用形；
`SkillHostOverHttpTest` 在真 Tomcat 上做「装→读回→卸→读回」往返，`AdminControllerExposureTest`
钉住控制台页不许把 id 拼回路径段。

### 对外兼容输出（无需 `expose-admin`）

| 路径 | 形状 |
|------|------|
| `/skill/compat/skills-sh/api/search` | `q` `limit`(1..200，默认 50) `owner` → `query` `searchType` `skills[]` `count` `duration_ms` |
| `/skill/compat/skills-sh/api/v1/skills` | `view`（`hot`→按 updated，否则按 installs）`page` `per_page` → `{data[], pagination{}}` |
| `/skill/compat/skills-sh/api/v1/skills/**` | 详情：`hash` + `files[]`；`?files=true` 才带正文；`installUrl` 缺省回落到 `{source}/{slug}` |
| `/skill/compat/skills-sh/api/v1/skills/audit/**` | `{audits:[{provider:"z-skill-static", slug, status, riskLevel, categories, summary}]}` |
| `/.well-known/agent-skills/index.json`、`/.well-known/skills/index.json`、`/skill/compat/skills-sh/.well-known/agent-skills/index.json` | 规范注册表索引 |
| `/skill/compat/qoder/skills` | Qoder 侧形状：`name`=`<ns>:<slug>`、`description`、`location`、`source` |

`**` 那两个端点吃原样斜杠的 `source/slug`：取值来自 Spring MVC 已解码的 `pathWithinHandlerMapping`
请求属性，`AntPathMatcher` 与 `PathPatternParser` 下实测一致（也正因如此 core 主代码不引用 servlet API）。

### 控制面（`/skill/admin/*`，`z.skill.enabled=true` 且 `z.skill.expose-admin=true` 且把 `z-skill-admin` 加进 classpath）

`GET` 七个：`/skill/admin/overview`、`/skills`、`/installed`、`/categories`、`/sources`、`/issues`、`/health`；
静态页 `/skill-admin/index.html`（Boot 默认静态资源映射，页内只 fetch 上面这七个）。

---

## 🛡️ 安全默认（逐条对着代码写）

- **总开关默认关**：`@ConditionalOnProperty(matchIfMissing = false)`。
- **控制面默认关**，且 `z-skill-admin` 不在 starter 依赖里 ⇒ 两道门。聚合结果含第三方描述与本机绝对路径，不该在未鉴权端口上裸露。
- **公共面过 `PublicSurface`**：只抹**本机目录**那四种写法（`file:` URI、`/` 开头、`\\` UNC、盘符开头），
  远端 `owner/repo@skill` 这类不透明引用、相对路径（`skillFilePath`、`resources[].path`、`files[]`）、
  `contentHash` 全留。真路径只留在 `/skill/admin/*`，沙箱读正文仍用真路径（所以抹完 `detail` 照样能读到
  `contentAvailable=true`）。`SkillControllerTest` 从映射注解推导全部公共出口，每条都必须在"出口账本"上
  落一笔并指向一条真实存在的测试，新增出口不落账就红、账上挂着已删的出口也红。
- **正文读取是沙箱**：拒绝 `../`、绝对路径、编码穿越，越界给 4xx 而不是 5xx。
- **静态扫描，不执行**：`SkillSecurityScanner` 的规则分 `CRITICAL/HIGH/MEDIUM/...` 若干档（`pipe-to-shell`、
  `destructive-root`、`fork-bomb`、`credential-read`、`secret-in-plainview`、`env-file-exfil`、`reverse-shell`、
  `force-push`、`sudo`、`escalated-container`、`installs-third-party`、`npx-fetch-run`、`raw-http-endpoint`、
  `system-file-write`、`process-kill` 等），每条命中都要能指出片段，便于人核对。
- **控制台不拼 `innerHTML`**：第三方 description/path 一律走 `textContent`。
- **鉴权不在本模块职责内**：由宿主网关 / `z-ctc` 统一拦截；z-skill 只保证默认不主动暴露。

---

## 📐 聚合语义

- 一次 `refresh()` 产出 `AggregateReportDto`：`durationMs` `sourceCount` `skillCount` `rawCount`
  `producedCount` `droppedCount` `dedupedCount` `conflictCount` `issueCount` + 逐来源视图 + issues
  + `formatDistribution` + `severityDistribution`。
- 报告里恒等式只有两条：`producedCount == skillCount + dedupedCount`、`Σ 逐来源产出行 == producedCount`。
  `rawCount` 按**发现层交给适配器的东西**计数（一份远端响应 = 一个候选，展开成 3 条是产出侧的事），
  所以 `producedCount` 可以大于 `rawCount`。
- severity 只按"有没有进目录"分：`error` = 没进目录（或整个来源失败）；`warning` = 进了但不规范；
  `info` = 进了、留痕（例如描述是从正文猜的）。issue 的 `code` 是有界裸码，不带 severity、不带命名空间、
  不带取值（取值在 `message` 里）。
- 同内容（同 `contentHash`）跨来源重复 → 折成一条，落选写法进 `aliases`，安装量累加。
  不同内容撞同一标识 → 不覆盖不丢弃，用 `sourceId/slug` 并存，计入 `conflictCount`。
- `installed` 是注册表自己的状态，不是来源给的数据：`SkillRegistry` 每条读路径（`get`/`require`/`listAll`/
  `snapshot`/`installedSkills`）都按本地安装记录改写它，上游自称 `installed=true` 一样被本地事实盖掉。
- 派生来源（插件清单展开的新来源）有深度上限 `MAX_DERIVED_DEPTH=3`；清单本身不算产出也不算丢弃。
- 守卫：`SkillSpec.MAX_SKILLS_PER_SOURCE=2000`、`MAX_SCAN_DEPTH=6`、正文 500 行软上限；触顶时**记 issue 后截断**，不静默丢弃。

---

## 🧪 测试

```bash
mvn -o clean test                                     # 全 reactor，离线可跑
mvn -o -pl z-skill-core -am -Dtest=HttpFetcherJdkLiveTest test   # 单类
```

测试类分布（实测 `find */src/test -name '*Test.java'`）：core 24、admin 4、starter 1、api 0。
0.2.0 那一趟发布构建的全量读数记在 [`_doc/006_release/`](_doc/006_release/)：330 条 / 0 失败 / 0 错误（core 295、starter 12、admin 23）；
本次 README 更新**没有重跑 mvn**，所以那个数是那一趟的读数，不是今天的读数。

分层：frontmatter/规范解析边界 → 各家形状适配器 → 文件系统扫描与插件清单派生 → 远端取数在**真 socket**
上（`HttpFetcherJdkLiveTest`，本机起监听口，不打外网：状态码、超时、8MB 上限、跟跳转、字符集，
以及"聚合器默认就用这个取数器"这一句）→ 聚合/检索/扫描/内容读取 → REST 与兼容形状
（含两种路径匹配器下的多段 id 路由）→ 端到端真语料。夹具在 `z-skill-core/src/test/resources/fixtures/`。

已知需要外部条件、如实写的两点：

1. **`RealWorldSkillCorpusTest` 依赖同工作区的兄弟仓**：语料根按
   `-Dz.skill.corpus.root` → `<user.dir>/corpus-repo` → 从 `user.dir` 上溯找"同时含 `z-skill` 和 `z-opc` 的目录"
   三级解析，实际挂的是 `z-opc/_doc/005_skills`、`z-opc/.qoder/skills`、`z-opc/.design_library`、
   `z-opc/_doc/003_building/skills`、`z-env/skills/XiaohongshuSkills`、
   `z-opc-foundation-lead/003_辅助能力`、`z-lc/z-lc-admin-ui/node_modules/playwright-core/lib/tools/skills`
   这些目录（本工作区 7 个全在）。**单独 clone 本仓跑不了这条**，它刻意设计成解析不出来就失败——
   跳过的语料测试等于没有。
2. `SkillControllerTest.unreadableContentReasonCarriesNoHostPath` 用 `assumeTrue` 要求文件系统支持 `posix`
   权限位；非 POSIX 文件系统上这条会跳过（即该分支在那类机器上未被验证）。

量具欠账登记在 `_doc/007_backlog/feature003_gauge_gaps/`：`HttpFetcher` 的状态码判定对 500 是**等价变异**
（JDK 自己就抛，摘掉不红；真正拦住的是跨协议 307），UTF-8 那条在本机等价（`file.encoding=UTF-8`）。

---

## 📦 发布

```bash
mvn -o clean install            # 日常构建：离线
mvn clean deploy -Pcentral      # 发布那一遍：绝对不要带 -o
```

⚠ 实测过的雷（记录在 `_doc/006_release/001_0.2.0发布与对账.md`）：`mvn -o deploy -Pcentral`
会**静默什么都不发**并印 BUILD SUCCESS（插件对每个模块提示 offline 下 skip publish）。
判"发没发"只认 repo1 的构件状态码 + `maven-metadata.xml`：`curl` 对 404 的 shell rc 仍是 0，
用 rc 判会全绿；本仓实测 Sonatype 发布状态 API 两轮都返 HTTP 500，也不作数。
`central` profile 已补 `<autoPublish>true</autoPublish>`——缺它时 deployment 停在 validated 态、构件对外不可见，
而 Maven 一路绿。

---

## 📄 License

MIT，见仓库根 [`LICENSE`](LICENSE)（版权方 z-opc-foundation）；根 POM `<licenses>` 同声明。

_Maintained by the z-opc-foundation organization._

---

## 文档目录

本仓 `_doc/` **没有**按组织的编号收口（不是 `001_arch` / `002_deploy` / `003_script` / `004_skill` 那棵树），
下面是现有真实文件的全量链接，不编造 conforming 结构：

- [`_doc/006_release/`](_doc/006_release/) — Central 发布与对账记录（一趟的全程判据，供下一趟照着跑）:
  - [`001_0.2.0发布与对账.md`](_doc/006_release/001_0.2.0发布与对账.md) — `0.2.0` 那一趟的提交/上传/可见/字节对账/验签/标签读数，
    含上面「发布」两节里那几条雷的取数命令，以及 §7"本机跑的确实是发布字节"怎么量（`bind(0)` 那条教训）。状态：**已闭合**。
- [`_doc/007_backlog/`](_doc/007_backlog/) — 要人拍板或要等外部条件的事项登记（每条一个 `featureNNN_<名字>/` 目录）:
  - [`README.md`](_doc/007_backlog/README.md) — 待办索引：两格现状、登记口径（"数字一律现场测，不许抄文档里的旧数"）。
  - [`feature002_downstream_pins/001_抬pin与验证.md`](_doc/007_backlog/feature002_downstream_pins/001_抬pin与验证.md) —
    抬下游 `z-skill.version` pin 的验证方法。**注**：文里记的"z-boot 已抬 / z-opc 两处仍 0.1.2"是 09-26 的读数，
    今天实测全组织只剩 `z-boot-fleet` 一处 `z-skill.version=0.2.2`（`rg "z-skill\.version>" -g 'pom.xml'`）。
  - [`feature003_gauge_gaps/001_待排产.md`](_doc/007_backlog/feature003_gauge_gaps/001_待排产.md) — 量具上三个洞
    （两支等价变异 + 一条下游占位断言），状态：**待排产**（不等裁定，等归属）。

架构、部署、脚本类的文档目前本仓没有；能力与契约以本 README 上文（每条都对应到 `src/main` 里的类或注解）
和测试源码为准。
