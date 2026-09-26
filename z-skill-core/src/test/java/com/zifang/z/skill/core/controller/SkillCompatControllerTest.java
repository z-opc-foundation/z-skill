package com.zifang.z.skill.core.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.dto.SkillIssueDto;
import com.zifang.z.skill.api.spec.SkillFormat;
import com.zifang.z.skill.api.spec.SkillSpec;
import com.zifang.z.skill.core.content.SkillContentReader;
import com.zifang.z.skill.core.discover.RawSkill;
import com.zifang.z.skill.core.discover.SkillSource;
import com.zifang.z.skill.core.normalize.RegistryIndexNormalizer;
import com.zifang.z.skill.core.normalize.SkillsShApiNormalizer;
import com.zifang.z.skill.core.registry.SkillRegistry;
import com.zifang.z.skill.core.search.SkillSearchEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 兼容输出层测试 — 第三方客户端( skills.sh 那套 / .well-known 注册表发现 / Qoder 插件目录)
 * 把 base 指过来时能不能不改代码就跑通. 键名即契约, 所以逐键断言.
 *
 * <p>目录内容不是手搓的: 先跑一遍真实的收录侧适配器(共享 fixture), 再要求吐出形状自洽 ——
 * 这是"聚合 + 兼容"这条产品主张的最小闭环.
 */
public class SkillCompatControllerTest {

    private static final String SH = "/skill/compat/skills-sh";
    private static final String INDEX_CP = "/fixtures/registry-index/.well-known/agent-skills/index.json";
    private static final String DETAIL_CP = "/fixtures/skills-sh/api-detail.json";
    private static final String LIST_CP = "/fixtures/skills-sh/api-list.json";
    private static final String SH_API_URL = "https://skills.sh/api/v1/skills";
    private static final String SH_SOURCE = "skills-sh";
    private static final String INDEX_SOURCE = "acme-registry";

    private final ObjectMapper mapper = new ObjectMapper();
    private SkillRegistry registry;
    private MockMvc mvc;

    @BeforeEach
    public void ingestThenExpose() throws Exception {
        registry = new SkillRegistry();
        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
        SkillSource indexSource = new SkillSource(INDEX_SOURCE, SkillFormat.REGISTRY_INDEX,
                "https://acme.example/.well-known/agent-skills/index.json", 10);
        RawSkill indexRaw = new RawSkill().format(SkillFormat.REGISTRY_INDEX)
                .entry(fixture(INDEX_CP)).entryRelative(".well-known/agent-skills/index.json")
                .origin(fixture(INDEX_CP).toUri().toString()).fallbackName("index.json");
        for (SkillDto dto : new RegistryIndexNormalizer(mapper).normalize(indexRaw, indexSource, issues)) {
            String id = "weather-lookup".equals(dto.getSlug()) ? indexSource.getId() + "/" + dto.getSlug()
                    : dto.getSlug();
            registry.register(dto.toBuilder().id(id).build());
        }

        SkillSource shSource = new SkillSource(SH_SOURCE, SkillFormat.SKILLS_SH_API, SH_API_URL, 20);
        RawSkill detail = new RawSkill().format(SkillFormat.SKILLS_SH_API).entry(fixture(DETAIL_CP))
                .origin(SH_API_URL + "/pdf-processing").entryRelative(SH_API_URL + "/pdf-processing")
                .fallbackName("detail");
        RawSkill list = new RawSkill().format(SkillFormat.SKILLS_SH_API).entry(fixture(LIST_CP))
                .origin(SH_API_URL).entryRelative(SH_API_URL).fallbackName("list");
        SkillsShApiNormalizer sh = new SkillsShApiNormalizer(mapper);
        for (SkillDto dto : sh.normalize(detail, shSource, issues)) {
            registry.register(dto.toBuilder().id(dto.getSlug()).build());
        }
        for (SkillDto dto : sh.normalize(list, shSource, issues)) {
            if (registry.get(dto.getSlug()).isPresent()) continue; // next-cache 留在下面补
            registry.register(dto.toBuilder().id(dto.getSlug()).build());
        }
        // 收录阶段就把 pdf-processing 装上, 让"已安装"的条目也能被外部形状吐出来
        registry.install("pdf-processing", "tester", "marketplace");
        mvc = mvc(registry);
    }

    private MockMvc mvc(SkillRegistry target) {
        return MockMvcBuilders.standaloneSetup(new SkillCompatController(target, new SkillSearchEngine(target),
                new SkillContentReader()))
                .setControllerAdvice(new SkillErrorAdvice())
                .build();
    }

    // ---------- skills.sh 搜索形状 ----------

    @Test
    public void shSearch_keepsSkillsShKeys() throws Exception {
        mvc.perform(get(SH + "/api/search").param("q", "pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.query").value("pdf"))
                .andExpect(jsonPath("$.searchType").value("fuzzy"))
                .andExpect(jsonPath("$.searchVersion").value("z-skill"))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.skills.length()").value(1))
                .andExpect(jsonPath("$.skills[0].id").value(SH_SOURCE + "/pdf-processing"))
                .andExpect(jsonPath("$.skills[0].source").value(SH_SOURCE))
                .andExpect(jsonPath("$.skills[0].skillId").value("pdf-processing"))
                .andExpect(jsonPath("$.skills[0].name").value("pdf-processing"))
                .andExpect(jsonPath("$.skills[0].installs").value(12000))
                .andExpect(jsonPath("$.duration_ms").isNumber());

        int browseCount = registry.skillCount();
        mvc.perform(get(SH + "/api/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value(""))
                .andExpect(jsonPath("$.searchType").value("browse"))
                .andExpect(jsonPath("$.count").value(browseCount));
        mvc.perform(get(SH + "/api/search"))
                .andExpect(jsonPath("$.duration_ms").isNumber())
                .andExpect(jsonPath("$.count").value(browseCount));

        mvc.perform(get(SH + "/api/search").param("limit", "2").param("q", "a"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(SH + "/api/search").param("limit", "2"))
                .andExpect(jsonPath("$.skills.length()").value(2))
                .andExpect(jsonPath("$.skills[0].id").value(SH_SOURCE + "/pdf-processing")); // 按 installs 倒序
    }

    @Test
    public void shSearch_ownerFilterMapsToSource() throws Exception {
        // owner → 来源过滤: 那份索引交出的 4 条(不含没有 name 的那条)都归 acme-registry
        mvc.perform(get(SH + "/api/search").param("owner", INDEX_SOURCE))
                .andExpect(jsonPath("$.skills.length()").value(4))
                .andExpect(jsonPath("$.count").value(4))
                .andExpect(jsonPath("$.skills[*].source")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(INDEX_SOURCE))))
                .andExpect(jsonPath("$.skills[0].id").value(INDEX_SOURCE + "/weather-lookup"));
        mvc.perform(get(SH + "/api/search").param("owner", "nobody"))
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.skills").isEmpty());
    }

    // ---------- skills.sh 列表 / 详情 / 审计 ----------

    @Test
    public void shList_dataAndPaginationAreCoherent() throws Exception {
        int total = registry.skillCount();
        mvc.perform(get(SH + "/api/v1/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(total))
                .andExpect(jsonPath("$.pagination.page").value(0))
                .andExpect(jsonPath("$.pagination.perPage").value(100))
                .andExpect(jsonPath("$.pagination.total").value(total))
                .andExpect(jsonPath("$.pagination.hasMore").value(false))
                .andExpect(jsonPath("$.data[0].id").value(SH_SOURCE + "/pdf-processing"))
                .andExpect(jsonPath("$.data[0].slug").value("pdf-processing"))
                .andExpect(jsonPath("$.data[0].installs").value(12000))
                .andExpect(jsonPath("$.data[0].sourceType").value("github"))
                .andExpect(jsonPath("$.data[0].installUrl").value(
                        org.hamcrest.Matchers.notNullValue()))
                .andExpect(jsonPath("$.data[0].url").value(org.hamcrest.Matchers.notNullValue()));

        mvc.perform(get(SH + "/api/v1/skills").param("per_page", "2"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.pagination.perPage").value(2))
                .andExpect(jsonPath("$.pagination.total").value(total))
                .andExpect(jsonPath("$.pagination.hasMore").value(true));
        mvc.perform(get(SH + "/api/v1/skills").param("per_page", "2").param("page", "1"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.pagination.page").value(1))
                .andExpect(jsonPath("$.pagination.hasMore").value(true));
        mvc.perform(get(SH + "/api/v1/skills").param("per_page", "2").param("page", "2"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.pagination.hasMore").value(false));
        mvc.perform(get(SH + "/api/v1/skills").param("view", "hot"))
                .andExpect(jsonPath("$.data.length()").value(total));
    }

    @Test
    public void shList_orderIsStableEnoughToDiff() throws Exception {
        List<String> expected = Arrays.asList(SH_SOURCE + "/pdf-processing", INDEX_SOURCE + "/weather-lookup",
                SH_SOURCE + "/next-cache", INDEX_SOURCE + "/bad-name", INDEX_SOURCE + "/no-skill-md",
                INDEX_SOURCE + "/path-escape");
        // 同样的目录连拉两次必须逐条同序, 否则客户端 diff 全是噪音
        // (逐下标断言: jsonpath 的 [*] 投影在 Spring 的 value() 比较里不可靠, 会把差异打印成 null)
        assertOrder(expected);
        assertOrder(expected);
        // installs 打平时按 id 升序兜底, 不允许退化成 HashMap 顺序
        assertOrder(expected.subList(3, 6), "per_page", "3", "page", "1");
    }

    @Test
    public void shDetail_resolvesTheSlashIdItAdvertises() throws Exception {
        // 列表/详情吐的 id 一律是 source/slug; 客户端拿它回填详情, 所以带斜杠的写法必须能路由
        mvc.perform(get(SH + "/api/v1/skills/" + SH_SOURCE + "/pdf-processing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("pdf-processing"));
        mvc.perform(get(SH + "/api/v1/skills/audit/" + INDEX_SOURCE + "/weather-lookup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("weather-lookup"));
    }

    @Test
    public void shDetail_hashFilesAndRiskLevel() throws Exception {
        mvc.perform(get(SH + "/api/v1/skills/pdf-processing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(SH_SOURCE + "/pdf-processing"))
                .andExpect(jsonPath("$.hash").value("sha256:abc123"))
                .andExpect(jsonPath("$.description").value("Extract PDF text and fill forms"))
                .andExpect(jsonPath("$.riskLevel").value("medium"))
                .andExpect(jsonPath("$.slug").value("pdf-processing"))
                // files[0] 必须是这条 skill 自己的主文件: 客户端拿它当正文位置
                .andExpect(jsonPath("$.files.length()").value(3))
                .andExpect(jsonPath("$.files[0].path").value("pdf-processing/SKILL.md"))
                .andExpect(jsonPath("$.files[0].contents").doesNotExist())
                .andExpect(jsonPath("$.files[1].path").isString());
        mvc.perform(get(SH + "/api/v1/skills/pdf-processing").param("files", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files.length()").value(3))
                // contents 只挂在主文件那一条上, 其余文件只给路径
                .andExpect(jsonPath("$.files[1].contents").doesNotExist())
                .andExpect(jsonPath("$.files[2].contents").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"contents\"")));
        mvc.perform(get(SH + "/api/v1/skills/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("skill_not_found"))
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    public void shAudit_isProviderArrayWithRiskLevel() throws Exception {
        mvc.perform(get(SH + "/api/v1/skills/audit/pdf-processing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(SH_SOURCE + "/pdf-processing"))
                .andExpect(jsonPath("$.source").value(SH_SOURCE))
                .andExpect(jsonPath("$.slug").value("pdf-processing"))
                .andExpect(jsonPath("$.audits.length()").value(1))
                .andExpect(jsonPath("$.audits[0].provider").value("z-skill-static"))
                .andExpect(jsonPath("$.audits[0].slug").value("pdf-processing"))
                .andExpect(jsonPath("$.audits[0].status").value("warn"))
                .andExpect(jsonPath("$.audits[0].riskLevel").value("MEDIUM")) // 外部字典是大写档位
                .andExpect(jsonPath("$.audits[0].summary").isString())
                .andExpect(jsonPath("$.audits[0].auditedAt").isString()); // ISO-8601
        mvc.perform(get(SH + "/api/v1/skills/audit/weather-lookup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audits[0].status").value("pending")) // unscanned 不能谎报 passed
                .andExpect(jsonPath("$.audits[0].riskLevel").doesNotExist()); // 没扫过就不给档位, 而不是编一个
        mvc.perform(get(SH + "/api/v1/skills/audit/ghost")).andExpect(status().isNotFound());
    }

    // ---------- .well-known 注册表发现 ----------

    @Test
    public void wellKnownIndex_isSelfConsistentWithRegistry() throws Exception {
        mvc.perform(get("/.well-known/agent-skills/index.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.skills.length()").value(registry.skillCount()))
                .andExpect(jsonPath("$.total").value(registry.skillCount()))
                .andExpect(jsonPath("$.generatedAt").isNumber());
        // 中文描述必须原样吐回来: MockHttpServletResponse 默认按 ISO-8859-1 解码, 所以要显式按 UTF-8 读
        String utf8 = mvc.perform(get("/.well-known/agent-skills/index.json"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(utf8.contains("files \u91cc\u6ca1\u6709 skill.md"), "响应编码把第三方中文描述弄坏了: " + utf8);

        JsonNode root = wellKnownRoot();
        List<String> names = new ArrayList<String>();
        for (JsonNode node : root.get("skills")) {
            names.add(node.get("name").asText());
            assertTrue(node.has("description"), "索引条目必须带 description: " + node.get("name"));
            assertTrue(node.get("files").isArray() && node.get("files").size() > 0,
                    "files 必须是非空数组: " + node.get("name"));
            for (JsonNode f : node.get("files")) {
                String path = f.asText();
                assertTrue(SkillSpec.NAME.matcher(node.get("name").asText()).matches(),
                        "索引 name 必须合规(Agent Skills 硬约束): " + node.get("name"));
                assertTrue(!path.startsWith("/"), "路径不得绝对: " + path);
                assertTrue(!path.contains(".."), "路径不得越界: " + path);
                assertTrue(!path.contains("://"), "路径不得是外部 URL: " + path);
            }
        }
        assertEquals(namesInRegistryOrder(), names, "吐出的条目必须与注册中心一一对应且同序");
        assertEquals(registry.skillCount(), new java.util.LinkedHashSet<String>(names).size(),
                "name 撞车会让客户端装错东西");
        mvc.perform(get("/.well-known/skills/index.json"))
                .andExpect(jsonPath("$.total").value(registry.skillCount()));
        mvc.perform(get(SH + "/.well-known/agent-skills/index.json"))
                .andExpect(jsonPath("$.total").value(registry.skillCount()));
    }

    @Test
    public void wellKnownIndex_entryAdvertisesItsOwnSkillMdPath() throws Exception {
        JsonNode root = wellKnownRoot();
        for (JsonNode node : root.get("skills")) {
            String first = node.get("files").get(0).asText();
            assertTrue(first.toLowerCase().endsWith("skill.md"),
                    node.get("name").asText() + " 的主文件位被吐成 " + first
                            + " —— 那是索引文档自己, 不是这个 skill 的 SKILL.md");
        }
    }

    @Test
    public void wellKnownIndex_carriesVersionMetadataWhenKnown() throws Exception {
        mvc.perform(get("/.well-known/agent-skills/index.json"))
                .andExpect(jsonPath("$['skills'][?(@.name == 'weather-lookup')].metadata.version")
                        .value(org.hamcrest.Matchers.hasItem("1.0.0"))) // 拿不到的不许臆造
                .andExpect(jsonPath("$['skills'][?(@.name == 'weather-lookup')].metadata.source")
                        .value(org.hamcrest.Matchers.hasItem(INDEX_SOURCE)));
    }

    // ---------- Qoder 插件目录形状 ----------

    @Test
    public void qoderSkills_basicShape() throws Exception {
        mvc.perform(get("/skill/compat/qoder/skills"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(registry.skillCount()))
                .andExpect(jsonPath("$[0].name").isString())
                .andExpect(jsonPath("$[0].description").isString())
                .andExpect(jsonPath("$[0].location").isString())
                .andExpect(jsonPath("$[0].source").isString());
    }

    @Test
    public void qoderSkills_namesPluginSkillsWithExtensionId() throws Exception {
        SkillRegistry pluginRegistry = new SkillRegistry();
        pluginRegistry.register(SkillDto.builder()
                .id("hello").slug("hello").name("hello")
                .description("Say hello from a Qoder plugin")
                .source("qoder-plugin@qoder-local").sourceType("plugin")
                .origin("file:///opt/qoder/plugins/qoder-plugin/skills/hello/SKILL.md")
                .skillFilePath("hello/SKILL.md")
                .format(SkillFormat.AGENT_SKILLS).contentHash("hash-hello").category("plugin")
                .build());
        MockMvc target = mvc(pluginRegistry);
        target.perform(get("/skill/compat/qoder/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].location").value("hello/SKILL.md"))
                .andExpect(jsonPath("$[0].name").value("qoder-plugin:hello")); // Qoder 写法: <extensionId>:<skillName>
    }

    /**
     * 兼容面是"把 z-skill 当成一个 skill 平台来装"的那道门: 客户端拿到的是第三方形状, 里面混进
     * {@code file:///...} 等于把宿主机的用户名与目录布局发给一个不认识我们的系统.
     *
     * <p>夹具两条: 本机来源那条的 origin 是真 file: URI(给"必须抹"提供猎物, 而且文件真在磁盘上,
     * 所以 {@code ?files=true} 那条会真去读盘), 远端来源那条给"不许多抹"提供猎物.
     */
    @Test
    public void everyCompatExitCarriesNoHostPathForALocalOrigin() throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("z-skill-compat-leak");
        Path entry = dir.resolve("hello").resolve("SKILL.md");
        try {
            java.nio.file.Files.createDirectories(entry.getParent());
            java.nio.file.Files.write(entry, "# hello\n\nsay hi\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            SkillRegistry local = new SkillRegistry();
            local.register(SkillDto.builder().id("hello").slug("hello").name("hello")
                    .description("Say hello from a Qoder plugin").version("1.0.0")
                    .source("qoder-plugin@qoder-local").sourceType("plugin")
                    .origin(entry.toUri().toString()).skillFilePath("hello/SKILL.md")
                    .format(SkillFormat.AGENT_SKILLS).contentHash("hash-hello").category("plugin")
                    .resources(Collections.singletonList(
                            new com.zifang.z.skill.api.dto.SkillResourceDto("references/note.md", "reference", 8L)))
                    .build());
            local.register(SkillDto.builder().id(INDEX_SOURCE + "/weather-lookup").slug("weather-lookup")
                    .name("weather-lookup").description("Look up weather")
                    .source(INDEX_SOURCE).sourceType("registry")
                    .origin("https://acme.example/.well-known/agent-skills/weather-lookup/SKILL.md")
                    .skillFilePath("weather-lookup/SKILL.md")
                    .format(SkillFormat.AGENT_SKILLS).contentHash("hash-weather").category("tools")
                    .build());
            // 前置: 两条夹具都得真在账上, 否则下面的"没有路径"是空跑
            assertTrue(local.get("hello").get().getOrigin().startsWith("file:"), "本机夹具没备好");
            MockMvc target = mvc(local);

            List<String> exits = Arrays.asList(
                    SH + "/api/search",
                    SH + "/api/search?q=hello",
                    SH + "/api/v1/skills",
                    SH + "/api/v1/skills/hello",
                    SH + "/api/v1/skills/hello?files=true",
                    SH + "/api/v1/skills/audit/hello",
                    "/.well-known/agent-skills/index.json",
                    "/.well-known/skills/index.json",
                    SH + "/.well-known/agent-skills/index.json",
                    "/skill/compat/qoder/skills");
            int ok = 0;
            for (String exit : exits) {
                String body = target.perform(get(exit)).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                ok++;
                assertTrue(!body.contains("file:"), exit + " 把 file: 地址发给了外部形状: " + body);
                assertTrue(!body.contains(dir.toString()), exit + " 把本机目录发给了外部形状: " + body);
            }
            assertEquals(exits.size(), ok, "有出口没跑到, 剩下的白测");
            // 本机那条正文预览确实给了内容(否则 ?files=true 只是空转, 上面两条断言测不到读盘分支)
            String withContents = target.perform(get(SH + "/api/v1/skills/hello?files=true"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(withContents.contains("say hi"), "?files=true 没交出正文, 读盘分支未被检: " + withContents);

            String remote = target.perform(get(SH + "/api/v1/skills/weather-lookup"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(remote.contains("https://acme.example/.well-known/agent-skills/weather-lookup/SKILL.md"),
                    "远端来源的 installUrl 被一起抹了: " + remote);
        } finally {
            try {
                java.nio.file.Files.deleteIfExists(entry);
                java.nio.file.Files.deleteIfExists(dir);
            } catch (java.io.IOException ignored) {
                // 临时目录交给系统回收, 不该盖掉断言结果
            }
        }
    }

    // ---------- helpers ----------

    /**
     * 直接取控制器吐出的索引对象再走一遍 Jackson 序列化.
     *
     * <p>这里不读 MockMvc 响应体: z-skill-core 的测试类路径上没有 javax.servlet, 读字节会编译不过,
     * 而逐条目断言要的是同一条数据(响应体只做中文编码那一条断言).
     */
    /**
     * 逐条比对 {@code data[].id} —— 不用 {@code $[*]} 投影配 value():
     * 顺序不一致时 Spring 会把实际值打成 null, 差在哪看不见.
     */
    private void assertOrder(List<String> ids, String... params) throws Exception {
        org.springframework.util.MultiValueMap<String, String> q =
                new org.springframework.util.LinkedMultiValueMap<String, String>();
        for (int i = 0; i + 1 < params.length; i += 2) q.add(params[i], params[i + 1]);
        String body = mvc.perform(get(SH + "/api/v1/skills").params(q))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        List<String> actual = new ArrayList<String>();
        for (JsonNode n : mapper.readTree(body).get("data")) actual.add(n.get("id").asText());
        org.junit.jupiter.api.Assertions.assertEquals(ids, actual);
    }

    private JsonNode wellKnownRoot() throws Exception {
        Map<String, Object> body = new SkillCompatController(registry, new SkillSearchEngine(registry),
                new SkillContentReader()).wellKnownIndex();
        return mapper.readTree(mapper.writeValueAsString(body));
    }

    private List<String> namesInRegistryOrder() {
        List<String> out = new ArrayList<String>();
        for (SkillDto dto : registry.listAll()) out.add(dto.getSlug());
        return out;
    }

    private Path fixture(String classpath) {
        try {
            return Paths.get(SkillCompatControllerTest.class.getResource(classpath).toURI());
        } catch (Exception e) {
            throw new IllegalStateException("fixture not found: " + classpath, e);
        }
    }

    private static void assertTrue(boolean v, String msg) {
        org.junit.jupiter.api.Assertions.assertTrue(v, msg);
    }

    private static void assertTrue(boolean v) {
        org.junit.jupiter.api.Assertions.assertTrue(v);
    }

    private static void assertEquals(Object a, Object b, String msg) {
        org.junit.jupiter.api.Assertions.assertEquals(a, b, msg);
    }

    private static void assertEquals(Object a, Object b) {
        org.junit.jupiter.api.Assertions.assertEquals(a, b);
    }
}
