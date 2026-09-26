package com.zifang.z.skill.core.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.dto.SkillIssueDto;
import com.zifang.z.skill.api.dto.SkillResourceDto;
import com.zifang.z.skill.api.spec.SkillFormat;
import com.zifang.z.skill.core.discover.RawSkill;
import com.zifang.z.skill.core.discover.SkillSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * skills.sh 风格 HTTP API 适配器测试 — 列表 {@code data[]} / 搜索 {@code skills[]} / 详情对象三副面孔,
 * 外加错误包络 {@code {error,message}} 不能被当成"空结果成功".
 */
public class SkillsShApiNormalizerTest {

    private static final String SOURCE_ID = "skills-sh";
    private static final String API_URL = "https://skills.sh/api/v1/skills";

    private final ObjectMapper mapper = new ObjectMapper();
    private final SkillsShApiNormalizer normalizer = new SkillsShApiNormalizer(mapper);
    private final SkillSource source = new SkillSource(SOURCE_ID, SkillFormat.SKILLS_SH_API, API_URL, 20);

    // ---------- 列表包络 ----------

    @Test
    public void listEnvelope_dataArrayLandsEveryDocumentedField() {
        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
        List<SkillDto> out = normalizer.normalize(localRaw("/fixtures/skills-sh/api-list.json"), source, issues);

        assertEquals(Arrays.asList("pdf-processing", "next-cache"), slugs(out), "data[] 顺序即收录顺序");
        SkillDto pdf = bySlug(out, "pdf-processing");
        assertEquals(12000, pdf.getInstallCount(), "installs 落到 installCount");
        assertEquals("anthropics/skills", pdf.getOwner(), "owner 取 source 字段");
        assertEquals("anthropics/skills", pdf.getAuthor(), "没有 author 时回落 owner/source");
        assertEquals("https://skills.sh/anthropics/skills/pdf-processing", pdf.getOrigin(),
                "installUrl 优先于 url 作为可回溯来源");
        assertEquals("github", pdf.getSourceType());
        assertEquals("skills-sh-api", pdf.getFormatId());
        assertEquals(SOURCE_ID, pdf.getSource());
        assertEquals("", pdf.getVersion(), "API 没给版本就留空, 不许臆造");
        assertNull(pdf.getPinnedVersion(), "pinnedVersion 只在安装时回填, 收录阶段必须为空");
        assertEquals("unscanned", pdf.getRiskLevel(), "没有审计信息时必须是 unscanned");

        SkillDto next = bySlug(out, "next-cache");
        assertEquals(340, next.getInstallCount());
        assertEquals("vercel/next.js", next.getOwner());
        assertEquals("Warm the Next.js cache routes", next.getDescription());
        assertEquals("vercel/next.js/next-cache", next.getOrigin(), "没有 installUrl/url 时回落条目 id");
        assertTrue(hasIssueWith(issues, "warning", "description-missing"),
                "首条无描述的条目要记账但仍收录: " + codes(issues));
    }

    @Test
    public void listEnvelope_paginationIsCoherentWithIngestedRows() {
        List<SkillDto> out = normalizer.normalize(localRaw("/fixtures/skills-sh/api-list.json"), source,
                new ArrayList<SkillIssueDto>());
        assertEquals(2, out.size(), "pagination.total=2 与实际收进来条数一致, 控制器才能原样回吐 total/hasMore");

        List<SkillDto> paged = normalizer.normalize(localRaw("/fixtures/A4/skills-sh-list-paged.json"), source,
                new ArrayList<SkillIssueDto>());
        assertEquals(Arrays.asList("schedule", "invoice"), slugs(paged),
                "分页页里给几条就收几条, 不许把 total=5 当成待抓队列");
        assertEquals(2, paged.size());
    }

    // ---------- 搜索包络 ----------

    @Test
    public void searchEnvelope_skillArrayAndSkillIdAlias() {
        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
        List<SkillDto> out = normalizer.normalize(localRaw("/fixtures/skills-sh/api-search.json"), source, issues);

        assertEquals(Collections.singletonList("pdf-processing"), slugs(out));
        SkillDto pdf = bySlug(out, "pdf-processing");
        assertEquals(12000, pdf.getInstallCount(), "搜索形状里的 installs 同样要落到 installCount");
        assertEquals("pdf-processing", pdf.getName());
        assertEquals("anthropics/skills", pdf.getOwner());
        assertEquals("", pdf.getDescription());
        assertTrue(hasIssueWith(issues, "warning", "description-missing"), codes(issues).toString());
        assertEquals(1, issues.size(), "只该有缺描述这一条 issue: " + codes(issues));
    }

    // ---------- 详情对象 ----------

    @Test
    public void detailEnvelope_hashFilesAndWorstAudit() {
        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
        List<SkillDto> out = normalizer.normalize(localRaw("/fixtures/skills-sh/api-detail.json"), source, issues);

        assertEquals(Collections.singletonList("pdf-processing"), slugs(out), "根对象带 slug 时按单条详情收");
        SkillDto pdf = bySlug(out, "pdf-processing");
        assertEquals("sha256:abc123", pdf.getContentHash(), "详情的 hash 就是 contentHash, 不能重算");
        assertEquals(Arrays.asList("pdf-processing/SKILL.md", "pdf-processing/scripts/x.py"), resourcePaths(pdf),
                "files[] 要变成资源清单");
        assertEquals("medium", pdf.getRiskLevel(), "多家审计取最差的一档(low vs medium)");
        assertEquals("Extract PDF text and fill forms", pdf.getDescription());
        assertEquals(12000, pdf.getInstallCount());
        assertEquals("anthropics/skills/pdf-processing", pdf.getOrigin(), "详情无 url 时回落 id");
        assertTrue(issues.isEmpty(), "干净的详情不该产出 issue: " + codes(issues));
    }

    @Test
    public void detailEnvelope_withVersionAuditAndUnsafeFiles() {
        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
        List<SkillDto> out = normalizer.normalize(localRaw("/fixtures/A4/skills-sh-detail-full.json"), source, issues);

        assertEquals(Collections.singletonList("pdf-processing"), slugs(out));
        SkillDto pdf = bySlug(out, "pdf-processing");
        assertEquals("2.3.1", pdf.getVersion());
        assertNull(pdf.getPinnedVersion(), "上游不存在钉版本这回事, 收录阶段留空");
        assertEquals(42, pdf.getInstallCount());
        assertEquals("Apache-2.0", pdf.getLicense());
        assertEquals("documents", pdf.getCategory());
        assertEquals(Arrays.asList("pdf", "office"), pdf.getTags());
        assertEquals("用户要从 PDF 里取文字或填表时", pdf.getTrigger());
        assertEquals("PDF Processing", pdf.getName(), "name 取 displayName 类写法, slug 才是清洗结果");
        assertEquals("anthropics/skills", pdf.getOwner());
        assertEquals(Arrays.asList("pdf-processing/SKILL.md", "pdf-processing/scripts/split.py"),
                resourcePaths(pdf), "越界 files 必须被拒");
        assertEquals(2, issuesWithCode(issues, "unsafe-path").size(), "两条越界各记一条: " + codes(issues));
    }

    @Test
    public void auditRiskLevel_takesWorstKnownRank() {
        List<SkillDto> out = normalizer.normalize(localRaw("/fixtures/A4/skills-sh-detail-full.json"), source,
                new ArrayList<SkillIssueDto>());

        assertEquals("critical", bySlug(out, "pdf-processing").getRiskLevel(),
                "多家审计取最差的一档(low/critical), 且只带 status 的第三方扫描器不能顶掉已知档位");
    }

    // ---------- 错误包络 / 坏 JSON ----------

    @Test
    public void errorEnvelope_isReportedAsFailureNotEmptySuccess() {
        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
        List<SkillDto> out = normalizer.normalize(localRaw("/fixtures/A4/skills-sh-error.json"), source, issues);

        assertTrue(out.isEmpty());
        assertEquals(1, issues.size(), "错误包络只该记一条, 实际: " + codes(issues));
        assertEquals("error", issues.get(0).getSeverity(),
                "{error,message} 是上游明确失败, 必须记 error 级 issue 并把 message 带出来, 不能降级成空结果警告");
        assertTrue(issues.get(0).getMessage() != null && issues.get(0).getMessage().contains("skill_not_found"),
                "错误 envelope 的原文要能追到: " + issues.get(0).getMessage());
    }

    @Test
    public void malformedAndUnshapedPayloads_becomeIssuesNotException() throws Exception {
        List<SkillIssueDto> broken = new ArrayList<SkillIssueDto>();
        assertTrue(normalizer.normalize(localRaw("/fixtures/A4/skills-sh-malformed.json"), source, broken).isEmpty());
        assertEquals(Collections.singletonList("error:api-unreadable"), codes(broken));

        List<SkillIssueDto> scalar = new ArrayList<SkillIssueDto>();
        assertTrue(normalizer.normalize(jsonRaw("42"), source, scalar).isEmpty());
        assertEquals(Collections.singletonList("error:api-empty"), codes(scalar));

        List<SkillIssueDto> empty = new ArrayList<SkillIssueDto>();
        assertTrue(normalizer.normalize(jsonRaw("{\"data\":[]}"), source, empty).isEmpty());
        assertTrue(empty.isEmpty(), "空页是合法响应, 不该记账: " + codes(empty));

        List<SkillIssueDto> nameless = new ArrayList<SkillIssueDto>();
        List<SkillDto> out = normalizer.normalize(jsonRaw("{\"data\":[{\"installs\":3}]}"), source, nameless);
        assertTrue(out.isEmpty(), "没有可用标识的条目不能收: " + slugs(out));
        assertEquals(Collections.singletonList("error:api-entry-skipped"), codes(nameless));
    }

    @Test
    public void installCountAliases_andCategoryHintFallback() throws Exception {
        List<SkillDto> out = normalizer.normalize(jsonRaw(
                "{\"skills\":["
                        + "{\"slug\":\"a-count\",\"name\":\"a-count\",\"description\":\"d\",\"downloads\":11},"
                        + "{\"slug\":\"b-count\",\"name\":\"b-count\",\"description\":\"d\",\"installCount\":22},"
                        + "{\"slug\":\"c-count\",\"name\":\"c-count\",\"description\":\"d\",\"uses\":33},"
                        + "{\"slug\":\"d-count\",\"name\":\"d-count\",\"description\":\"d\"}]}"),
                source, new ArrayList<SkillIssueDto>());
        assertEquals(4, out.size());
        assertEquals(Arrays.asList(11, 22, 33, 0), installCounts(out),
                "installs / installCount / downloads / uses 四种写法都要认");

        SkillSource hinted = new SkillSource("hinted", SkillFormat.SKILLS_SH_API, API_URL, 20)
                .categoryHint("office");
        List<SkillDto> withHint = normalizer.normalize(jsonRaw(
                "{\"data\":[{\"slug\":\"titled\",\"name\":\"titled\",\"description\":\"  multi\\n line   text  \","
                        + "\"topic\":\"spreadsheet\"}]}"), hinted, new ArrayList<SkillIssueDto>());
        assertEquals("spreadsheet", bySlug(withHint, "titled").getCategory(), "category/topic 别名要认");
        assertEquals("multi line text", bySlug(withHint, "titled").getDescription(), "描述折行要压平");

        List<SkillDto> noTopic = normalizer.normalize(jsonRaw(
                "{\"data\":[{\"slug\":\"plain\",\"name\":\"plain\",\"description\":\"d\"}]}"), hinted,
                new ArrayList<SkillIssueDto>());
        assertEquals("office", bySlug(noTopic, "plain").getCategory(), "条目无 category 时用来源 hint");

        SkillSource bare = new SkillSource("bare", SkillFormat.SKILLS_SH_API, API_URL, 20);
        assertEquals("registry", bySlug(normalizer.normalize(jsonRaw(
                "{\"data\":[{\"slug\":\"plain2\",\"name\":\"plain2\",\"description\":\"d\"}]}"), bare,
                new ArrayList<SkillIssueDto>()), "plain2").getCategory(), "连 hint 都没有时回落 registry");
    }

    @Test
    public void repeatedNormalization_isStableAndFingerprintIsContentDerived() throws Exception {
        List<SkillDto> first = normalizer.normalize(jsonRaw(
                "{\"data\":[{\"slug\":\"x\",\"name\":\"x\",\"description\":\"d\",\"source\":\"acme/tools\"}]}"),
                source, new ArrayList<SkillIssueDto>());
        List<SkillDto> second = normalizer.normalize(jsonRaw(
                "{\"data\":[{\"slug\":\"x\",\"name\":\"x\",\"description\":\"d\",\"source\":\"acme/tools\"}]}"),
                source, new ArrayList<SkillIssueDto>());
        assertEquals(first.get(0).getContentHash(), second.get(0).getContentHash(),
                "没有 hash 字段时的兜底指纹必须由内容决定, 否则每次聚合都被误判成上游漂移");
        assertTrue(first.get(0).getContentHash().matches("[0-9a-f]{64}"));
        assertEquals(slugs(first), slugs(second));
    }

    // ---------- helpers ----------

    private Path fixture(String classpath) {
        try {
            return Paths.get(SkillsShApiNormalizerTest.class.getResource(classpath).toURI());
        } catch (Exception e) {
            throw new IllegalStateException("fixture not found: " + classpath, e);
        }
    }

    private RawSkill localRaw(String classpath) {
        Path entry = fixture(classpath);
        return new RawSkill().format(SkillFormat.SKILLS_SH_API).origin(API_URL).entry(entry)
                .entryRelative(classpath.replaceFirst("^/", "")).fallbackName("skills-sh");
    }

    private RawSkill jsonRaw(String literal) throws Exception {
        JsonNode node = mapper.readTree(literal);
        return new RawSkill().format(SkillFormat.SKILLS_SH_API).origin(API_URL)
                .entryRelative("https://skills.sh/api/v1/skills").json(node).fallbackName("inline");
    }

    private static List<String> slugs(List<SkillDto> in) {
        List<String> out = new ArrayList<String>();
        for (SkillDto d : in) out.add(d.getSlug());
        return out;
    }

    private static SkillDto bySlug(List<SkillDto> in, String slug) {
        for (SkillDto d : in) {
            if (slug.equals(d.getSlug())) return d;
        }
        throw new AssertionError("没有收录 slug=" + slug + ", 实际: " + slugs(in));
    }

    private static List<String> resourcePaths(SkillDto dto) {
        List<String> out = new ArrayList<String>();
        for (SkillResourceDto r : dto.getResources()) out.add(r.getPath());
        return out;
    }

    private static List<Integer> installCounts(List<SkillDto> in) {
        List<Integer> out = new ArrayList<Integer>();
        for (SkillDto d : in) out.add(d.getInstallCount());
        return out;
    }

    private static List<String> codes(List<SkillIssueDto> in) {
        List<String> out = new ArrayList<String>();
        for (SkillIssueDto i : in) out.add(i.getSeverity() + ":" + i.getCode());
        return out;
    }

    private static List<SkillIssueDto> issuesWithCode(List<SkillIssueDto> in, String needle) {
        List<SkillIssueDto> out = new ArrayList<SkillIssueDto>();
        for (SkillIssueDto i : in) {
            if (i.getCode() != null && i.getCode().contains(needle)) out.add(i);
        }
        return out;
    }

    private static boolean hasIssueWith(List<SkillIssueDto> in, String severity, String codeNeedle) {
        for (SkillIssueDto i : in) {
            if (severity.equals(i.getSeverity()) && i.getCode() != null && i.getCode().contains(codeNeedle)) {
                return true;
            }
        }
        return false;
    }
}
