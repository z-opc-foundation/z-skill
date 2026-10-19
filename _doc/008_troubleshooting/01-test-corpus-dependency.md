# z-skill 测试的语料依赖：playwright-core 必须先装

## 现象

`mvn -o test` 里 `RealWorldSkillCorpusTest` 6 条失败，报的是语料不全：

    语料来源没全挂上, 缺的目录: 根=… 实挂=[corpus-xhs, corpus-zopc-doc-skills,
                                             corpus-qoder, corpus-lead, corpus-novel, corpus-designlib]
    候选条目只有 27 个, 语料没被扫全: raw=27 …
    语料聚合结果里没有 id=playwright-cli; 实有: […]

**看着像 6 个功能缺陷，实际只有一个原因**：`corpus-playwright` 那份语料
指向 `z-lc/z-lc-admin-ui/node_modules/playwright-core/lib/tools/skills`，
而 `z-lc-admin-ui` 的 `node_modules` 没装 ⇒ 那份语料不存在。

其余 6 份（`z-env/skills/XiaohongshuSkills`、`z-opc/_doc/005_skills`、
`z-opc/.qoder/skills`、`z-opc-foundation-lead/003_辅助能力`、
`z-opc/_doc/003_building/skills`、`z-opc/.design_library`）都在盘上。

## 这份依赖是合法的，不是幻觉

`z-lc/z-lc-admin-ui/package.json` 第 53 行**明确声明**了
`"playwright-core": "^1.63.0"`。所以语料指向它是设计内的，
不是测试凭空捏的路径。

## 修法

    cd z-lc/z-lc-admin-ui && npm install

装完 `mvn -o test`：**Tests run: 295 + 12 + 23 = 330, 0 failures, BUILD SUCCESS**。

## 为什么值得记

1. **报错的形态是"内容缺失"而不是"环境缺失"**。
   6 条失败里 4 条在说"语料里没有 id=xxx"、1 条在说"数量对不上"，
   只有第 1 条（`corpusIsRealAndNonTrivial`）点出了"缺的目录"——
   **它排在最前面且消息最直白，是唯一一条能直接指路的**。
   判据：一组失败同时抱怨"内容比预期少" ⇒ 先查内容从哪来、来源挂上了没有。
2. **语料根目录可以覆盖**：`resolveCorpusRootOrFail()` 支持
   `-Dz.skill.corpus.root=<path>`，也认 `user.dir/corpus-repo` 与向上找 `z-skill/`。
   换机器跑时用这个指过去，不要改测试里的路径常量。
3. 同族提醒：`z-lc/z-lc-admin-ui`、`z-opc/bootstraps/z-opc-main-starter-frontend`、
   `z-render` 这类前端工程的 `node_modules` 都是**未提交**的，
   任何跨仓读它们内容的测试都会在干净机器上红。
   判据：测试里出现 `node_modules/...` 字样的，**先装依赖再谈失败**。
