package com.zifang.z.skill.core.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.dto.SkillInstallDto;
import com.zifang.z.skill.api.dto.SkillResourceDto;
import com.zifang.z.skill.api.dto.SkillSourceDto;
import com.zifang.z.skill.api.exception.SkillException;
import com.zifang.z.skill.api.spec.SkillFormat;
import com.zifang.z.skill.core.aggregate.SkillAggregator;
import com.zifang.z.skill.core.content.SkillContentReader;
import com.zifang.z.skill.core.discover.SkillSource;
import com.zifang.z.skill.core.registry.SkillRegistry;
import com.zifang.z.skill.core.search.SkillSearchEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 市场侧 REST 的形状测试: 检索/筛选/分页/详情(按 id、slug、alias)/安装回路/分面/聚合报告/正文预览.
 *
 * <p>断言全部打在键名上 —— 兼容层的产品承诺就是键名.
 *
 * <p><b>为什么没走 MockMvc</b>: z-skill-core 的测试类路径上没有 javax.servlet —— spring-webmvc 只以
 * provided 方式带 servlet-api(不传递), 本模块又不依赖 spring-boot-starter-web, 而 pom 不许我改.
 * 实测 {@code MockMvcBuilders.standaloneSetup(...)} 在 {@code setUp} 里就抛
 * {@code NoClassDefFoundError: javax/servlet/ServletException}(28 个用例全挂). 因此这里退一步:
 * 直接调用控制器方法, 用与 HTTP 层同一个 Jackson 把返回值序列化成 JSON, 再按 JSONPath 断言.
 * 形状/键名/错误包络的断言力度不变, 丢的只是 URL 路由本身.
 */
public class SkillControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SkillErrorAdvice advice = new SkillErrorAdvice();
    private SkillRegistry registry;
    private SkillSearchEngine search;
    private SkillContentReader reader;
    private SkillAggregator aggregator;
    private SkillController controller;

    @BeforeEach
    public void setUp() {
        registry = new SkillRegistry();
        search = new SkillSearchEngine(registry);
        reader = new SkillContentReader();
        registry.register(pdf());
        registry.register(dataImport());
        registry.register(weather());
        rebuild();
    }

    private void rebuild() {
        aggregator = new SkillAggregator(registry, mapper);
        aggregator.addSource(new SkillSource("mkt-local", SkillFormat.AGENT_SKILLS, marketplaceRoot(), 10));
        controller = new SkillController(registry, search, aggregator, reader);
    }

    // ---------- 检索 ----------

    @Test
    public void search_echoesSkillsShEnvelope() {
        Resp hit = new Resp(controller.search("pdf", null, null, null, null, null, null, "relevance", 0, 20));
        assertEquals("pdf", hit.str("$.query"));
        assertEquals("fuzzy", hit.str("$.searchType"));
        assertEquals(1, hit.num("$.count"));
        assertEquals(1, hit.num("$.total"));
        assertEquals(0, hit.num("$.page"));
        assertEquals(20, hit.num("$.perPage"));
        assertTrue(!hit.flag("$.hasMore"));
        assertTrue(hit.num("$.durationMs") >= 0);
        assertEquals("pdf-processing", hit.str("$.skills[0].id"));
        assertEquals("agent-skills", hit.str("$.skills[0].formatId"));
        assertTrue(!hit.flag("$.skills[0].installed"));
        assertTrue(hit.map("$.facets").containsKey("documents"));

        Resp twoWords = new Resp(controller.search("pdf extract", null, null, null, null, null, null,
                "relevance", 0, 20));
        assertEquals("semantic", twoWords.str("$.searchType")); // 多词走 semantic 口径
        assertEquals("pdf extract", twoWords.str("$.query"));

        Resp browse = new Resp(controller.search(null, null, null, null, null, null, null, "relevance", 0, 20));
        assertEquals("browse", browse.str("$.searchType"));
        assertEquals("", browse.str("$.query"));
        assertEquals(3, browse.num("$.total"));
    }

    @Test
    public void search_facetsAreCountMaps() {
        Resp hit = new Resp(controller.search("pdf", null, null, null, null, null, null, "relevance", 0, 20));
        assertEquals(1, hit.num("$.facets.documents"));
        assertEquals(1, hit.num("$.allFacets.category.documents"));
        assertEquals(1, hit.num("$.allFacets.tag.pdf"));
        assertEquals(1, hit.num("$.allFacets.source.anthropics"));

        Resp all = new Resp(controller.search(null, null, null, null, null, null, null, "relevance", 0, 20));
        assertEquals(3, all.map("$.allFacets.category").size());
        assertTrue(all.map("$.allFacets.tag").containsKey("etl"));
        assertEquals(3, all.map("$.allFacets.source").size()); // 三个来源都得有分面
    }

    @Test
    public void search_filters() {
        assertEquals("acme-registry/weather-lookup", new Resp(controller.search(null, "weather", null, null,
                null, null, null, "relevance", 0, 20)).str("$.skills[0].id"));
        assertEquals("site/data-import", new Resp(controller.search(null, null, "ETL", null, null, null, null,
                "relevance", 0, 20)).str("$.skills[0].id")); // tag 大小写不敏感
        assertEquals(1, new Resp(controller.search(null, null, null, "site", null, null, null, "relevance", 0, 20))
                .num("$.total"));
        assertEquals("weather-lookup", new Resp(controller.search(null, null, null, null, "registry-index", null,
                null, "relevance", 0, 20)).str("$.skills[0].slug"));
        assertEquals("data-import", new Resp(controller.search(null, null, null, null, null, "high", null,
                "relevance", 0, 20)).str("$.skills[0].slug"));
        Resp none = new Resp(controller.search(null, null, null, null, null, null, Boolean.TRUE, "relevance", 0, 20));
        assertEquals(0, none.num("$.total"));
        assertTrue(none.list("$.skills").isEmpty());
        assertEquals(0, new Resp(controller.search(null, "nope", null, null, null, null, null, "relevance", 0, 20))
                .num("$.total"));
    }

    @Test
    public void search_paginationAndSorts() {
        Resp first = new Resp(controller.search(null, null, null, null, null, null, null, "relevance", 0, 2));
        assertEquals(2, first.list("$.skills").size());
        assertEquals(3, first.num("$.total"));
        assertTrue(first.flag("$.hasMore"));
        assertEquals("pdf-processing", first.str("$.skills[0].id")); // 无 query 时相关度退到 installs

        Resp second = new Resp(controller.search(null, null, null, null, null, null, null, "relevance", 1, 2));
        assertEquals(1, second.list("$.skills").size());
        assertEquals("site/data-import", second.str("$.skills[0].id"));
        assertTrue(!second.flag("$.hasMore"));

        assertEquals(Arrays.asList("pdf-processing", "acme-registry/weather-lookup", "site/data-import"),
                new Resp(controller.search(null, null, null, null, null, null, null, "installs", 0, 20))
                        .ids("$.skills[*].id"));
        assertEquals(Arrays.asList("acme-registry/weather-lookup", "pdf-processing", "site/data-import"),
                new Resp(controller.search(null, null, null, null, null, null, null, "name", 0, 20))
                        .ids("$.skills[*].id"));
        assertEquals(Arrays.asList("site/data-import", "acme-registry/weather-lookup", "pdf-processing"),
                new Resp(controller.search(null, null, null, null, null, null, null, "updated", 0, 20))
                        .ids("$.skills[*].id"));
        // 负页码不得变成 500, 也不得悄悄回吐整表
        assertEquals(0, new Resp(controller.search(null, null, null, null, null, null, null, "relevance", -1, 20))
                .num("$.page"));
    }

    @Test
    public void list_returnsWholeCatalogInStableOrder() {
        Resp resp = new Resp(controller.list(null, null, null));
        assertEquals(3, resp.num("$.total"));
        assertEquals(3, resp.list("$.skills").size());
        assertEquals(Arrays.asList("acme-registry/weather-lookup", "pdf-processing", "site/data-import"),
                resp.ids("$.skills[*].id"));
        assertTrue(!resp.flag("$.skills[1].installed"));
        assertEquals(1, new Resp(controller.list("weather", null, null)).num("$.total"));
        assertEquals("data-import", new Resp(controller.list(null, null, "site")).str("$.skills[0].slug"));
    }

    @Test
    public void list_doesNotSilentlyTruncateAtSearchPageCap() {
        for (int i = 0; i < 250; i++) {
            String id = String.format("bulk/skill-%03d", i);
            registry.register(SkillDto.builder().id(id).slug("skill-" + i).name("skill-" + i)
                    .description("bulk catalog entry " + i).source("bulk")
                    .format(SkillFormat.AGENT_SKILLS).contentHash("h-" + i).build());
        }
        Resp resp = new Resp(controller.list(null, null, null));
        assertEquals(253, resp.num("$.total"));
        // /skill/list 的语义是"整个目录": 被检索层的 perPage 上限截断等于前端少 53 个 skill
        assertEquals(253, resp.list("$.skills").size());
    }

    // ---------- 详情 ----------

    @Test
    public void detail_byIdSlugAndAlias() {
        Resp resp = new Resp(controller.detail("pdf-processing"));
        assertEquals("pdf-processing", resp.str("$.skill.id"));
        assertEquals("pdf-processing", resp.str("$.skill.slug"));
        assertEquals("pdf-processing/SKILL.md", resp.str("$.files[0].path"));
        assertTrue(resp.flag("$.files[0].entry"));
        assertEquals("references/notes.md", resp.str("$.files[1].path"));
        assertEquals("reference", resp.str("$.files[1].kind"));
        assertTrue(resp.flag("$.contentAvailable"));
        assertEquals("claude", resp.str("$.frontmatter.compatibility"));
        resp.absent("$.install", "没装过的 skill 不该有 install 块");

        assertEquals("site/data-import", new Resp(controller.detail("data-import")).str("$.skill.id"));
        assertEquals("pdf-processing", new Resp(controller.detailById("anthropic/pdf")).str("$.skill.id"));
        Resp remote = new Resp(controller.detailById("acme-registry/weather-lookup"));
        assertTrue(!remote.flag("$.contentAvailable"));
        assertTrue(remote.str("$.contentReason").length() > 0);
    }

    @Test
    public void detail_unknownId_returnsSkillsShErrorEnvelope() {
        Resp missing = errorBody(() -> controller.detail("ghost"));
        assertEquals("skill_not_found", missing.str("$.error"));
        assertEquals(404, missing.num("$.code"));
        assertTrue(missing.str("$.message").contains("ghost"));

        Resp alsoMissing = errorBody(() -> controller.detailById("ghost"));
        assertEquals("skill_not_found", alsoMissing.str("$.error"));
        assertEquals(404, alsoMissing.num("$.code"));

        Resp uninstalled = errorBody(() -> controller.uninstall("pdf-processing"));
        assertEquals(404, uninstalled.num("$.code")); // 没装过 -> 404, 不是 200 空操作
        assertLeakFree(uninstalled);
    }

    // ---------- 正文与资源预览 ----------

    @Test
    public void contentPreview_mainAndDeclaredResource() {
        Resp main = new Resp(controller.content("pdf-processing", null));
        assertEquals("pdf-processing", main.str("$.id"));
        assertTrue(main.flag("$.available"));
        assertTrue(main.str("$.content").contains("PDF Processing"));
        // 正文预览的 path 也是对外契约的一部分: 它曾经放的是 target.toString(),
        // 于是这条出口把宿主机目录整个发出去 —— 而下面 400 分支反倒查了本机路径, 空跑的是成功分支.
        assertEquals("pdf-processing/SKILL.md", main.str("$.path"),
                "主文件预览要报源根相对路径, 和 files[0].path 同一个口径: " + main.str("$.path"));
        assertFalse(main.str("$.path").startsWith("/"), "预览 path 回到了绝对路径");

        Resp note = new Resp(controller.content("pdf-processing", "references/notes.md"));
        assertTrue(note.flag("$.available"));
        assertTrue(note.str("$.content").contains("MARKER-REFERENCE-NOTE"));
        assertEquals("references/notes.md", note.str("$.path"),
                "附属文件预览的 path 要和资源清单里的写法一致, 客户端拿它回查资源");

        Resp remote = new Resp(controller.content("weather-lookup", null));
        assertEquals("acme-registry/weather-lookup", remote.str("$.id"));
        assertTrue(!remote.flag("$.available"));
        assertNull(remote.raw("$.content")); // 读不到就给 null, 不许给空串冒充
        assertTrue(remote.str("$.reason").length() > 0);
    }

    @Test
    public void contentPreview_rejectsTraversalAndUndeclaredFilesWithClientError() {
        for (String bad : Arrays.asList("../../../etc/passwd", "..%2f..%2fetc%2fpasswd", "/etc/passwd",
                "secrets.txt", "references/../references/notes.md")) {
            Resp body = errorBody(() -> controller.content("pdf-processing", bad));
            assertEquals("bad_request", body.str("$.error"), "越界/未声明路径必须 400: " + bad);
            // 报错正文不许把本机绝对路径回吐出去
            assertTrue(!body.raw("$").toString().contains(System.getProperty("user.home")),
                    "400 正文回吐了本机路径: " + body.raw("$"));
        }
    }

    // ---------- 安装回路 ----------

    @Test
    public void install_uninstall_roundTrip() {
        Resp installed = new Resp(controller.install(jsonBody(
                "{\"id\":\"pdf-processing\",\"installedBy\":\"tester\",\"source\":\"cli\"}")));
        assertEquals("pdf-processing", installed.str("$.skillId"));
        assertEquals("pdf-processing", installed.str("$.skillName"));
        assertEquals("1.0.0", installed.str("$.version"));
        assertEquals("tester", installed.str("$.installedBy"));
        assertEquals("cli", installed.str("$.source"));
        assertEquals("hash-pdf", installed.str("$.contentHash"));
        // 这一度是 assertTrue(installRef.startsWith("file:")) —— 一条把漏点当契约钉住的断言:
        // 回执是公共出口, 本机 origin 不能原样回显(2026-09-25 实测到 /skill/install 吐 file:///Users/...)
        assertNull(installed.raw("$.installRef"), "本机来源的回执不许把 file: 地址发给公共面");
        // 必须是毫秒级 epoch: 秒级时间戳在客户端排序里会整体沉底, 而"> 0"看不出这件事
        assertTrue(installed.lng("$.installedAt") >= 1000000000000L, "installedAt 不是毫秒 epoch: " + installed.raw("$.installedAt"));

        Resp again = errorBody(() -> controller.install(jsonBody("{\"id\":\"pdf-processing\"}")));
        assertEquals(409, again.num("$.code"));
        assertEquals("already_installed", again.str("$.error"));

        assertTrue(new Resp(controller.list(null, null, null)).flag("$.skills[1].installed"));
        assertTrue(new Resp(controller.search("pdf", null, null, null, null, null, null, "relevance", 0, 20))
                .flag("$.skills[0].installed"));
        assertEquals("pdf-processing", new Resp(controller.detail("pdf-processing")).str("$.install.skillId"));
        Resp mine = new Resp(controller.installed());
        assertEquals(1, mine.num("$.total"));
        assertEquals("pdf-processing", mine.str("$.skills[0].id"));
        assertEquals("pdf-processing", mine.str("$.records[0].skillId"));
        assertTrue(mine.list("$.dangling").isEmpty());

        assertEquals("pdf-processing", new Resp(controller.uninstall("pdf-processing")).str("$.uninstalled"));
        assertEquals(0, new Resp(controller.installed()).num("$.total"));

        assertEquals("site/data-import", new Resp(controller.install(jsonBody("{\"name\":\"Data Import\"}")))
                .str("$.skillId")); // 按展示名安装也要走通
        assertEquals("site/data-import", new Resp(controller.uninstallById("data-import")).str("$.uninstalled"));
    }

    /**
     * 反向半边: 抹 {@code installRef} 只针对本机路径.
     *
     * <p>远端来源的这条按约定就是"不透明引用"(这里是 SKILL.md 的 URL), 客户端拿它做升级比对 ——
     * 一刀切把它也抹掉, 上面的"没有 file:"照样全绿, 所以两个方向必须各占一条测试.
     */
    @Test
    public void installReceiptKeepsRemoteReferences() {
        Resp receipt = new Resp(controller.install(jsonBody(
                "{\"id\":\"acme-registry/weather-lookup\",\"installedBy\":\"tester\"}")));
        // raw() 而非 str(): str() 会先抛"必须是字符串"这句通用诊断, 把下面这条契约盖掉 ——
        // 抹除逻辑一刀切时, 读测试的人要看到"不许一刀切", 而不是看到一个类型断言。
        assertEquals("https://acme.example/.well-known/agent-skills/weather-lookup/SKILL.md",
                receipt.raw("$.installRef"), "远端来源的安装引用被误抹了: 本机路径要抹, 远端引用是发给客户端的");
        assertEquals("hash-weather", receipt.str("$.contentHash"));
        assertEquals("acme-registry", receipt.str("$.sourceId"), "来源身份要留着, 客户端还要按它分组");
    }

    /**
     * installed 是一个事实, 但从四个出口讲出去: search / list / detail / installed.
     *
     * <p>盖章原本只发生在检索层, 于是绕过它的那两个出口稳定地回答"没装" —— {@code /skill/installed}
     * 的 {@code skills[0].installed=false} 和同一份响应里躺着的那条安装记录直接矛盾,
     * {@code /skill/detail} 也一样. 按出口各写一条断言的话, 漏写哪个出口就永远测不到哪个,
     * 所以这里把四处读数收进一个 Map 要求一致: 新增出口忘了登记会红, 某一个出口跑偏也会红.
     */
    @Test
    public void installStateReadsTheSameOnEveryExit() {
        Map<String, Boolean> before = installReadings("pdf-processing");
        for (String exit : new String[]{"search", "list", "detail"}) {
            assertEquals(Boolean.FALSE, before.get(exit), exit + " 上没装过却亮着: " + before);
        }
        assertNull(before.get("installed"), "没装过的东西不该出现在「我的安装」里");

        controller.install(jsonBody("{\"id\":\"pdf-processing\",\"installedBy\":\"tester\"}"));
        Map<String, Boolean> on = installReadings("pdf-processing");
        for (String exit : on.keySet()) {
            assertEquals(Boolean.TRUE, on.get(exit), exit + " 读数跑偏, 全量=" + on);
        }

        Map<String, Boolean> bystander = installReadings("site/data-import");
        for (String exit : new String[]{"search", "list", "detail"}) {
            assertEquals(Boolean.FALSE, bystander.get(exit), "装机不能把邻居一起点亮: " + exit + " " + bystander);
        }
        assertNull(bystander.get("installed"), "「我的安装」里冒出了没装过的那条");

        controller.uninstall("pdf-processing");
        Map<String, Boolean> off = installReadings("pdf-processing");
        for (String exit : new String[]{"search", "list", "detail"}) {
            assertEquals(Boolean.FALSE, off.get(exit), exit + " 卸载后还亮着: " + off);
        }
        assertNull(off.get("installed"), "卸载后「我的安装」里还剩这一条");
    }

    /** 值 = 该出口上这一行的 installed; null = 这个出口按语义没有这一行(只有「我的安装」允许). */
    private Map<String, Boolean> installReadings(String id) {
        Map<String, Boolean> out = new LinkedHashMap<String, Boolean>();
        out.put("search", flagIn(new Resp(controller.search(null, null, null, null, null, null, null,
                "name", 0, 50)), id));
        out.put("list", flagIn(new Resp(controller.list(null, null, null)), id));
        out.put("detail", new Resp(controller.detail(id)).flag("$.skill.installed"));
        Resp mine = new Resp(controller.installed());
        out.put("installed", mine.list("$.skills[*].id").contains(id) ? flagIn(mine, id) : null);
        return out;
    }

    /** 先按 id 找到它落在第几行, 再读那一行的标记 —— 位置写死的话, 排序一变就是假红/假绿. */
    private static Boolean flagIn(Resp resp, String id) {
        int at = resp.list("$.skills[*].id").indexOf(id);
        assertTrue(at >= 0, "出口里找不到 " + id + ", 实有: " + resp.list("$.skills[*].id"));
        return resp.flag("$.skills[" + at + "].installed");
    }

    @Test
    public void install_badInputIsClientError() {
        assertEquals("bad_request", errorBody(() -> controller.install(null)).str("$.error"));
        assertEquals("bad_request", errorBody(() -> controller.install(jsonBody("{}"))).str("$.error"));
        assertEquals("bad_request", errorBody(() -> controller.install(jsonBody("{\"name\":null}"))).str("$.error"));
        assertEquals("skill_not_found", errorBody(() -> controller.install(jsonBody("{\"id\":\"ghost\"}")))
                .str("$.error"));
    }

    // ---------- 目录侧信息 ----------

    @Test
    public void categories_facetShape() {
        Resp resp = new Resp(controller.categories());
        assertEquals(1, resp.num("$.categories.documents"));
        assertEquals(1, resp.num("$.categories.weather"));
        assertEquals(1, resp.num("$.categories.data"));
        assertEquals(1, resp.num("$.tags.pdf"));
        assertEquals(1, resp.num("$.tags.http"));
        assertEquals(3, resp.map("$.categories").size());
    }

    @Test
    public void refresh_reportsCountsPerSourceRowsAndIssues() {
        Resp report = new Resp(controller.refresh());
        assertTrue(report.num("$.durationMs") >= 0);
        assertEquals(1, report.num("$.sourceCount"));
        // refresh 是"以来源为准换掉整张目录": 手搓但没声明来源的 3 条会被换掉, 只剩扫出来的那条
        assertEquals(1, report.num("$.skillCount"));
        assertEquals(1, report.num("$.rawCount"));
        assertEquals(0, report.num("$.dedupedCount"));
        assertEquals(0, report.num("$.conflictCount"));
        assertEquals(report.list("$.issues").size(), report.num("$.issueCount"));
        assertEquals(1, report.list("$.sources").size());
        assertEquals(1, report.map("$.formatDistribution").get("agent-skills"));
        assertTrue(report.map("$.severityDistribution").size() >= 0);

        assertEquals("mkt-local", report.str("$.sources[0].id"));
        assertEquals("agent-skills", report.str("$.sources[0].format"));
        assertEquals("ok", report.str("$.sources[0].status"));
        assertTrue(report.flag("$.sources[0].enabled"));
        assertEquals(10, report.num("$.sources[0].priority"));
        assertEquals(1, report.num("$.sources[0].skillCount"));
        assertEquals(Collections.singletonList("pdf-processing"), report.list("$.sources[0].skillIds"));
        assertEquals(report.list("$.sources[0].issues").size(), report.num("$.sources[0].issueCount"));
        assertTrue(report.lng("$.sources[0].lastRefreshAt") >= 1000000000000L,
                "lastRefreshAt 不是毫秒 epoch: " + report.raw("$.sources[0].lastRefreshAt"));

        assertEquals(1, new Resp(controller.sources()).list("$").size());
        assertEquals("mkt-local", new Resp(controller.sources()).str("$[0].id"));

        Resp stats = new Resp(controller.stats());
        assertEquals("z-skill", stats.str("$.platform"));
        assertEquals(1, stats.num("$.skillCount"));
        assertEquals(0, stats.num("$.installedCount"));
        assertEquals(1, stats.num("$.sourceCount"));
        assertTrue(stats.num("$.categoryCount") >= 1);
        // 聚合默认挂了扫描器, 所以真实抓进来的每条都带判定档 —— 这里必须落在扫过的档位上,
        // 出现 "unscanned" 说明扫描器没接进来, 控制台会把未审的内容显示成"一切正常".
        assertEquals(Collections.singletonMap("safe", 1), stats.map("$.riskDistribution"),
                "实际 riskDistribution=" + stats.map("$.riskDistribution"));
        assertTrue(stats.map("$.sourceDistribution").containsKey("mkt-local"));
        // 差量口径: 上一张目录是手搓的 3 条(伪 hash), 换上来的是扫出来的 1 条真 hash.
        // 所以这次 refresh 既没有新增, 也不能是"全清空" —— pdf-processing 是内容漂移, 另两条是被摘掉.
        assertTrue(stats.list("$.recentlyAdded").isEmpty(),
                "pdf-processing 本来就在表里, 不该算新增: " + stats.list("$.recentlyAdded"));
        assertEquals(Collections.singletonList("pdf-processing"), stats.list("$.upstreamChanged"),
                "实际 upstreamChanged=" + stats.list("$.upstreamChanged"));
        assertEquals(Arrays.asList("acme-registry/weather-lookup", "site/data-import"),
                stats.list("$.upstreamRemoved"), "实际 upstreamRemoved=" + stats.list("$.upstreamRemoved"));
        assertEquals(1, stats.num("$.lastAggregate.sourceCount"));

        Resp updates = new Resp(controller.updates());
        assertTrue(updates.list("$.added").isEmpty());
        // changed 给的是条目本身(要展示新旧 hash), added/removed 只给 id —— 消费方按 changed[i].id 寻址
        assertEquals("pdf-processing", updates.str("$.changed[0].id"));
        assertTrue(!"hash-pdf".equals(updates.str("$.changed[0].contentHash")),
                "changed 里的 hash 必须是从文件重算出来的, 不是表里那条假的");
        assertEquals(Arrays.asList("acme-registry/weather-lookup", "site/data-import"), updates.list("$.removed"));
    }

    @Test
    public void stats_withoutAggregate_omitsLastAggregate() {
        Resp stats = new Resp(controller.stats());
        assertEquals("z-skill", stats.str("$.platform"));
        assertEquals(3, stats.num("$.skillCount"));
        stats.absent("$.lastAggregate", "没聚合过时不该伪造一份报告");
        assertTrue(stats.list("$.danglingInstalls").isEmpty());
        assertTrue(new Resp(controller.sources()).list("$").isEmpty());
        assertTrue(new Resp(controller.updates()).list("$.added").isEmpty());
    }

    // ---------- 参数错误 / 不外泄 ----------

    @Test
    public void malformedParams_returnEnvelopeNotStackTrace() {
        Resp tooShort = errorBody(() -> controller.search("a", null, null, null, null, null, null,
                "relevance", 0, 20));
        assertEquals("bad_request", tooShort.str("$.error"));
        assertTrue(tooShort.str("$.message").contains("2")); // 得说清至少要几个字符
        assertLeakFree(tooShort);

        Resp cjkTooShort = errorBody(() -> controller.search("清", null, null, null, null, null, null,
                "relevance", 0, 20));
        assertEquals("bad_request", cjkTooShort.str("$.error")); // 单字中文同样要走 400, 不能悄悄全表

        Resp ghost = errorBody(() -> controller.detail("ghost"));
        assertEquals(404, ghost.num("$.code"));
        assertLeakFree(ghost);

        Resp illegal = new Resp(advice.onIllegal(new IllegalArgumentException("boom")).getBody());
        assertEquals("bad_request", illegal.str("$.error"));
        assertEquals("boom", illegal.str("$.message"));
    }

    // ---------- helpers ----------

    private static void assertLeakFree(Resp body) {
        String s = body.raw("$").toString();
        assertTrue(!s.contains(System.getProperty("user.home")), "不许回吐本机路径: " + s);
        assertTrue(!s.contains("at java.") && !s.contains(".java:") && !s.contains("StackOverflow"),
                "不许回吐栈信息: " + s);
        assertTrue(!s.contains("null"), "错误正文该有人话: " + s);
    }

    private interface Call {
        Object run() throws Exception;
    }

    private static SkillException threw(Call call) {
        try {
            call.run();
        } catch (SkillException e) {
            return e;
        } catch (Exception e) {
            throw new AssertionError("期望 SkillException, 实际抛了 " + e, e);
        }
        throw new AssertionError("期望抛 SkillException, 但调用正常返回");
    }

    /** 走一遍 SkillErrorAdvice: 断言状态码由 getCode() 决定, 返回体按 JSON 断言. */
    private Resp errorBody(Call call) {
        SkillException e = threw(call);
        ResponseEntity<Map<String, Object>> r = advice.onSkill(e);
        assertEquals(e.getCode(), r.getStatusCodeValue(), "HTTP 状态必须由 SkillException.getCode() 决定");
        return new Resp(r.getBody());
    }

    private JsonNode jsonBody(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 公共面不得带出宿主机目录结构.
     *
     * <p>控制面那道门({@code z.skill.expose-admin})默认关着, 但 {@code z.skill.enabled=true} 一个开关
     * 就把 {@code /skill/*} 全开起来了 —— 市场面里如果混着 {@code file:///Users/<name>/<项目>/...},
     * 等于把宿主的用户名、项目名、目录布局挂在没鉴权的端口上. 这条测试钉住"每个公共出口都过一遍
     * {@link PublicSurface}", 反向同时钉住别把该给的相对路径与远端 URL 一起抹掉.
     */
    @Test
    public void publicEndpointsNeverCarryHostPaths() throws Exception {
        // 装这一次要走控制器的出口, 不是 registry.install: 安装回执本身就是一条公共出口,
        // 而它是全集里唯一没过 PublicSurface 的那条(实测 installRef 原样回显 file:///...).
        SkillInstallDto receipt = controller.install(jsonBody(
                "{\"id\":\"pdf-processing\",\"installedBy\":\"tester\"}"));
        // 不带条件的全量搜索: 结果里必须有本机 origin 的那两条, "没有 file:" 才算数
        Resp everything = new Resp(controller.search(null, null, null, null, null, null, null,
                "relevance", 0, 20));
        assertTrue(everything.list("$.skills[*].id").contains("pdf-processing"),
                "全量搜索里连本机目录那条都没有, 后面白测: " + everything.raw("$.skills[*].id"));
        Resp remote = new Resp(controller.search(null, "weather", null, null, null, null, null,
                "relevance", 0, 20));
        assertEquals("acme-registry/weather-lookup", remote.str("$.skills[0].id"), "夹具没备好, 后面白测");
        // 正文预览的两个分支都要在册: 成功分支的 path 是真从磁盘上拼出来的, 曾经就是绝对路径,
        // 而 400 分支反倒早就有人查过本机路径 —— 出口清单少列一条, 少列的那条就永远不红.
        // 两条形(路径变量 / 查询串)都要各走一次: 账本按映射注解推, 少落一笔就红, 但"走过"才是真证据.
        assertNoHostPath(new String[]{"search", "search", "list", "installed", "categories", "detail",
                        "content", "content-by-query", "content-resource", "install"},
                new Object[]{everything.raw("$"), remote.raw("$"), controller.list(null, null, null),
                        controller.installed(), controller.categories(), controller.detail("pdf-processing"),
                        controller.content("pdf-processing", null),
                        controller.contentById("pdf-processing", null),
                        controller.content("pdf-processing", "references/notes.md"), receipt},
                marketplaceRoot());

        // 反向: 抹路径不能顺手抹掉公共面该给的东西
        assertEquals("https://acme.example/.well-known/agent-skills/weather-lookup/SKILL.md",
                remote.str("$.skills[0].origin"), "远端来源的安装地址被误抹了");
        Resp detail = new Resp(controller.detail("pdf-processing"));
        assertEquals("pdf-processing/SKILL.md", detail.str("$.skill.skillFilePath"), "相对主文件位要照给");
        assertEquals("references/notes.md", detail.str("$.skill.resources[0].path"), "相对资源路径要照给");
        assertTrue(detail.list("$.skill.aliases").contains("anthropic/pdf"), "别名列表要照给");
        assertTrue(detail.flag("$.contentAvailable"), "正文读取靠真路径, 抹公共面不该把内容读没, 整个响应="
                + detail.raw("$"));
        Resp installed = new Resp(controller.installed());
        assertEquals(1, installed.list("$.records").size());
        assertNull(installed.raw("$.records[0].installRef"), "安装记录的 file: 引用要抹掉");
        assertEquals("hash-pdf", installed.str("$.records[0].contentHash"), "安装记录除了安装地址都该原样留着");
        // 回执自己也要留得住别的字段: 抹路径不能把版本/hash 一起抹掉, 客户端拿它判要不要升级
        assertNull(receipt.getInstallRef(), "回执的 installRef 就是本机 origin, 要抹");
        assertEquals("hash-pdf", receipt.getContentHash(), "回执的 contentHash 要照给");
        assertEquals("pdf-processing", receipt.getSkillId(), "回执要能对上条目");
        // 卸载回执同样是一条公共出口, 顺带证明装/卸闭环
        assertNoHostPath(new String[]{"uninstall"},
                new Object[]{controller.uninstall("pdf-processing")}, marketplaceRoot());
    }

    /**
     * 读不出正文时给的理由也不能带本机路径.
     *
     * <p>{@code SkillContentReader.readAt} 的 {@code IOException} 分支一度直接转发 {@code e.getMessage()},
     * 而 JDK 那几条消息的正文就是绝对路径本身. 只有这一条分支会走到这里: 文件不存在时
     * {@code localEntry} 先返回空(给的是"远端来源"那句), 超限走 size 分支, 两条都不碰异常消息.
     */
    @Test
    public void unreadableContentReasonCarriesNoHostPath() throws Exception {
        java.nio.file.FileSystem fs = java.nio.file.FileSystems.getDefault();
        org.junit.jupiter.api.Assumptions.assumeTrue(fs.supportedFileAttributeViews().contains("posix"),
                "这台机器的文件系统不支持 posix 权限位, 这一条在本机上未被验证");
        java.util.Set<java.nio.file.attribute.PosixFilePermission> none =
                java.util.EnumSet.noneOf(java.nio.file.attribute.PosixFilePermission.class);
        java.util.Set<java.nio.file.attribute.PosixFilePermission> ownerRead =
                java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ);
        Path dir = java.nio.file.Files.createTempDirectory("z-skill-unreadable");
        Path entry = dir.resolve("secret-note").resolve("SKILL.md");
        java.nio.file.Files.createDirectories(entry.getParent());
        java.nio.file.Files.write(entry, "# secret\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            java.nio.file.Files.setPosixFilePermissions(entry, none);
            // 前置: 这条夹具必须真的"读不出来", 否则下面那条"没有路径"的断言是空跑
            assertTrue(java.nio.file.Files.isRegularFile(entry), "文件本身得在, 否则走不到读失败那一步");
            SkillDto secret = SkillDto.builder().id("secret-note").slug("secret-note").name("secret-note")
                    .description("本机存在但读不出的条目").source("local-fs").sourceType("fs")
                    .origin(entry.toUri().toString()).skillFilePath("secret-note/SKILL.md")
                    .format(SkillFormat.AGENT_SKILLS).contentHash("hash-secret").category("documents")
                    .build();
            registry.register(secret);
            assertTrue(reader.localEntry(secret).isPresent(), "夹具没能被认成本机文件, 后面白测");
            String reason = new Resp(controller.content("secret-note", null)).str("$.reason");
            assertTrue(!reason.contains(dir.toString()),
                    "读失败的理由把本机目录挂到了公共面: " + reason);
            assertNoHostPath(new String[]{"content-unreadable", "detail-unreadable"},
                    new Object[]{controller.content("secret-note", null), controller.detail("secret-note")},
                    dir.toString());
        } catch (java.nio.file.FileSystemException e) {
            fail("造不出不可读文件, 这条断言没跑: " + e.getMessage());
        } finally {
            try {
                java.nio.file.Files.setPosixFilePermissions(entry, ownerRead);
                java.nio.file.Files.deleteIfExists(entry);
                java.nio.file.Files.deleteIfExists(dir);
            } catch (java.io.IOException ignored) {
                // 临时目录留给系统回收, 不该把断言结果盖掉
            }
        }
    }

    /**
     * 上游漂移这条出口单独测: {@code /skill/updates} 只在 {@code contentHash} 变了的时候才吐条目,
     * 手搓的三条夹具永远给不出漂移 —— 不真造一次漂移, 这个出口的断言就是空跑.
     */
    @Test
    public void upstreamDriftOnThePublicSurfaceCarriesNoHostPaths() throws Exception {
        Map<String, SkillDto> next = new LinkedHashMap<String, SkillDto>();
        SkillDto drifted = pdf().toBuilder().contentHash("hash-pdf-v2").build();
        next.put(drifted.getId(), drifted);
        registry.replaceAll(next, Collections.<SkillSourceDto>emptyList(), null);

        Resp updates = new Resp(controller.updates());
        assertEquals("pdf-processing", updates.str("$.changed[0].id"), "没造出漂移, /skill/updates 是空跑的");
        assertEquals("hash-pdf-v2", updates.str("$.changed[0].contentHash"), "漂移条目要带着新指纹回来");
        assertNoHostPath(new String[]{"updates"}, new Object[]{updates.raw("$")}, marketplaceRoot());
        assertNull(updates.raw("$.changed[0].origin"), "漂移条目的本机 origin 要抹掉");
    }

    /** 报告类出口另开一条: {@code refresh()} 会整体换掉注册中心, 和上面手搓的三条夹具互斥. */
    @Test
    public void aggregateReportsOnThePublicSurfaceCarryNoHostPaths() throws Exception {
        controller.refresh();
        assertNoHostPath(new String[]{"sources", "stats", "refresh"},
                new Object[]{controller.sources(), controller.stats(), controller.refresh()},
                marketplaceRoot());

        Resp sources = new Resp(controller.sources());
        assertEquals(1, sources.list("$").size(), "这个夹具只有一个来源");
        assertNull(sources.raw("$[0].origin"), "来源视图还在报本机目录");
        assertEquals("mkt-local", sources.str("$[0].id"), "来源身份要留着, 客户端还要按它筛选");
        assertEquals("agent-skills", sources.str("$[0].observedFormats[0]"), "抹路径不该把观察到的平台形状一起抹掉");
    }

    /** 每个公共出口的返回值都要能序列化, 且不带宿主机路径. */
    private void assertNoHostPath(String[] names, Object[] bodies, String root) throws Exception {
        assertEquals(names.length, bodies.length, "出口名字与返回值对不上, 有出口漏了记账");
        for (int i = 0; i < names.length; i++) {
            String json = mapper.writeValueAsString(bodies[i]);
            assertFalse(json.contains("file:"), names[i] + " 把 file: 地址发到了公共面: " + json);
            assertFalse(json.contains(root), names[i] + " 把本机绝对路径发到了公共面: " + root);
        }
    }

    /**
     * 公共面出口账本: 每条映射都必须有人检过"不带本机路径", 且账上不许留死条目.
     *
     * <p>出口清单从注解推导而不是抄: {@code /skill/{id}/content} 那条漏网就是"加了出口没加检查" ——
     * 手写的清单只覆盖写它那天存在的出口, 少列一条永远不会红. 反向也要比: 账上挂着已经删掉的出口,
     * 等于它还在声称"这条有人检". 账上写的测试名还得真存在, 免得有人拿一句假归属把这条门糊过去.
     */
    @Test
    public void everyPublicMappingIsOnTheHostPathLedger() throws Exception {
        List<String> endpoints = new ArrayList<String>(publicMappingPaths());
        Map<String, String> ledger = hostPathLedger();
        assertFalse(endpoints.isEmpty(), "反射没拿到任何映射, 这条断言什么也测不到");
        List<String> unaccounted = new ArrayList<String>();
        for (String path : endpoints) if (!ledger.containsKey(path)) unaccounted.add(path);
        assertTrue(unaccounted.isEmpty(),
                "这些公共出口没人检本机路径: " + unaccounted + "; 账上现有 " + ledger.keySet());
        List<String> stale = new ArrayList<String>();
        for (String path : ledger.keySet()) if (!endpoints.contains(path)) stale.add(path);
        assertTrue(stale.isEmpty(), "账上还挂着已经不存在的出口: " + stale);

        List<String> ghost = new ArrayList<String>();
        for (String where : new java.util.LinkedHashSet<String>(ledger.values())) {
            int at = where.indexOf('#');
            try {
                Class<?> owner = at < 0 ? getClass() : Class.forName(where.substring(0, at));
                owner.getDeclaredMethod(at < 0 ? where : where.substring(at + 1));
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                ghost.add(where + " -> " + e.getClass().getSimpleName());
            }
        }
        assertTrue(ghost.isEmpty(), "账本指向的测试并不存在(归属是编的): " + ghost);
    }

    /** 谁在检这条出口. 值不带类名前缀即指本类. */
    private static Map<String, String> hostPathLedger() {
        Map<String, String> ledger = new LinkedHashMap<String, String>();
        ledger.put("/skill/search", "publicEndpointsNeverCarryHostPaths");
        ledger.put("/skill/list", "publicEndpointsNeverCarryHostPaths");
        ledger.put("/skill/installed", "publicEndpointsNeverCarryHostPaths");
        ledger.put("/skill/categories", "publicEndpointsNeverCarryHostPaths");
        ledger.put("/skill/detail", "publicEndpointsNeverCarryHostPaths");
        // /skill/detail?id= 与 /skill/uninstall?id= 只是寻址别名, 方法体直接委派给下面两条
        ledger.put("/skill/{id}", "publicEndpointsNeverCarryHostPaths");
        ledger.put("/skill/{id}/content", "publicEndpointsNeverCarryHostPaths");
        // 内容预览的查询串形: 带斜杠的 id(source/slug)只有这一条走得通, 路径变量形会被 Tomcat 拒 400
        ledger.put("/skill/content", "publicEndpointsNeverCarryHostPaths");
        ledger.put("/skill/install", "publicEndpointsNeverCarryHostPaths");
        ledger.put("/skill/uninstall/{id}", "publicEndpointsNeverCarryHostPaths");
        ledger.put("/skill/uninstall", "publicEndpointsNeverCarryHostPaths");
        ledger.put("/skill/sources", "aggregateReportsOnThePublicSurfaceCarryNoHostPaths");
        ledger.put("/skill/stats", "aggregateReportsOnThePublicSurfaceCarryNoHostPaths");
        ledger.put("/skill/refresh", "aggregateReportsOnThePublicSurfaceCarryNoHostPaths");
        ledger.put("/skill/updates", "upstreamDriftOnThePublicSurfaceCarriesNoHostPaths");
        String compat = "com.zifang.z.skill.core.controller.SkillCompatControllerTest#"
                + "everyCompatExitCarriesNoHostPathForALocalOrigin";
        for (String path : compatMappingPaths()) ledger.put(path, compat);
        return ledger;
    }

    /** {@code /skill/*} 与兼容面的全部映射(不含 {@code /skill/admin/*}, 那条有自己的门). */
    private static List<String> publicMappingPaths() {
        List<String> out = new ArrayList<String>();
        collectMapped(out, SkillController.class);
        collectMapped(out, SkillCompatController.class);
        return out;
    }

    private static List<String> compatMappingPaths() {
        List<String> out = new ArrayList<String>();
        collectMapped(out, SkillCompatController.class);
        return out;
    }

    private static void collectMapped(List<String> out, Class<?> controller) {
        org.springframework.web.bind.annotation.RequestMapping root =
                controller.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class);
        String base = root == null || root.value().length == 0 ? "" : root.value()[0];
        for (java.lang.reflect.Method method : controller.getDeclaredMethods()) {
            addMapped(out, base, method, org.springframework.web.bind.annotation.GetMapping.class);
            addMapped(out, base, method, org.springframework.web.bind.annotation.PostMapping.class);
            addMapped(out, base, method, org.springframework.web.bind.annotation.DeleteMapping.class);
        }
    }

    private static void addMapped(List<String> out, String base, java.lang.reflect.Method method,
                                  Class<? extends java.lang.annotation.Annotation> mapping) {
        java.lang.annotation.Annotation present = method.getAnnotation(mapping);
        if (present == null) return;
        String[] paths;
        try {
            paths = (String[]) mapping.getMethod("value").invoke(present);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("读不到 " + mapping.getSimpleName() + " 的 value(): " + e, e);
        }
        if (paths.length == 0) {
            out.add(base);
            return;
        }
        for (String path : paths) if (!out.contains(base + path)) out.add(base + path);
    }

    // ---------- fixtures ----------

    private static SkillDto pdf() {
        return SkillDto.builder()
                .id("pdf-processing").slug("pdf-processing").name("pdf-processing")
                .description("Extract PDF text and fill forms").version("1.0.0").license("MIT")
                .category("documents").tags(Arrays.asList("pdf", "documents"))
                .author("anthropic").source("anthropics").sourceType("github")
                .origin(localSkillMd().toUri().toString())
                .skillFilePath("pdf-processing/SKILL.md")
                .format(SkillFormat.AGENT_SKILLS)
                .resources(Collections.singletonList(
                        new SkillResourceDto("references/notes.md", "reference", 32L)))
                .contentHash("hash-pdf").installCount(12000).riskLevel(SkillDto.RISK_LOW)
                .discoveredAt(1000L).updatedAt(500L)
                .metadata(Collections.singletonMap("compatibility", "claude"))
                .aliases(Arrays.asList("anthropic/pdf"))
                .build();
    }

    private static SkillDto dataImport() {
        return SkillDto.builder()
                .id("site/data-import").slug("data-import").name("Data Import")
                .description("Import CSV rows into the warehouse").version("0.2.0")
                .category("data").tags(Arrays.asList("etl"))
                .author("site-team").source("site").sourceType("local")
                .origin("file:///nonexistent/site/data-import/SKILL.md")
                .skillFilePath("data-import/SKILL.md")
                .format(SkillFormat.AGENT_SKILLS)
                .contentHash("hash-import").installCount(10).riskLevel(SkillDto.RISK_HIGH)
                .discoveredAt(1100L).updatedAt(700L)
                .build();
    }

    private static SkillDto weather() {
        return SkillDto.builder()
                .id("acme-registry/weather-lookup").slug("weather-lookup").name("weather-lookup")
                .description("Look up current weather and forecasts").version("1.0.0")
                .category("weather").tags(Arrays.asList("http"))
                .author("acme").owner("acme").source("acme-registry").sourceType("well-known")
                .origin("https://acme.example/.well-known/agent-skills/weather-lookup/SKILL.md")
                .skillFilePath(".well-known/agent-skills/index.json")
                .format(SkillFormat.REGISTRY_INDEX)
                .resources(Collections.singletonList(new SkillResourceDto("SKILL.md", "other", 0L)))
                .contentHash("hash-weather").installCount(4210).riskLevel(SkillDto.RISK_UNSCANNED)
                .discoveredAt(1200L).updatedAt(600L)
                .build();
    }

    private static String marketplaceRoot() {
        return localSkillMd().getParent().getParent().toString();
    }

    private static Path localSkillMd() {
        try {
            return Paths.get(SkillControllerTest.class.getResource(
                    "/fixtures/A4/marketplace/pdf-processing/SKILL.md").toURI());
        } catch (Exception e) {
            throw new IllegalStateException("A4 marketplace fixture missing", e);
        }
    }

    /** 控制器返回值 -> JSON 文档; 断言按 JSONPath 打在键名上. */
    private final class Resp {
        private final DocumentContext dc;

        Resp(Object controllerReturn) {
            try {
                dc = JsonPath.parse(mapper.writeValueAsString(controllerReturn));
            } catch (Exception e) {
                throw new IllegalStateException("控制器返回值无法序列化成 JSON", e);
            }
        }

        Object raw(String path) {
            return dc.read(path);
        }

        String str(String path) {
            Object v = dc.read(path);
            assertTrue(v instanceof String, path + " 必须是字符串, 实际是 " + v);
            return (String) v;
        }

        int num(String path) {
            Object v = dc.read(path);
            assertTrue(v instanceof Number, path + " 必须是数字, 实际是 " + v);
            long l = ((Number) v).longValue();
            // epoch 毫秒用 intValue() 读会静默溢出成负数 — 曾经把好的时间戳判成坏, 所以这里直接拦下来
            assertTrue(l == ((Number) v).intValue(), path + " 超出 int 范围(" + l + "), 该用 lng()");
            return ((Number) v).intValue();
        }

        long lng(String path) {
            Object v = dc.read(path);
            assertTrue(v instanceof Number, path + " 必须是数字, 实际是 " + v);
            return ((Number) v).longValue();
        }

        boolean flag(String path) {
            Object v = dc.read(path);
            assertTrue(v instanceof Boolean, path + " 必须是布尔, 实际是 " + v);
            return (Boolean) v;
        }

        List<Object> list(String path) {
            Object v = dc.read(path);
            assertTrue(v instanceof List, path + " 必须是数组, 实际是 " + v);
            return (List<Object>) v;
        }

        Map<String, Object> map(String path) {
            Object v = dc.read(path);
            assertTrue(v instanceof Map, path + " 必须是对象, 实际是 " + v);
            return (Map<String, Object>) v;
        }

        List<String> ids(String path) {
            return (List<String>) dc.read(path);
        }

        void absent(String path, String why) {
            Object v;
            try {
                v = dc.read(path);
            } catch (PathNotFoundException expected) {
                return;
            }
            fail(why + " —— 却出现了 " + path + "=" + v);
        }
    }
}
