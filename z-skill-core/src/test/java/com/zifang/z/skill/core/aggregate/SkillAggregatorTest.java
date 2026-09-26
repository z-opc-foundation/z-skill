package com.zifang.z.skill.core.aggregate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.skill.api.dto.AggregateReportDto;
import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.dto.SkillIssueDto;
import com.zifang.z.skill.api.dto.SkillSourceDto;
import com.zifang.z.skill.api.spec.SkillFormat;
import com.zifang.z.skill.api.spec.SkillSpec;
import com.zifang.z.skill.core.discover.HttpFetcher;
import com.zifang.z.skill.core.discover.SkillSource;
import com.zifang.z.skill.core.registry.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聚合流水线(来源 → 扫描 → 归一化 → 命名空间 → 冲突消解 → 判级 → 换表 → 报告)的行为测试.
 *
 * <p>注册中心自身的差量语义由 {@code SkillRegistryTest} 覆盖, 这里只测它之上的流水线:
 * 报告的每个数字都要能被来源与条目反推出来, 坏来源不能拖垮好来源, 冲突与折叠都要留痕.
 */
public class SkillAggregatorTest {

    @TempDir
    Path tmp;

    // ---------- fixtures ----------

    private static String skillMd(String name, String description, String body) {
        return "---\nname: " + name + "\ndescription: " + description + "\nlicense: MIT\n---\n\n"
                + "# " + name + "\n\n" + body + "\n";
    }

    private static Path writeSkill(Path root, String dir, String name, String description, String body)
            throws IOException {
        Path d = root.resolve(dir);
        Files.createDirectories(d);
        Path file = d.resolve("SKILL.md");
        Files.write(file, skillMd(name, description, body).getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static SkillSource local(String id, SkillFormat format, Path dir, int priority) {
        return new SkillSource(id, format, dir.toAbsolutePath().toString(), priority);
    }

    private static final class StubFetcher implements HttpFetcher {
        private final Map<String, String> bodies = new LinkedHashMap<String, String>();
        private final Set<String> unreachable = new HashSet<String>();
        int calls;

        StubFetcher ok(String url, String body) {
            bodies.put(url, body);
            return this;
        }

        StubFetcher fail(String url) {
            unreachable.add(url);
            return this;
        }

        @Override
        public String get(String url) throws IOException {
            calls++;
            if (unreachable.contains(url)) throw new IOException("HTTP 500 for " + url);
            String body = bodies.get(url);
            if (body == null) throw new IOException("no stub for " + url);
            return body;
        }
    }

    private static List<String> ids(List<SkillDto> list) {
        List<String> out = new ArrayList<String>();
        for (SkillDto d : list) out.add(d.getId());
        return out;
    }

    private static List<String> codes(AggregateReportDto report) {
        List<String> out = new ArrayList<String>();
        for (SkillIssueDto i : report.getIssues()) out.add(i.getCode());
        return out;
    }

    private static SkillSourceDto view(AggregateReportDto report, String sourceId) {
        for (SkillSourceDto s : report.getSources()) {
            if (sourceId.equals(s.getId())) return s;
        }
        throw new AssertionError("来源 " + sourceId + " 没有出现在报告里, 只看到 " + sourceIds(report));
    }

    private static List<String> sourceIds(AggregateReportDto report) {
        List<String> out = new ArrayList<String>();
        for (SkillSourceDto s : report.getSources()) out.add(s.getId());
        return out;
    }

    // ---------- (a) 单来源 ----------

    @Test
    public void singleSource_yieldsExpectedSkills_withCleanReport() throws Exception {
        writeSkill(tmp, "pdf-processing", "pdf-processing", "Extract text and tables from PDF files.", "Run the extractor.");
        writeSkill(tmp, "code-review", "code-review", "Review a diff and report defects.", "Be terse.");

        SkillRegistry registry = new SkillRegistry();
        SkillAggregator aggregator = new SkillAggregator(registry, new ObjectMapper());
        aggregator.addSource(local("alpha", SkillFormat.AGENT_SKILLS, tmp, 10));

        AggregateReportDto report = aggregator.refresh();

        assertEquals(1, report.getSourceCount(), "只声明了一个来源");
        assertEquals(2, report.getRawCount());
        assertEquals(2, report.getSkillCount());
        assertEquals(2, registry.skillCount());
        assertEquals(0, report.getDedupedCount());
        assertEquals(0, report.getConflictCount());
        assertEquals(0, report.getIssueCount(), "干净条目不该记账: " + report.getIssues());
        assertEquals(Arrays.asList("code-review", "pdf-processing"), ids(registry.listAll()));
        assertEquals("pdf-processing", registry.require("pdf-processing").getId());
        assertEquals(SkillFormat.AGENT_SKILLS.id(), registry.require("pdf-processing").getFormatId());
        assertEquals(SkillDto.RISK_SAFE, registry.require("pdf-processing").getRiskLevel());
        assertEquals(Collections.singletonMap(SkillFormat.AGENT_SKILLS.id(), 2), report.getFormatDistribution());
        assertEquals(registry.getSources(), report.getSources(), "注册中心与报告必须持有同一份来源视图");

        SkillSourceDto alpha = view(report, "alpha");
        assertEquals("ok", alpha.getStatus());
        assertEquals(2, alpha.getSkillCount());
        assertEquals(0, alpha.getIssueCount());
        assertEquals(2, alpha.getSkillIds().size());
        assertTrue(alpha.getSkillIds().contains("pdf-processing"));
        assertEquals(tmp.toAbsolutePath().toString(), alpha.getOrigin());
        assertEquals(10, alpha.getPriority());
        assertTrue(alpha.isEnabled());
        assertTrue(alpha.getLastRefreshAt() > 0);
    }

    // ---------- (b) 同 slug 双来源: 都不丢 + 命名空间 ----------

    @Test
    public void twoSourcesSameSlug_bothSurviveAndGetNamespacedIds() throws Exception {
        Path a = Files.createDirectories(tmp.resolve("a"));
        Path b = Files.createDirectories(tmp.resolve("b"));
        writeSkill(a, "pdf-processing", "pdf-processing", "Alpha flavour of the pdf skill.", "alpha body");
        writeSkill(b, "pdf-processing", "pdf-processing", "Beta flavour of the pdf skill.", "beta body");

        SkillRegistry registry = new SkillRegistry();
        SkillAggregator aggregator = new SkillAggregator(registry, new ObjectMapper());
        aggregator.addSource(local("alpha", SkillFormat.AGENT_SKILLS, a, 10));
        aggregator.addSource(local("beta", SkillFormat.AGENT_SKILLS, b, 20));

        AggregateReportDto report = aggregator.refresh();

        assertEquals(2, report.getSourceCount());
        assertEquals(2, report.getRawCount());
        assertEquals(2, report.getSkillCount(), "同名不同内容的两条都必须保留");
        assertEquals(2, registry.skillCount());
        assertEquals(Arrays.asList("alpha/pdf-processing", "beta/pdf-processing"), ids(registry.listAll()),
                "冲突双方都要落成 sourceId/slug 形式");
        assertTrue(registry.get("beta/pdf-processing").isPresent(), "命名空间写法必须可寻址");
        assertEquals("Beta flavour of the pdf skill.",
                registry.require("beta/pdf-processing").getDescription(), "低优先级那条的内容不能被覆盖");
        assertEquals("Alpha flavour of the pdf skill.",
                registry.require("alpha/pdf-processing").getDescription());
        assertTrue(report.getConflictCount() >= 1, "冲突必须记进报告");
        assertTrue(codes(report).contains("name-conflict"), "报告里要能看出是谁撞了名: " + codes(report));
        assertTrue(codes(report).contains("content-divergence"), "同 slug 不同内容要单独记账: " + codes(report));
        assertEquals(1, view(report, "alpha").getSkillCount());
        assertEquals(1, view(report, "beta").getSkillCount());
        assertEquals(2, report.getFormatDistribution().get(SkillFormat.AGENT_SKILLS.id()).intValue());
    }

    // ---------- (c) 同内容折叠 ----------

    @Test
    public void identicalContentFromTwoSources_foldsIntoOneEntryWithAliases() throws Exception {
        Path a = Files.createDirectories(tmp.resolve("claude-skills"));
        Path b = Files.createDirectories(tmp.resolve("agents-skills"));
        writeSkill(a, "pdf-processing", "pdf-processing", "Shared content across two platform dirs.", "same body");
        writeSkill(b, "pdf-processing", "pdf-processing", "Shared content across two platform dirs.", "same body");

        SkillRegistry registry = new SkillRegistry();
        SkillAggregator aggregator = new SkillAggregator(registry, new ObjectMapper());
        aggregator.addSource(local("claude", SkillFormat.AGENT_SKILLS, a, 10));
        aggregator.addSource(local("agents", SkillFormat.AGENT_SKILLS, b, 20));

        AggregateReportDto report = aggregator.refresh();

        assertEquals(2, report.getRawCount(), "两条原始候选都被读到");
        assertEquals(1, report.getSkillCount(), "同 contentHash 必须折成一条");
        assertEquals(1, registry.skillCount());
        assertEquals(1, report.getDedupedCount(), "折叠掉一条, dedupedCount 要能反映");
        SkillDto survivor = registry.listAll().get(0);
        assertEquals("claude/pdf-processing", survivor.getId(), "存活者归属高优先级来源");
        assertEquals("pdf-processing", survivor.getSlug());
        assertTrue(survivor.getAliases().contains("pdf-processing"), "裸 slug 要留在 alias 里: " + survivor.getAliases());
        assertTrue(survivor.getAliases().contains("agents/pdf-processing"),
                "被折叠那条的命名空间写法要降级为 alias: " + survivor.getAliases());
        assertEquals("claude", survivor.getSource());
        assertEquals("claude/pdf-processing", registry.require("agents/pdf-processing").getId(),
                "被折叠那条的命名空间写法仍要能查到存活者");
        assertEquals(1, view(report, "agents").getSkillCount(), "来源视图仍按它自己产出的条数计");
    }

    @Test
    public void duplicatedApiEntries_foldInstallCounts() throws Exception {
        String url = "https://skills.example.invalid/v1/skills";
        String payload = "{\"data\":["
                + "{\"slug\":\"api-alpha\",\"description\":\"Alpha from the registry\",\"installs\":5,\"source\":\"acme/skills\"},"
                + "{\"slug\":\"api-beta\",\"description\":\"Beta from the registry\",\"installs\":7,\"source\":\"acme/skills\"},"
                + "{\"slug\":\"api-alpha\",\"description\":\"Alpha from the registry\",\"installs\":4,\"source\":\"acme/skills\"}"
                + "]}";
        StubFetcher fetcher = new StubFetcher().ok(url, payload);

        SkillRegistry registry = new SkillRegistry();
        SkillAggregator aggregator = new SkillAggregator(registry, new ObjectMapper());
        aggregator.setHttpFetcher(fetcher);
        aggregator.addSource(new SkillSource("registry-api", SkillFormat.SKILLS_SH_API, url, 30));

        AggregateReportDto report = aggregator.refresh();

        assertEquals(1, report.getRawCount(), "一次 HTTP 响应只算一条原始候选");
        assertEquals(3, view(report, "registry-api").getSkillCount(), "来源视图按归一化后的候选计");
        assertEquals(1, report.getDedupedCount());
        assertEquals(2, report.getSkillCount());
        assertEquals(16, registry.require("api-beta").getInstallCount() + registry.require("api-alpha").getInstallCount(),
                "折叠时安装量累加");
        assertEquals(9, registry.require("api-alpha").getInstallCount(), "5 + 4 两条重复要加成 9");
        assertEquals(1, fetcher.calls);
    }

    // ---------- (d) 优先级决定裸标识归属 ----------

    @Test
    public void priorityDecidesWhoOwnsTheBareSlug_regardlessOfDeclarationOrder() throws Exception {
        Path fast = Files.createDirectories(tmp.resolve("fast"));
        Path slow = Files.createDirectories(tmp.resolve("slow"));
        writeSkill(fast, "pdf-processing", "pdf-processing", "Authoritative flavour from the fast source.", "fast body");
        writeSkill(slow, "pdf-processing", "pdf-processing", "Third party flavour from the slow source.", "slow body");

        SkillRegistry registry = new SkillRegistry();
        SkillAggregator aggregator = new SkillAggregator(registry, new ObjectMapper());
        // 故意先声明低优先级来源, 证明归属看 priority 而不是声明顺序
        aggregator.addSource(local("slow", SkillFormat.AGENT_SKILLS, slow, 90));
        aggregator.addSource(local("fast", SkillFormat.AGENT_SKILLS, fast, 10));

        AggregateReportDto report = aggregator.refresh();

        assertEquals(Arrays.asList("fast", "slow"), sourceIds(report), "来源视图按优先级排序");
        SkillDto owner = registry.require("pdf-processing");
        assertEquals("fast", owner.getSource(), "裸 slug 必须解析到最高优先级来源");
        assertEquals("Authoritative flavour from the fast source.", owner.getDescription());
        assertEquals("slow", registry.require("slow/pdf-processing").getSource());
        assertEquals(2, report.getSkillCount());
    }

    // ---------- (e) 报告数字自洽 ----------

    @Test
    public void reportNumbersAreSelfConsistent() throws Exception {
        Path a = Files.createDirectories(tmp.resolve("srcA"));
        Path b = Files.createDirectories(tmp.resolve("srcB"));
        writeSkill(a, "pdf-processing", "pdf-processing", "Extract text from PDF files.", "body a");
        writeSkill(a, "code-review", "code-review", "Review a diff and report defects.", "body a");
        writeSkill(b, "pdf-processing", "pdf-processing", "Extract text from PDF files.", "body a");
        // 一个坏文件: 没有 frontmatter, 只能记账
        Path brokenDir = Files.createDirectories(b.resolve("broken-no-frontmatter"));
        Files.write(brokenDir.resolve("SKILL.md"), "just prose, no fence at all\n".getBytes(StandardCharsets.UTF_8));

        String url = "https://skills.example.invalid/v2/skills";
        StubFetcher fetcher = new StubFetcher().ok(url, "{\"data\":["
                + "{\"slug\":\"api-alpha\",\"description\":\"Alpha from the registry\",\"installs\":5,\"source\":\"acme\"},"
                + "{\"slug\":\"api-beta\",\"description\":\"Beta from the registry\",\"installs\":7,\"source\":\"acme\"},"
                + "{\"slug\":\"api-alpha\",\"description\":\"Alpha from the registry\",\"installs\":4,\"source\":\"acme\"}]}");

        SkillRegistry registry = new SkillRegistry();
        SkillAggregator aggregator = new SkillAggregator(registry, new ObjectMapper());
        aggregator.setHttpFetcher(fetcher);
        aggregator.addSource(local("srcA", SkillFormat.AGENT_SKILLS, a, 10));
        aggregator.addSource(local("srcB", SkillFormat.AGENT_SKILLS, b, 20));
        aggregator.addSource(new SkillSource("api", SkillFormat.SKILLS_SH_API, url, 30));

        AggregateReportDto report = aggregator.refresh();

        int perSourceCandidates = 0;
        int perSourceIssues = 0;
        for (SkillSourceDto s : report.getSources()) {
            perSourceCandidates += s.getSkillCount();
            perSourceIssues += s.getIssueCount();
            assertTrue(s.getIssues().size() <= 8, "来源视图只放头几条 issue");
            assertTrue(s.getIssues().size() <= s.getIssueCount());
        }
        assertEquals(3, report.getSourceCount());
        assertEquals(report.getSources().size(), report.getSourceCount());
        assertEquals(registry.skillCount(), report.getSkillCount());
        assertEquals(perSourceCandidates, report.getSkillCount() + report.getDedupedCount(),
                "每个来源产出的候选数 = 收录条目数 + 被折叠数");
        assertEquals(report.getIssues().size(), report.getIssueCount());
        assertTrue(perSourceIssues <= report.getIssueCount(),
                "冲突/折叠这类合并期记账不挂在单个来源上, 所以只能小于等于");
        assertEquals(2, view(report, "srcA").getSkillCount());
        assertEquals(2, view(report, "srcB").getSkillCount(), "缺 frontmatter 的坏文件按宽松规则收录, 也算产出");
        assertEquals(3, view(report, "api").getSkillCount());
        assertEquals(0, view(report, "srcA").getIssueCount());
        assertEquals(2, view(report, "srcB").getIssueCount(), "坏文件的 error 与说明性 info 都要挂在 srcB 头上");
        assertEquals(5, report.getSkillCount());
        assertEquals(2, report.getDedupedCount());
        int formatSum = 0;
        for (Map.Entry<String, Integer> e : report.getFormatDistribution().entrySet()) {
            assertNotNull(e.getKey());
            formatSum += e.getValue();
        }
        assertEquals(report.getSkillCount(), formatSum, "formatDistribution 必须正好覆盖所有收录条目");
        assertEquals(Integer.valueOf(3), report.getFormatDistribution().get(SkillFormat.AGENT_SKILLS.id()));
        assertEquals(Integer.valueOf(2), report.getFormatDistribution().get(SkillFormat.SKILLS_SH_API.id()));
        assertEquals(6, report.getIssueCount(), "1 条 warning(坏文件) + 1 条 info + 4 条合并期冲突(两组同名各 2 条)");
        int severitySum = 0;
        for (SkillIssueDto i : report.getIssues()) severitySum += 1;
        assertEquals(report.getIssueCount(), severitySum);
        int severityBucketSum = 0;
        for (Map.Entry<String, Integer> e : report.getSeverityDistribution().entrySet()) {
            assertTrue(Arrays.asList("error", "warning", "info").contains(e.getKey()), "非法 severity: " + e.getKey());
            severityBucketSum += e.getValue();
        }
        assertEquals(report.getIssueCount(), severityBucketSum, "severityDistribution 必须正好覆盖所有 issue");
        // 这个语料里没有任何条目被丢弃, 所以 error 档必须一条都没有 —— 坏文件也进了目录, 只能记 warning.
        // 口径见 SkillIssueDto: error = "这条没进目录", 否则收录数与 error 数会互相打脸.
        assertNull(report.getSeverityDistribution().get("error"), "没有丢弃发生就不许有 error: " + codes(report));
        assertEquals(Integer.valueOf(5), report.getSeverityDistribution().get("warning"));
        assertEquals(Integer.valueOf(1), report.getSeverityDistribution().get("info"));
        assertEquals(0, report.getDroppedCount(), "5 个候选全部有产出(坏文件也收下了)");
        // 候选按"发现层交给适配器的东西"数: 一份远端响应 = 一个候选, 展开成 3 条是产出侧的事
        assertEquals(5, report.getRawCount(), "srcA 2 份文件 + srcB 2 份文件(含坏文件) + api 1 份响应");
        assertEquals(7, report.getProducedCount(), "srcA 2 + srcB 2 + api 那份响应展开成 3");
        // 两条按构造成立的恒等式: 产出 = 目录 + 折叠; 来源视图的产出之和 = 报告的产出总数
        assertEquals(report.getProducedCount(), report.getSkillCount() + report.getDedupedCount());
        assertEquals(perSourceCandidates, report.getProducedCount(), "来源视图逐条相加必须等于报告产出总数");
        for (SkillDto dto : registry.listAll()) {
            assertTrue(sourceIds(report).contains(dto.getSource()), "条目的 source 必须是已声明的来源: " + dto.getId());
        }
    }

    // ---------- (f) 部分失败隔离 ----------

    @Test
    public void unreachableAndGarbageSources_doNotZeroOutHealthyOnes() throws Exception {
        writeSkill(tmp, "pdf-processing", "pdf-processing", "Extract text from PDF files.", "body");
        String dead = "https://dead.example.invalid/v1/skills";
        String garbage = "https://garbage.example.invalid/v1/skills";
        StubFetcher fetcher = new StubFetcher().fail(dead).ok(garbage, "<html><body>502 Bad Gateway</body></html>");

        SkillRegistry registry = new SkillRegistry();
        SkillAggregator aggregator = new SkillAggregator(registry, new ObjectMapper());
        aggregator.setHttpFetcher(fetcher);
        aggregator.addSource(new SkillSource("dead-api", SkillFormat.SKILLS_SH_API, dead, 5));
        aggregator.addSource(local("alpha", SkillFormat.AGENT_SKILLS, tmp, 10));
        aggregator.addSource(new SkillSource("garbage-api", SkillFormat.SKILLS_SH_API, garbage, 15));

        AggregateReportDto report = aggregator.refresh();

        assertEquals(3, report.getSourceCount(), "坏来源也要出现在报告里, 不能静默消失");
        assertEquals(1, report.getSkillCount(), "唯一的健康来源必须照常收录");
        assertEquals(1, registry.skillCount());
        assertTrue(registry.get("pdf-processing").isPresent());
        assertEquals("error", view(report, "dead-api").getStatus());
        assertEquals(0, view(report, "dead-api").getSkillCount());
        assertEquals("ok", view(report, "alpha").getStatus());
        assertTrue(codes(report).contains("fetch-failed"), "取数失败要留痕: " + codes(report));
        assertTrue(codes(report).contains("source-failed"), "来源级失败要留痕: " + codes(report));
        assertTrue(codes(report).contains("payload-not-json"), "非 JSON 响应要留痕: " + codes(report));
        assertEquals(0, view(report, "garbage-api").getSkillCount());
        assertEquals(2, view(report, "dead-api").getIssueCount());
        assertEquals(3, report.getIssueCount());
        for (SkillIssueDto i : report.getIssues()) {
            assertEquals("error", i.getSeverity());
            assertNotNull(i.getPath(), "issue 必须能指到出问题的位置");
        }
    }

    @Test
    public void missingLocalDirectory_isRecordedAndIsolated() throws Exception {
        writeSkill(tmp, "pdf-processing", "pdf-processing", "Extract text from PDF files.", "body");
        SkillRegistry registry = new SkillRegistry();
        SkillAggregator aggregator = new SkillAggregator(registry, new ObjectMapper());
        aggregator.addSource(local("ghost", SkillFormat.AGENT_SKILLS, tmp.resolve("nope"), 5));
        aggregator.addSource(local("alpha", SkillFormat.AGENT_SKILLS, tmp, 10));

        AggregateReportDto report = aggregator.refresh();

        assertEquals(1, report.getSkillCount());
        assertTrue(codes(report).contains("source-missing"));
        assertEquals(0, view(report, "ghost").getSkillCount());
    }

    // ---------- (g) 幂等与上游漂移 ----------

    @Test
    public void refreshIsIdempotent_thenReportsExactlyTheEditedSkillAsChanged() throws Exception {
        writeSkill(tmp, "alpha-skill", "alpha-skill", "First description.", "body");
        writeSkill(tmp, "beta-skill", "beta-skill", "Second description.", "body");

        SkillRegistry registry = new SkillRegistry();
        SkillAggregator aggregator = new SkillAggregator(registry, new ObjectMapper());
        aggregator.addSource(local("alpha", SkillFormat.AGENT_SKILLS, tmp, 10));

        aggregator.refresh();
        assertEquals(Arrays.asList("alpha-skill", "beta-skill"), registry.getAdded());

        AggregateReportDto second = aggregator.refresh();
        assertTrue(registry.getAdded().isEmpty(), "输入未变却报新增: " + registry.getAdded());
        assertTrue(registry.getChanged().isEmpty(), "输入未变却报漂移: " + registry.getChanged());
        assertTrue(registry.getRemoved().isEmpty(), "输入未变却报撤下: " + registry.getRemoved());
        assertEquals(2, second.getSkillCount());

        writeSkill(tmp, "beta-skill", "beta-skill", "Second description, now reworded.", "body");
        aggregator.refresh();
        assertEquals(Collections.singletonList("beta-skill"), registry.getChanged(),
                "只有被改过的那条该被判定为漂移");
        assertTrue(registry.getAdded().isEmpty());
        assertTrue(registry.getRemoved().isEmpty());
        assertEquals("Second description, now reworded.", registry.require("beta-skill").getDescription());

        Files.delete(tmp.resolve("beta-skill/SKILL.md"));
        aggregator.refresh();
        assertEquals(Collections.singletonList("beta-skill"), registry.getRemoved());
        assertEquals(Collections.singletonList("alpha-skill"), ids(registry.listAll()));
    }

    // ---------- (h) 上限守卫 ----------

    @Test
    public void maxSkillsPerSource_capsTheSourceAndRecordsAnIssue() throws Exception {
        int n = SkillSpec.MAX_SKILLS_PER_SOURCE + 2;
        for (int i = 0; i < n; i++) {
            String slug = String.format("bulk-%05d", i);
            Files.write(tmp.resolve(slug + ".md"),
                    skillMd(slug, "Bulk skill number " + i, "body").getBytes(StandardCharsets.UTF_8));
        }

        SkillRegistry registry = new SkillRegistry();
        SkillAggregator aggregator = new SkillAggregator(registry, new ObjectMapper());
        aggregator.addSource(local("bulk", SkillFormat.FLAT_MARKDOWN, tmp, 10));

        AggregateReportDto report = aggregator.refresh();

        assertTrue(codes(report).contains("source-too-large"), "超过上限必须记账而不是闷声收: " + codes(report));
        assertEquals(SkillSpec.MAX_SKILLS_PER_SOURCE, report.getRawCount(), "原始候选要被截断到上限");
        assertEquals(SkillSpec.MAX_SKILLS_PER_SOURCE, report.getSkillCount());
        assertEquals(SkillSpec.MAX_SKILLS_PER_SOURCE, registry.skillCount());
        assertEquals(SkillSpec.MAX_SKILLS_PER_SOURCE, view(report, "bulk").getSkillCount());
    }

    @Test
    public void maxDepthAndDefaultScanDepth_controlHowDeepWeLook() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("tree"));
        writeSkill(root, "skill-shallow", "skill-shallow", "One level below the source root.", "body");
        writeSkill(root.resolve("n1/n2"), "skill-mid", "skill-mid", "Three levels below the source root.", "body");
        writeSkill(root.resolve("d1/d2/d3/d4/d5/d6/d7"), "skill-toodeep", "skill-toodeep",
                "Eight levels below the source root.", "body");

        SkillRegistry oneLevel = new SkillRegistry();
        SkillAggregator a1 = new SkillAggregator(oneLevel, new ObjectMapper());
        a1.addSource(local("depth1", SkillFormat.AGENT_SKILLS, root, 10).maxDepth(1));
        AggregateReportDto shallow = a1.refresh();
        assertEquals(Collections.singletonList("skill-shallow"), ids(oneLevel.listAll()),
                "maxDepth=1 只看得到一层目录里的 skill");
        assertEquals(1, shallow.getRawCount());
        assertEquals(0, shallow.getIssueCount());

        SkillRegistry threeLevels = new SkillRegistry();
        SkillAggregator a3 = new SkillAggregator(threeLevels, new ObjectMapper());
        a3.addSource(local("depth3", SkillFormat.AGENT_SKILLS, root, 10).maxDepth(3));
        a3.refresh();
        assertEquals(Arrays.asList("skill-mid", "skill-shallow"), ids(threeLevels.listAll()));

        SkillRegistry byDefault = new SkillRegistry();
        SkillAggregator aDefault = new SkillAggregator(byDefault, new ObjectMapper());
        aDefault.addSource(local("auto", SkillFormat.AGENT_SKILLS, root, 10));
        AggregateReportDto defaultReport = aDefault.refresh();
        assertEquals(Arrays.asList("skill-mid", "skill-shallow"), ids(byDefault.listAll()),
                "未指定 maxDepth 时用 SkillSpec.MAX_SCAN_DEPTH=" + SkillSpec.MAX_SCAN_DEPTH + " 层, 更深的要舍掉");
        assertEquals(2, defaultReport.getRawCount());
        assertTrue(codes(defaultReport).isEmpty(), "被深度守卫舍掉的目录不该留下任何记账: " + codes(defaultReport));
    }

    // ---------- (i) 禁用的来源 ----------

    @Test
    public void disabledSource_contributesNothing() throws Exception {
        Path a = Files.createDirectories(tmp.resolve("enabled"));
        Path b = Files.createDirectories(tmp.resolve("disabled"));
        writeSkill(a, "alpha-skill", "alpha-skill", "From the enabled source.", "body");
        writeSkill(b, "beta-skill", "beta-skill", "From the disabled source.", "body");

        SkillRegistry registry = new SkillRegistry();
        SkillAggregator aggregator = new SkillAggregator(registry, new ObjectMapper());
        aggregator.addSource(local("on", SkillFormat.AGENT_SKILLS, a, 10));
        aggregator.addSource(local("off", SkillFormat.AGENT_SKILLS, b, 20).enabled(false));

        AggregateReportDto report = aggregator.refresh();

        assertEquals(Collections.singletonList("on"), sourceIds(report));
        assertEquals(1, report.getSourceCount());
        assertEquals(1, report.getSkillCount());
        assertEquals(Collections.singletonList("alpha-skill"), ids(registry.listAll()));
        assertTrue(!registry.get("beta-skill").isPresent(), "禁用来源的 skill 不能进目录");
        assertEquals(2, aggregator.getDeclaredSources().size(), "声明列表仍要能看到被禁用的来源");
        try {
            view(report, "off");
        } catch (AssertionError expected) {
            // 禁用的来源不进报告
        }
    }

    // ---------- 判级链路 ----------

    @Test
    public void upstreamDeclaredCriticalRisk_survivesTheLocalScan() throws Exception {
        String url = "https://skills.example.invalid/v3/skills";
        StubFetcher fetcher = new StubFetcher().ok(url, "{\"data\":[{\"slug\":\"audited-critical\","
                + "\"description\":\"Declared critical by the upstream audit team\",\"riskLevel\":\"critical\","
                + "\"source\":\"acme\"}]}");

        SkillRegistry registry = new SkillRegistry();
        SkillAggregator aggregator = new SkillAggregator(registry, new ObjectMapper());
        aggregator.setHttpFetcher(fetcher);
        aggregator.addSource(new SkillSource("api", SkillFormat.SKILLS_SH_API, url, 30));
        AggregateReportDto report = aggregator.refresh();

        assertEquals(1, report.getSkillCount());
        assertEquals(SkillDto.RISK_CRITICAL, registry.require("audited-critical").getRiskLevel(),
                "本机纯文本判级只覆盖自己能看见的部分, 上游审计更差的等级不能被抹平");
    }

    @Test
    public void withoutAScanner_riskLevelStaysUnscanned() throws Exception {
        writeSkill(tmp, "pdf-processing", "pdf-processing", "Extract text from PDF files.", "body");
        SkillRegistry registry = new SkillRegistry();
        SkillAggregator aggregator = new SkillAggregator(registry, new ObjectMapper(), null);
        aggregator.addSource(local("alpha", SkillFormat.AGENT_SKILLS, tmp, 10));
        aggregator.refresh();

        assertEquals(SkillDto.RISK_UNSCANNED, registry.require("pdf-processing").getRiskLevel(),
                "没扫过就该是 unscanned, 不能伪装成 safe");
        assertTrue(registry.require("pdf-processing").getIssues().isEmpty());
    }

    /**
     * 来源视图要答得出"这个目录是哪个平台的形状".
     *
     * <p>自动发现的本机平台目录一律以 {@code UNKNOWN} 声明进场(没人知道用户装了些什么), 靠扫描识别.
     * 只回显声明值的话, 控制台那张最显眼的来源表会把 anthropics/codex/qoder 全列成 unknown —
     * 聚合器的头号主张"我们聚合了这些平台"被自己的报表否认了.
     */
    @Test
    public void sourceView_reportsTheShapesItActuallyRecognized() throws Exception {
        Path a = tmp.resolve("a");
        writeSkill(a, "pdf-processing", "pdf-processing", "Extract text from PDF files.", "body");
        Files.write(a.resolve("AGENTS.md"),
                ("# Repo rules\n\nbody line\n").getBytes(StandardCharsets.UTF_8));
        Path b = tmp.resolve("b");
        writeSkill(b, "code-review", "code-review", "Review a diff.", "Be terse.");
        Path c = tmp.resolve("c");
        Files.createDirectories(c);

        SkillRegistry registry = new SkillRegistry();
        SkillAggregator aggregator = new SkillAggregator(registry, new ObjectMapper());
        aggregator.addSource(local("discovered", SkillFormat.UNKNOWN, a, 10));
        aggregator.addSource(local("declared", SkillFormat.AGENT_SKILLS, b, 20));
        aggregator.addSource(local("empty", SkillFormat.UNKNOWN, c, 30));
        AggregateReportDto report = aggregator.refresh();

        SkillSourceDto found = view(report, "discovered");
        assertEquals(SkillFormat.UNKNOWN.id(), found.getFormat(), "声明的形状归声明, 扫出来的不许覆写它");
        assertEquals(Arrays.asList(SkillFormat.AGENT_SKILLS.id(), SkillFormat.AGENTS_MD.id()),
                found.getObservedFormats(), "一个目录里同时认出两种平台形状时都要列出来, 顺序要可复现");

        assertEquals(Collections.singletonList(SkillFormat.AGENT_SKILLS.id()),
                view(report, "declared").getObservedFormats(), "声明了格式的来源同样要记实际观察值, 别只给自动发现的那条开小灶");

        assertTrue(view(report, "empty").getObservedFormats().isEmpty(),
                "什么都没扫到就该是空, 不能拿声明值冒充观察值: " + view(report, "empty").getObservedFormats());
    }
}
