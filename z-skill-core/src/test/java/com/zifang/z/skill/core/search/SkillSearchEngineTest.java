package com.zifang.z.skill.core.search;

import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.dto.SkillSearchResultDto;
import com.zifang.z.skill.api.exception.SkillException;
import com.zifang.z.skill.api.spec.SkillFormat;
import com.zifang.z.skill.api.spec.SkillSpec;
import com.zifang.z.skill.core.registry.SkillRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索与分面测试 — 排序口径(命中标题优于命中描述)、中文可搜、筛选按 AND 组合、分页与分面计数.
 *
 * <p>{@code SkillRegistry} 内部是 ConcurrentHashMap, 因此"同分按 id 稳定排序"不是装饰性断言:
 * 少了兜底比较, 同一份目录两次检索就会给出不同顺序.
 */
public class SkillSearchEngineTest {

    // ---------- fixtures ----------

    private static SkillDto dto(String id, String name, String description) {
        return SkillDto.builder()
                .id(id).slug(SkillSpec.slugify(name)).name(name).description(description)
                .version("1.0.0").source("alpha").sourceType("local")
                .origin("file:///skills/" + id + "/SKILL.md")
                .skillFilePath(id + "/SKILL.md")
                .format(SkillFormat.AGENT_SKILLS)
                .category("general")
                .contentHash("hash-" + id)
                .riskLevel(SkillDto.RISK_SAFE)
                .build();
    }

    private static SkillDto skill(String id, String name, String description, String category, String source,
                                  SkillFormat format, String... tags) {
        return dto(id, name, description).toBuilder()
                .category(category).source(source).format(format).tags(Arrays.asList(tags))
                .build();
    }

    private static SkillRegistry registryOf(SkillDto... skills) {
        SkillRegistry registry = new SkillRegistry();
        for (SkillDto s : skills) registry.register(s);
        return registry;
    }

    private static List<String> ids(SkillSearchResultDto result) {
        List<String> out = new ArrayList<String>();
        for (SkillDto d : result.getSkills()) out.add(d.getId());
        return out;
    }

    private static SkillSearchResultDto search(SkillRegistry registry, SkillSearchEngine.Query q) {
        return new SkillSearchEngine(registry).search(q);
    }

    /** installedOnly / riskLevel 没有链式 setter, 按 Query 的 javadoc 直接给公开字段赋值. */
    private static SkillSearchEngine.Query installedOnly(Boolean v) {
        SkillSearchEngine.Query q = new SkillSearchEngine.Query();
        q.installedOnly = v;
        return q;
    }

    // ---------- 排序 ----------

    @Test
    public void relevance_nameHit_outranksDescriptionHit() {
        SkillRegistry registry = registryOf(
                skill("pdf-processing", "pdf-processing", "Extract text and tables from PDF files.",
                        "documents", "alpha", SkillFormat.AGENT_SKILLS, "pdf"),
                skill("gamma", "PDF Toolkit", "Everything about documents.", "tools", "beta",
                        SkillFormat.FLAT_MARKDOWN),
                skill("doc-convert", "Doc Convert", "Handles pdf exports.", "documents", "gamma",
                        SkillFormat.CURSOR_RULES));

        SkillSearchResultDto result = search(registry, new SkillSearchEngine.Query().q("pdf"));

        assertEquals(Arrays.asList("pdf-processing", "gamma", "doc-convert"), ids(result),
                "命中 slug > 命中标题 > 命中描述, 这是文档里承诺的排序口径");
        assertEquals(3, result.getTotal());
        assertEquals("pdf", result.getQuery());
        assertEquals("fuzzy", result.getSearchType(), "单个词是 fuzzy 口径");
        assertEquals(0, result.getPage());
    }

    @Test
    public void relevance_slugPrefix_outranksSlugSubstring() {
        SkillRegistry registry = registryOf(
                skill("pdf-tools", "pdf-tools", "Merge and split pages.", "tools", "alpha",
                        SkillFormat.AGENT_SKILLS),
                skill("my-pdf-tool", "my-pdf-tool", "Merge and split pages.", "tools", "alpha",
                        SkillFormat.AGENT_SKILLS));

        assertEquals(Arrays.asList("pdf-tools", "my-pdf-tool"),
                ids(search(registry, new SkillSearchEngine.Query().q("pdf"))), "前缀命中要优于中间命中");
    }

    @Test
    public void partialQuery_matchesInsideSlug_andCaseIsIgnored() {
        SkillRegistry registry = registryOf(
                skill("code-review", "code-review", "Read a diff and report defects.", "quality", "alpha",
                        SkillFormat.AGENT_SKILLS),
                skill("pdf-processing", "pdf-processing", "Extract text.", "documents", "alpha",
                        SkillFormat.AGENT_SKILLS));

        assertEquals(Collections.singletonList("code-review"),
                ids(search(registry, new SkillSearchEngine.Query().q("rev"))), "部分词也要能命中");
        List<String> lower = ids(search(registry, new SkillSearchEngine.Query().q("pdf")));
        List<String> upper = ids(search(registry, new SkillSearchEngine.Query().q("  PDF  ")));
        assertEquals(lower, upper, "大小写不敏感");
        assertEquals(Collections.singletonList("pdf-processing"), upper);
    }

    @Test
    public void multiWordQuery_isReportedAsSemantic() {
        SkillRegistry registry = registryOf(
                skill("pdf-processing", "pdf-processing", "Extract text from pdf.", "documents", "alpha",
                        SkillFormat.AGENT_SKILLS));
        SkillSearchResultDto result = search(registry, new SkillSearchEngine.Query().q("pdf extract"));
        assertEquals("semantic", result.getSearchType());
        assertEquals(1, result.getTotal());
    }

    // ---------- 中文 ----------

    @Test
    public void cjkQueries_areFindableByTwoCharAndByLongerSubstring() {
        SkillRegistry registry = registryOf(
                skill("video-edit", "视频剪辑", "自动生成字幕并剪辑视频片段。", "media", "alpha",
                        SkillFormat.AGENT_SKILLS, "视频"),
                skill("subtitle-gen", "字幕生成", "为长视频生成中文字幕。", "media", "alpha",
                        SkillFormat.AGENT_SKILLS));
        SkillSearchEngine engine = new SkillSearchEngine(registry);

        SkillSearchResultDto twoChar = engine.search(new SkillSearchEngine.Query().q("剪辑"));
        assertEquals(Collections.singletonList("video-edit"), ids(twoChar), "二元组切分下 2 字查询要能命中");

        SkillSearchResultDto substring = engine.search(new SkillSearchEngine.Query().q("视频剪辑"));
        assertEquals(Arrays.asList("video-edit", "subtitle-gen"), ids(substring),
                "长串按二元组展开后, 命中的都要出现, 且命中多的排前");

        SkillSearchResultDto chinese = engine.search(new SkillSearchEngine.Query().q("字幕"));
        assertEquals(2, chinese.getTotal(), "两个中文描述的 skill 都提到字幕");
        assertEquals("fuzzy", chinese.getSearchType());
    }

    @Test
    public void mixedCjkAndLatinQuery_matchesBothSides() {
        SkillRegistry registry = registryOf(
                skill("video-edit", "video-edit", "把长视频剪成短片。", "media", "alpha", SkillFormat.AGENT_SKILLS),
                skill("pdf-processing", "pdf-processing", "Extract text from pdf.", "documents", "alpha",
                        SkillFormat.AGENT_SKILLS));
        SkillSearchEngine engine = new SkillSearchEngine(registry);

        assertEquals(Collections.singletonList("video-edit"),
                ids(engine.search(new SkillSearchEngine.Query().q("视频"))));
        assertEquals(Collections.singletonList("pdf-processing"),
                ids(engine.search(new SkillSearchEngine.Query().q("pdf"))));
    }

    // ---------- 参数边界 ----------

    @Test
    public void queryShorterThanMinLength_isRejected() {
        final SkillRegistry registry = registryOf(dto("pdf-processing", "pdf-processing", "Extract text."));
        SkillException e = assertThrows(SkillException.class,
                () -> new SkillSearchEngine(registry).search(new SkillSearchEngine.Query().q("p")));
        assertEquals(400, e.getCode());
        assertEquals("bad_request", e.getErrorId());
        assertTrue(e.getMessage().contains(String.valueOf(SkillSpec.MIN_QUERY_LENGTH)),
                "报错要说清最短长度: " + e.getMessage());
        // 单字中文同样被拒, 说明守的是字符数而不是词形
        assertThrows(SkillException.class,
                () -> new SkillSearchEngine(registry).search(new SkillSearchEngine.Query().q("视")));
    }

    @Test
    public void emptyAndBlankQuery_browseEverything() {
        SkillRegistry registry = registryOf(
                dto("a-first", "a-first", "one"), dto("b-second", "b-second", "two"),
                dto("c-third", "c-third", "three"));
        SkillSearchEngine engine = new SkillSearchEngine(registry);

        SkillSearchResultDto empty = engine.search(new SkillSearchEngine.Query().q(""));
        assertEquals(3, empty.getTotal());
        assertEquals("browse", empty.getSearchType());
        assertEquals("", empty.getQuery());
        assertEquals(Arrays.asList("a-first", "b-second", "c-third"), ids(empty));

        assertEquals(3, engine.search(new SkillSearchEngine.Query().q("   ")).getTotal(), "纯空白等同浏览");
        assertEquals(3, engine.search(new SkillSearchEngine.Query()).getTotal(), "query 为 null 等同浏览");
        assertEquals(3, engine.search(null).getTotal(), "整个 Query 为 null 也不能炸");
    }

    // ---------- 筛选 ----------

    private static SkillRegistry filterPool() {
        return registryOf(
                skill("s1", "s1", "alpha docs pdf", "documents", "alpha", SkillFormat.AGENT_SKILLS, "pdf"),
                skill("s2", "s2", "alpha docs convert", "documents", "beta", SkillFormat.FLAT_MARKDOWN, "convert"),
                skill("s3", "s3", "beta media pdf", "media", "alpha", SkillFormat.AGENT_SKILLS, "pdf"),
                skill("s4", "s4", "beta media convert", "media", "gamma", SkillFormat.CURSOR_RULES, "convert"));
    }

    @Test
    public void filters_composeWithAnd_andDoNotLeak() {
        SkillRegistry registry = filterPool();
        SkillSearchEngine engine = new SkillSearchEngine(registry);

        assertEquals(Arrays.asList("s1", "s2"), ids(engine.search(new SkillSearchEngine.Query().category("documents"))));
        assertEquals(Arrays.asList("s1", "s3"), ids(engine.search(new SkillSearchEngine.Query().tag("pdf"))));
        assertEquals(Arrays.asList("s1", "s3"), ids(engine.search(new SkillSearchEngine.Query().source("alpha"))));
        assertEquals(Collections.singletonList("s2"),
                ids(engine.search(new SkillSearchEngine.Query().format("flat-markdown"))));
        assertEquals(Arrays.asList("s1", "s3"),
                ids(engine.search(new SkillSearchEngine.Query().format("agent-skills"))));
        assertEquals(Collections.singletonList("s1"),
                ids(engine.search(new SkillSearchEngine.Query().category("documents").tag("pdf"))),
                "多条件必须 AND, 不能漏进 s3");
        assertEquals(Collections.singletonList("s2"),
                ids(engine.search(new SkillSearchEngine.Query().category("documents").source("beta"))));
        assertEquals(Collections.singletonList("s4"),
                ids(engine.search(new SkillSearchEngine.Query().category("media").tag("convert"))));
        assertTrue(engine.search(new SkillSearchEngine.Query().category("media").tag("pdf").source("beta"))
                .getSkills().isEmpty(), "三个条件求交为空就是空, 不能退化成最宽的那个");
        assertEquals(0, engine.search(new SkillSearchEngine.Query().category("nope")).getTotal());
    }

    @Test
    public void filters_combineWithQueryText() {
        SkillRegistry registry = filterPool();
        SkillSearchResultDto result = new SkillSearchEngine(registry)
                .search(new SkillSearchEngine.Query().q("pdf").category("media"));
        assertEquals(Collections.singletonList("s3"), ids(result));
        assertEquals(1, result.getTotal());
    }

    @Test
    public void installedOnly_onlyReturnsInstalled_andFlagIsEchoed() {
        SkillRegistry registry = filterPool();
        registry.install("s1", "tester", "marketplace");
        registry.install("s3", "tester", "marketplace");
        SkillSearchEngine engine = new SkillSearchEngine(registry);

        SkillSearchResultDto installed = engine.search(installedOnly(Boolean.TRUE));
        assertEquals(Arrays.asList("s1", "s3"), ids(installed));
        assertTrue(installed.getSkills().get(0).isInstalled());
        assertTrue(installed.getSkills().get(1).isInstalled());

        SkillSearchResultDto browse = engine.search(new SkillSearchEngine.Query());
        assertEquals(Arrays.asList("s1", "s2", "s3", "s4"), ids(browse));
        assertTrue(browse.getSkills().get(0).isInstalled());
        assertFalse(browse.getSkills().get(1).isInstalled(), "未装的条目不能带着 installed=true 出去");

        registry.uninstall("s3");
        assertEquals(Collections.singletonList("s1"),
                ids(engine.search(installedOnly(Boolean.TRUE))));
        assertEquals(4, engine.search(installedOnly(Boolean.FALSE)).getTotal(),
                "installedOnly=false 等同不加这个筛选");
    }

    // ---------- 分面 ----------

    @Test
    public void facets_countExactlyWhatTheUnfilteredSetHolds() {
        SkillRegistry registry = filterPool();
        SkillSearchEngine engine = new SkillSearchEngine(registry);
        SkillSearchResultDto browse = engine.search(new SkillSearchEngine.Query());

        Map<String, Integer> categories = browse.getFacets();
        assertEquals(2, categories.size());
        assertEquals(Integer.valueOf(2), categories.get("documents"));
        assertEquals(Integer.valueOf(2), categories.get("media"));
        int sum = 0;
        for (Integer n : categories.values()) sum += n;
        assertEquals(browse.getTotal(), sum, "category 分面的计数之和必须等于总数");
        assertEquals(engine.categoryCounts(), categories, "getFacets 是历史口径的 category 分面");
        assertEquals(categories, browse.getAllFacets().get("category"));
        assertEquals(Arrays.asList("documents", "media"), new ArrayList<String>(categories.keySet()),
                "同计数的分面项按名字排序, 保证 UI 上顺序稳定");

        Map<String, Integer> tags = browse.getAllFacets().get("tag");
        assertEquals(Integer.valueOf(2), tags.get("pdf"));
        assertEquals(Integer.valueOf(2), tags.get("convert"));
        Map<String, Integer> sources = browse.getAllFacets().get("source");
        assertEquals(Integer.valueOf(2), sources.get("alpha"));
        assertEquals(Integer.valueOf(1), sources.get("beta"));
        assertEquals(Integer.valueOf(1), sources.get("gamma"));
        assertEquals(Arrays.asList("alpha", "beta", "gamma"), new ArrayList<String>(sources.keySet()));
        assertEquals(Arrays.asList("convert", "pdf"), new ArrayList<String>(tags.keySet()),
                "同计数的分面项按名字排序, 保证 UI 上顺序稳定");
    }

    @Test
    public void facets_followTheHitsAndCountMissingCategoryIsNotSilent() {
        SkillRegistry registry = registryOf(
                skill("s1", "s1", "pdf tools", "documents", "alpha", SkillFormat.AGENT_SKILLS, "PDF"),
                skill("s2", "s2", "pdf pages", "media", "alpha", SkillFormat.AGENT_SKILLS),
                dto("s3", "s3", "no match at all"));
        SkillSearchEngine engine = new SkillSearchEngine(registry);

        SkillSearchResultDto hits = engine.search(new SkillSearchEngine.Query().q("pdf"));
        assertEquals(Arrays.asList("s1", "s2"), ids(hits));
        assertEquals(Integer.valueOf(1), hits.getFacets().get("documents"));
        assertEquals(Integer.valueOf(1), hits.getFacets().get("media"));
        assertFalse(hits.getFacets().containsKey("general"), "没命中的分类不该出现在本次检索的分面里");
        assertEquals(Integer.valueOf(1), engine.categoryCounts().get("general"),
                "全量分类目录仍要看到未命中的那条");
        assertTrue(engine.tagCounts().containsKey("pdf"), "tag 分面按小写归一");
        assertEquals(Arrays.asList("documents", "general", "media"), engine.listCategories(),
                "同计数的分类按名字给序");
        assertEquals(Collections.singletonList("pdf"), engine.listTags());
    }

    // ---------- 分页 ----------

    private static SkillRegistry pagePool() {
        SkillDto[] all = new SkillDto[7];
        for (int i = 0; i < 7; i++) {
            all[i] = skill("page-" + i, "page-" + i, "shared keyword needle here", "misc", "alpha",
                    SkillFormat.AGENT_SKILLS);
        }
        return registryOf(all);
    }

    @Test
    public void pagination_pagesAreDisjointAndCoverTheWholeResult() {
        SkillRegistry registry = pagePool();
        SkillSearchEngine engine = new SkillSearchEngine(registry);

        SkillSearchResultDto p0 = engine.search(new SkillSearchEngine.Query().q("needle").page(0).perPage(3));
        SkillSearchResultDto p1 = engine.search(new SkillSearchEngine.Query().q("needle").page(1).perPage(3));
        SkillSearchResultDto p2 = engine.search(new SkillSearchEngine.Query().q("needle").page(2).perPage(3));
        SkillSearchResultDto p3 = engine.search(new SkillSearchEngine.Query().q("needle").page(3).perPage(3));

        assertEquals(Arrays.asList("page-0", "page-1", "page-2"), ids(p0));
        assertEquals(Arrays.asList("page-3", "page-4", "page-5"), ids(p1));
        assertEquals(Collections.singletonList("page-6"), ids(p2), "末页不满一页");
        assertTrue(p3.getSkills().isEmpty(), "offset 越过 total 就是空页");

        for (SkillSearchResultDto r : Arrays.asList(p0, p1, p2, p3)) {
            assertEquals(7, r.getTotal(), "分页不改变总数");
        }
        assertTrue(p0.isHasMore());
        assertTrue(p1.isHasMore());
        assertFalse(p2.isHasMore());
        assertFalse(p3.isHasMore());
        assertEquals(3, p0.getCount());
        assertEquals(1, p2.getCount());
        assertEquals(3, p2.getPerPage());
        assertEquals(2, p2.getPage());
        assertEquals(20, engine.search(new SkillSearchEngine.Query().q("needle").perPage(0)).getPerPage(),
                "perPage<=0 回落默认页长 20");
        assertEquals(200, engine.search(new SkillSearchEngine.Query().q("needle").perPage(5000)).getPerPage(),
                "页长有上限, 不能让一次请求把整库拖走");
    }

    @Test
    public void orderingIsDeterministicAcrossRuns_forTiedScores() {
        SkillRegistry registry = registryOf(
                skill("zeta", "zeta", "alpha beta", "misc", "alpha", SkillFormat.AGENT_SKILLS),
                skill("mid", "mid", "alpha beta", "misc", "alpha", SkillFormat.AGENT_SKILLS),
                skill("bravo", "bravo", "alpha beta", "misc", "alpha", SkillFormat.AGENT_SKILLS),
                skill("kilo", "kilo", "alpha beta", "misc", "alpha", SkillFormat.AGENT_SKILLS));
        SkillSearchEngine engine = new SkillSearchEngine(registry);

        List<String> first = ids(engine.search(new SkillSearchEngine.Query().q("alpha")));
        assertEquals(Arrays.asList("bravo", "kilo", "mid", "zeta"), first,
                "同分必须落到 id 序, 否则 ConcurrentHashMap 的桶序会泄漏成结果序");
        for (int i = 0; i < 5; i++) {
            assertEquals(first, ids(engine.search(new SkillSearchEngine.Query().q("alpha"))));
        }
        List<String> browse = new ArrayList<String>();
        for (SkillDto d : registry.listAll()) browse.add(d.getId());
        assertEquals(browse, ids(engine.search(new SkillSearchEngine.Query())), "浏览态与 listAll 给同一个稳定序");

        // 同分时安装量高的在前
        registry.register(skill("high-installs", "high-installs", "alpha beta", "misc", "alpha",
                SkillFormat.AGENT_SKILLS).toBuilder().installCount(99).build());
        List<String> withLeader = ids(engine.search(new SkillSearchEngine.Query().q("alpha")));
        assertEquals("high-installs", withLeader.get(0), "同分按安装量降序兜底");
        assertEquals(Arrays.asList("bravo", "kilo", "mid", "zeta"), withLeader.subList(1, withLeader.size()));
    }

    @Test
    public void namedSorts_overrideRelevance() {
        SkillRegistry registry = registryOf(
                skill("zeta", "zeta", "alpha beta", "misc", "alpha", SkillFormat.AGENT_SKILLS),
                skill("alpha-one", "alpha-one", "alpha beta", "misc", "alpha", SkillFormat.AGENT_SKILLS));
        registry.register(skill("mid", "mid", "alpha", "misc", "alpha", SkillFormat.AGENT_SKILLS)
                .toBuilder().installCount(50).build());
        SkillSearchEngine engine = new SkillSearchEngine(registry);

        assertEquals(Arrays.asList("mid", "alpha-one", "zeta"),
                ids(engine.search(new SkillSearchEngine.Query().q("alpha").sort("installs"))));
        assertEquals(Arrays.asList("alpha-one", "mid", "zeta"),
                ids(engine.search(new SkillSearchEngine.Query().q("alpha").sort("name"))));
        assertEquals(Arrays.asList("alpha-one", "mid", "zeta"),
                ids(engine.search(new SkillSearchEngine.Query().q("alpha").sort("updated"))),
                "updatedAt 相同则回落 id 序");
    }
}
