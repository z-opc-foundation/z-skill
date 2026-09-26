package com.zifang.z.skill.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.skill.api.dto.AggregateReportDto;
import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.dto.SkillIssueDto;
import com.zifang.z.skill.api.dto.SkillSourceDto;
import com.zifang.z.skill.api.spec.SkillFormat;
import com.zifang.z.skill.core.aggregate.SkillAggregator;
import com.zifang.z.skill.core.discover.SkillSource;
import com.zifang.z.skill.core.registry.SkillRegistry;
import com.zifang.z.skill.core.scan.SkillSecurityScanner;
import com.zifang.z.skill.core.search.SkillSearchEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制面查询服务与注册中心的对账测试 — 用真临时目录跑真聚合, 不用手搓 DTO,
 * 因为 {@code AdminQueryService} 的全部价值就是"把聚合报告的数字如实搬给运维看".
 *
 * <p>覆盖三条: 计数守恒(skill/来源/分类/风险分布)、逐来源行、issue 过滤、
 * 以及"装过的东西上游撤了"这条 dangling 路径.
 */
class AdminQueryServiceReconciliationTest {

    private static final String ALPHA = "alpha";
    private static final String BETA = "beta";
    private static final String GAMMA = "gamma-missing-dir";

    @TempDir
    Path tmp;

    private SkillRegistry registry;
    private SkillSearchEngine search;
    private SkillAggregator aggregator;
    private AdminQueryService service;
    private Path alphaDir;
    private Path betaDir;

    @BeforeEach
    void wireRealPipeline() throws IOException {
        registry = new SkillRegistry();
        search = new SkillSearchEngine(registry);
        service = new AdminQueryService(registry, search);
        aggregator = new SkillAggregator(registry, new ObjectMapper(), new SkillSecurityScanner());
        alphaDir = Files.createDirectories(tmp.resolve("alpha"));
        betaDir = Files.createDirectories(tmp.resolve("beta"));
        writeSkill(alphaDir, "deploy-helper", "Deploy helper", "把服务部署到测试环境的助手");
        writeSkill(alphaDir, "scheduler-doc", "Scheduler doc", "任务排期与调度说明");
        // 没有 frontmatter 的真文件: 按宽松收录口径进目录并记 warning, 控制面要能看见这笔账
        Files.createDirectories(alphaDir.resolve("broken"));
        Files.write(alphaDir.resolve("broken/SKILL.md"),
                "# 没有 frontmatter 的正文\n\n只有说明文字.\n".getBytes(StandardCharsets.UTF_8));
        writeSkill(betaDir, "beta-only", "Beta only", "第二个来源里的条目");

        aggregator.addSource(new SkillSource(ALPHA, SkillFormat.AGENT_SKILLS, alphaDir.toString(), 10).maxDepth(2));
        aggregator.addSource(new SkillSource(BETA, SkillFormat.AGENT_SKILLS, betaDir.toString(), 20).maxDepth(2));
    }

    // ------------------------------------------------------------------ 空态

    /** 还没聚合过: 所有数字是 0, 列表是空而不是 null, health 说 UP. */
    @Test
    void emptyRegistryYieldsZeroedOverviewNotNullListsAndUpHealth() {
        Map<String, Object> overview = service.overview();
        assertEquals(0, overview.get("skillCount"), "空注册中心的 skillCount 不是 0: " + overview);
        assertEquals(0, overview.get("installedCount"), "空注册中心的 installedCount 不是 0: " + overview);
        assertEquals(0, overview.get("categoryCount"), "空注册中心的 categoryCount 不是 0: " + overview);
        assertEquals(0, overview.get("sourceCount"), "空注册中心的 sourceCount 不是 0: " + overview);
        assertEquals(0, overview.get("rawCandidates"), "没聚合过却有 rawCandidates: " + overview);
        assertEquals(0, overview.get("issues"), "没聚合过却有 issues: " + overview);
        assertEquals(0, overview.get("conflicts"), "没聚合过却有 conflicts: " + overview);
        assertEquals(0, overview.get("deduped"), "没聚合过却有 deduped: " + overview);
        assertEquals("z-skill", overview.get("platform"));
        assertEquals("skill-platform-aggregator", overview.get("role"));
        assertTrue(((Map<?, ?>) overview.get("riskDistribution")).isEmpty(), "空态还有风险分布");
        assertTrue(((Map<?, ?>) overview.get("formatDistribution")).isEmpty(), "空态还有格式分布");

        assertTrue(service.listSkills(null).isEmpty());
        assertTrue(service.listInstalled().isEmpty());
        assertTrue(service.listCategories().isEmpty());
        assertTrue(service.listSources().isEmpty());
        assertTrue(service.listIssues(null).isEmpty(), "报告为空时 issues 必须是空列表而不是 null");
        assertNotNull(service.listIssues("error"));
        assertEquals("UP", service.health().get("status"));
        assertEquals(0, service.health().get("skills"));
    }

    // ------------------------------------------------------------------ 计数守恒

    @Test
    void overviewNumbersAreExactlyTheRegistryAndTheLastReport() throws IOException {
        AggregateReportDto report = aggregator.refresh();
        assertTrue(report.getSkillCount() >= 3, "临时语料本身就没被收全: " + describe(report));

        Map<String, Object> overview = service.overview();
        assertEquals(registry.skillCount(), overview.get("skillCount"), "overview.skillCount 与注册中心不符");
        assertEquals(registry.installedCount(), overview.get("installedCount"), "installedCount 与注册中心不符");
        assertEquals(search.listCategories().size(), overview.get("categoryCount"), "categoryCount 与检索引擎不符");
        assertEquals(registry.getSources().size(), overview.get("sourceCount"), "sourceCount 与注册中心不符");
        assertEquals(report.getRawCount(), overview.get("rawCandidates"), "rawCandidates 与最后一次聚合报告不符");
        assertEquals(report.getDedupedCount(), overview.get("deduped"), "deduped 与报告不符");
        assertEquals(report.getConflictCount(), overview.get("conflicts"), "conflicts 与报告不符");
        assertEquals(report.getIssueCount(), overview.get("issues"), "issues 与报告不符");
        assertEquals(report.getIssues().size(), overview.get("issues"), "报告的 issueCount 与 issues 长度不符");

        int riskSum = sum(((Map<?, ?>) overview.get("riskDistribution")).values());
        int formatSum = sum(((Map<?, ?>) overview.get("formatDistribution")).values());
        assertEquals(registry.skillCount(), riskSum, "riskDistribution 加起来不等于条目数: " + overview);
        assertEquals(registry.skillCount(), formatSum, "formatDistribution 加起来不等于条目数: " + overview);
        Set<String> formats = new LinkedHashSet<String>();
        for (Object k : ((Map<?, ?>) overview.get("formatDistribution")).keySet()) formats.add(String.valueOf(k));
        assertTrue(formats.contains(SkillFormat.AGENT_SKILLS.id()), "SKILL.md 条目没落在 agent-skills 上: " + formats);
    }

    /** 控制面把"哪些来源被扫了、每个来源收了多少"如实摊开: 逐来源行的数字要能加回去. */
    @Test
    void perSourceRowsReconcileWithCandidateAccounting() throws IOException {
        AggregateReportDto report = aggregator.refresh();
        List<SkillSourceDto> rows = service.listSources();
        assertEquals(new TreeSet<String>(Arrays.asList(ALPHA, BETA)), idsOfSources(rows),
                "来源行不对: " + rows);
        assertEquals(report.getSources().size(), rows.size(), "listSources 与报告里的来源数不符");

        int produced = 0;
        for (SkillSourceDto row : rows) {
            produced += row.getSkillCount();
            assertNotNull(row.getOrigin(), "来源行没有 origin: " + row);
            assertEquals("ok", row.getStatus(), "两个正常目录的来源状态不该是 " + row.getStatus());
            assertTrue(row.getDurationMs() >= 0);
        }
        // 逐来源行的 skillCount 是"该来源产出的条目数"(合并前), 加起来必须等于报告的 producedCount
        assertEquals(report.getProducedCount(), produced,
                "逐来源产出行加起来不等于 producedCount: producedCount=" + report.getProducedCount() + " rows=" + rows);
        assertEquals(report.getProducedCount(), report.getSkillCount() + report.getDedupedCount(),
                "produced != skill + deduped: " + describe(report));
        assertEquals(report.getProducedCount() + report.getDroppedCount(), report.getRawCount(),
                "候选不守恒 raw=" + report.getRawCount() + " produced=" + report.getProducedCount()
                        + " dropped=" + report.getDroppedCount() + "; " + describe(report));
        // 新 severity 口径: error 只留给"没进目录"的. 没 frontmatter 的文件是宽松收录 + warning,
        // 所以这里不能再要求一笔 error —— 要求它是"控制面看得见的 warning".
        assertTrue(hasCode(report.getIssues(), "warning:frontmatter-missing"),
                "没 frontmatter 的那个真文件必须留下 warning: " + codes(report.getIssues()));
        assertEquals(0, countSeverity(report.getIssues(), "error"),
                "两个正常目录不该有任何候选被丢弃, 却记了 error: " + codes(report.getIssues()));
    }

    /** 只有"抛异常"的来源才会被标成 status=error; 目录不存在只记 issue, 于是 /skill/admin/health 依然报 UP. */
    @Test
    void missingSourceDirectoryIsBookedAsAnErrorIssueButHealthStillSaysUp() throws IOException {
        aggregator.addSource(new SkillSource(GAMMA, SkillFormat.AGENT_SKILLS,
                tmp.resolve("does-not-exist").toString(), 30).maxDepth(2));
        AggregateReportDto report = aggregator.refresh();

        SkillSourceDto gamma = null;
        for (SkillSourceDto row : service.listSources()) {
            if (GAMMA.equals(row.getId())) gamma = row;
        }
        assertNotNull(gamma, "缺目录的来源在控制面行里消失了: " + service.listSources());
        assertEquals(0, gamma.getSkillCount(), "不存在的目录竟然产出了条目");
        assertTrue(hasIssueAmong(gamma.getIssues(), "source-missing"),
                "来源行没带上 source-missing 这笔账: " + gamma.getIssues());
        assertTrue(hasCode(report.getIssues(), "error:source-missing"),
                "报告里缺 source-missing: " + codes(report.getIssues()));
        assertEquals(3, service.health().get("sources"), "health 看到的来源数不对");
        assertEquals("UP", service.health().get("status"),
                "health 的 status 现在只看 status=error, 目录缺失/解析失败不会让它 DEGRADED;"
                        + " 若改成按 error issue 判定, 请同步这条断言与 README");
        assertEquals(0, service.health().get("sourcesFailed"), "sourcesFailed 统计不对: " + service.health());
    }

    // ------------------------------------------------------------------ issue 过滤

    @Test
    void issueFilteringMatchesSeverityAndNeverLeaksOtherSeverities() throws IOException {
        AggregateReportDto report = aggregator.refresh();
        assertEquals(report.getIssues().size(), service.listIssues(null).size(),
                "severity=null 应当原样给出全部 issue");
        assertEquals(countSeverity(report.getIssues(), "error"), service.listIssues("error").size(),
                "error 过滤结果数不对: " + codes(service.listIssues("error")));
        for (SkillIssueDto issue : service.listIssues("error")) {
            assertEquals("error", issue.getSeverity(), "error 过滤里漏进了别的严重级: " + issue);
        }
        assertEquals(countSeverity(report.getIssues(), "warning"), service.listIssues("warning").size(),
                "warning 过滤结果数不对: " + codes(service.listIssues("warning")));
        assertTrue(service.listIssues("does-not-exist").isEmpty(),
                "未知 severity 竟然返回了内容 → 过滤条件被忽略: " + codes(service.listIssues("does-not-exist")));
        assertTrue(service.listIssues(null).size() > service.listIssues("warning").size(),
                "报告里只有 warning 一种严重级, 这条测试失去意义: " + codes(report.getIssues()));
    }

    // ------------------------------------------------------------------ 列表/分类

    @Test
    void skillListingAgreesWithTheSearchEngineForEveryCategory() throws IOException {
        aggregator.refresh();
        List<SkillDto> all = service.listSkills(null);
        assertEquals(registry.skillCount(), all.size(), "listSkills(null) 与注册中心条目数不符");

        List<String> categories = service.listCategories();
        assertFalse(categories.isEmpty(), "聚合完还没有分类: " + describe(registry.getLastReport()));
        Map<String, Integer> counts = search.categoryCounts();
        int covered = 0;
        for (String category : categories) {
            List<SkillDto> rows = service.listSkills(category);
            assertEquals(counts.get(category).intValue(), rows.size(),
                    "分类 " + category + " 的控制面行数与 facet 计数不符: " + rowsOf(rows));
            for (SkillDto row : rows) {
                assertEquals(category, row.getCategory(), "分类过滤后仍返回别的分类: " + row.getId());
            }
            covered += rows.size();
        }
        assertEquals(registry.skillCount(), covered,
                "所有分类的行数加起来不等于总数 → 有条目不属于任何可查分类");
        assertTrue(service.listSkills("__definitely-not-a-category__").isEmpty(),
                "未知分类竟然有结果");
    }

    /** 每个 DTO 都有一个可分类的落点: categoryHint 走的是同一套分类逻辑, 这里钉住"默认分类存在". */
    @Test
    void everyCorpusSkillCarriesRiskLevelAndContentHashTheAdminViewReads() throws IOException {
        aggregator.refresh();
        List<String> missing = new ArrayList<String>();
        for (SkillDto dto : service.listSkills(null)) {
            if (dto.getRiskLevel() == null || "unscanned".equals(dto.getRiskLevel())) {
                missing.add(dto.getId() + " risk=" + dto.getRiskLevel());
            }
            if (dto.getContentHash() == null || dto.getContentHash().isEmpty()) {
                missing.add(dto.getId() + " hash=" + dto.getContentHash());
            }
        }
        assertTrue(missing.isEmpty(), "控制面依赖的字段没填上: " + missing);
    }

    // ------------------------------------------------------------------ 安装 / dangling

    /**
     * 装过的条目被上游撤掉时: installedCount(记录数) 保留, listInstalled(能渲染的行) 少一条,
     * 差额必须等于 danglingInstalls —— 控制面不能把"用户装过的东西"静默吞掉.
     */
    @Test
    void installsAndDanglingInstallsReconcileWithRegistryRecords() throws IOException {
        aggregator.refresh();
        int skillsBefore = registry.skillCount();
        registry.install("deploy-helper", "z-skill-admin-e2e", "test");
        registry.install("scheduler-doc", "z-skill-admin-e2e", "test");
        assertEquals(2, service.overview().get("installedCount"), "两条安装记录没被如实报出");

        List<SkillDto> installed = service.listInstalled();
        assertEquals(2, installed.size(), "listInstalled 少了行: " + rowsOf(installed));
        assertEquals(rowsOf(registry.installedSkills()), rowsOf(installed),
                "listInstalled 与注册中心的已安装视图不一致");
        assertTrue(service.listInstalled().get(0).getId().compareTo(service.listInstalled().get(1).getId()) < 0,
                "已安装列表没按 id 稳定排序: " + rowsOf(installed));

        deleteRecursively(alphaDir.resolve("deploy-helper"));
        AggregateReportDto report = aggregator.refresh();
        assertEquals(2, registry.installedCount(), "安装记录被聚合冲掉了(不该回收用户装过的东西)");
        assertEquals(Arrays.asList("deploy-helper"), registry.danglingInstalls(),
                "上游撤下后 dangling 名单不对; report=" + describe(report));
        List<SkillDto> stillVisible = service.listInstalled();
        assertEquals(1, stillVisible.size(), "dangling 条目还留在已安装视图里: " + rowsOf(stillVisible));
        assertEquals("scheduler-doc", stillVisible.get(0).getId());
        assertEquals(registry.installedCount() - stillVisible.size(), registry.danglingInstalls().size(),
                "installedCount 与已安装视图的差额不等于 dangling 数");
        assertEquals(skillsBefore - 1, service.overview().get("skillCount"),
                "撤下一条后总数没跟着变: " + service.overview());
    }

    // ------------------------------------------------------------------ helpers

    private static void writeSkill(Path root, String slug, String name, String description) throws IOException {
        Path dir = Files.createDirectories(root.resolve(slug));
        String md = "---\n"
                + "name: " + name + "\n"
                + "description: " + description + "\n"
                + "metadata:\n"
                + "  version: 0.3.1\n"
                + "---\n\n"
                + "# " + slug + "\n\n"
                + "使用说明, 无可执行片段.\n";
        Files.write(dir.resolve("SKILL.md"), md.getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        List<Path> all = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
            s.forEach(all::add);
        }
        all.sort(Comparator.reverseOrder());
        for (Path p : all) Files.deleteIfExists(p);
    }

    private static int sum(java.util.Collection<?> values) {
        int n = 0;
        for (Object v : values) n += ((Number) v).intValue();
        return n;
    }

    private static Set<String> idsOfSources(List<SkillSourceDto> rows) {
        Set<String> out = new TreeSet<String>();
        for (SkillSourceDto row : rows) out.add(row.getId());
        return out;
    }

    private static List<String> rowsOf(List<SkillDto> rows) {
        List<String> out = new ArrayList<String>();
        for (SkillDto d : rows) out.add(d.getId());
        return out;
    }

    private static List<String> codes(List<SkillIssueDto> issues) {
        List<String> out = new ArrayList<String>();
        for (SkillIssueDto i : issues) out.add(i.getSeverity() + ":" + i.getCode() + "@" + i.getPath());
        return out;
    }

    private static boolean hasCode(List<SkillIssueDto> issues, String severityColonCode) {
        for (SkillIssueDto i : issues) {
            if ((i.getSeverity() + ":" + i.getCode()).equals(severityColonCode)) return true;
        }
        return false;
    }

    private static boolean hasIssueAmong(List<String> rendered, String codeFragment) {
        for (String s : rendered) if (s != null && s.contains(codeFragment)) return true;
        return false;
    }

    private static int countSeverity(List<SkillIssueDto> issues, String severity) {
        int n = 0;
        for (SkillIssueDto i : issues) if (severity.equals(i.getSeverity())) n++;
        return n;
    }

    private static String describe(AggregateReportDto report) {
        return "raw=" + report.getRawCount() + " skill=" + report.getSkillCount()
                + " deduped=" + report.getDedupedCount() + " conflict=" + report.getConflictCount()
                + " issues=" + codes(report.getIssues()) + " sources=" + report.getSources();
    }
}
