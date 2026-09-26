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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code .well-known/agent-skills/index.json} 注册表索引适配器测试.
 *
 * <p>关注三件事: 索引真带的那些字段必须落到 {@link SkillDto} 的对应字段上(格式标记为 registry-index)、
 * 越界路径不能进目录、坏 JSON 只能记账不能炸链路。
 */
public class RegistryIndexNormalizerTest {

    private static final String SHARED_INDEX = "/fixtures/registry-index/.well-known/agent-skills/index.json";
    private static final String SOURCE_ID = "acme-registry";
    private static final String INDEX_URL = "https://acme.example/.well-known/agent-skills/index.json";

    private final ObjectMapper mapper = new ObjectMapper();
    private final RegistryIndexNormalizer normalizer = new RegistryIndexNormalizer(mapper);
    private final SkillSource source = new SkillSource(SOURCE_ID, SkillFormat.REGISTRY_INDEX, INDEX_URL, 10);

    // ---------- 收录共享索引里"真的带"的字段 ----------

    @Test
    public void sharedIndex_landsIndexCarriedFields_andIsDeterministic() {
        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
        List<SkillDto> out = normalizer.normalize(localRaw(SHARED_INDEX), source, issues);

        assertEquals(4, out.size(), "5 条里只有一条(空 name)该被跳过");
        assertEquals(Arrays.asList("weather-lookup", "bad-name", "path-escape", "no-skill-md"), slugs(out),
                "收录顺序必须跟索引数组一致, 否则客户端无法 diff");

        SkillDto weather = bySlug(out, "weather-lookup");
        assertEquals("weather-lookup", weather.getName());
        assertEquals("Look up current weather and forecasts.", weather.getDescription());
        assertEquals("1.0.0", weather.getVersion(), "metadata.version 要能顶上来");
        assertEquals(4210, weather.getInstallCount(), "installs 必须落到 installCount");
        assertEquals("registry-index", weather.getFormatId());
        assertEquals(SkillFormat.REGISTRY_INDEX, weather.getFormat());
        assertEquals(SOURCE_ID, weather.getSource());
        assertEquals("well-known", weather.getSourceType());
        assertEquals("registry", weather.getCategory(), "索引没写 category 且来源无 hint 时回落 registry");
        assertEquals("acme", weather.getMetadata().get("author"));
        assertNotNull(weather.getContentHash());
        assertTrue(weather.getContentHash().matches("[0-9a-f]{64}"), "contentHash 必须是 sha256 hex");
        assertEquals(Arrays.asList("SKILL.md", "scripts/fetch.py", "references/api.md"),
                resourcePaths(weather));
        assertEquals(Arrays.asList("other", "script", "reference"), resourceKinds(weather));
    }

    @Test
    public void sharedIndex_originStaysInsideServedRoot() {
        List<SkillDto> out = normalizer.normalize(localRaw(SHARED_INDEX), source, new ArrayList<SkillIssueDto>());
        String root = fixture(SHARED_INDEX).getParent().getParent().toUri().toString();
        if (!root.endsWith("/")) root = root + "/";
        for (SkillDto dto : out) {
            assertNotNull(dto.getOrigin(), "每条都要有可回溯的 origin");
            assertTrue(dto.getOrigin().startsWith(root),
                    "origin 必须落在索引目录的上层之内, 实际: " + dto.getOrigin());
            assertTrue(!dto.getOrigin().contains(".."), "origin 不得含 .. 片段: " + dto.getOrigin());
        }
    }

    @Test
    public void sharedIndex_nonConformingName_isCleanedAndWarned() {
        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
        List<SkillDto> out = normalizer.normalize(localRaw(SHARED_INDEX), source, issues);

        SkillDto cleaned = bySlug(out, "bad-name");
        assertEquals("Bad_Name", cleaned.getName(), "name 保留来源原写法, slug 才是清洗结果");
        assertEquals("bad-name", cleaned.getSlug());
        assertTrue(hasIssueWith(issues, "warning", "name-non-conforming"),
                "清洗过名字必须留 warning, 实际: " + codes(issues));
    }

    // ---------- 越界路径 ----------

    @Test
    public void sharedIndex_unsafeFiles_areRejectedButSkillSurvives() {
        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
        List<SkillDto> out = normalizer.normalize(localRaw(SHARED_INDEX), source, issues);

        SkillDto escapee = bySlug(out, "path-escape");
        assertEquals(Collections.singletonList("SKILL.md"), resourcePaths(escapee),
                "../escape.sh 与 /etc/passwd 都不能进资源清单");
        List<SkillIssueDto> unsafe = issuesWithCode(issues, "unsafe-path");
        assertEquals(2, unsafe.size(), "越界声明有两条, 该记两条 issue: " + codes(issues));
        for (SkillIssueDto issue : unsafe) {
            // 标题就是 "SkillSurvives": 进了目录的条目只能是 warning(error = 没进目录)
            assertEquals("warning", issue.getSeverity());
            assertEquals("path-escape", issue.getSkillId());
        }
        assertNoEscapingPathIn(out);
    }

    @Test
    public void entryDeclaringOnlyUnsafePaths_isRejectedInsteadOfIngested() {
        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
        List<SkillDto> out = normalizer.normalize(localRaw("/fixtures/A4/index-unsafe-paths.json"), source, issues);

        assertTrue(out.isEmpty(),
                "只声明了越界路径的条目一条都不该收录(error 级 issue 的语义就是丢弃该 Skill), 实际收了: " + slugs(out));
        assertEquals(4, issuesWithCode(issues, "unsafe-path").size(),
                "穿越/绝对/file:/越域四种写法各记一条, 实际: " + codes(issues));
        assertNoEscapingPathIn(out);
    }

    // ---------- 标识缺失 / 未知字段 ----------

    @Test
    public void missingIdentity_isSkippedWithIssue() {
        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
        List<SkillDto> out = normalizer.normalize(localRaw("/fixtures/A4/index-extra-fields.json"), source, issues);

        assertEquals(Collections.singletonList("weather-lookup"), slugs(out));
        assertTrue(hasIssueWith(issues, "error", "index-name-invalid"), "缺 name 要记 error: " + codes(issues));
        assertTrue(hasIssueWith(issues, "error", "description-missing"), "缺 description 要记 error: " + codes(issues));
        assertEquals(2, issues.size(), "健康条目不该顺带产出 issue: " + codes(issues));
    }

    @Test
    public void unknownExtraFields_areToleratedAndKnownOnesLand() {
        List<SkillDto> out = normalizer.normalize(localRaw("/fixtures/A4/index-extra-fields.json"), source,
                new ArrayList<SkillIssueDto>());
        SkillDto weather = bySlug(out, "weather-lookup");

        assertEquals("Look up current weather.", weather.getDescription(), "折行描述要压成一行");
        assertEquals("MIT", weather.getLicense());
        assertEquals("weather", weather.getCategory());
        assertEquals(Arrays.asList("travel", "http"), weather.getTags());
        assertEquals("acme-labs", weather.getAuthor());
        assertEquals("acme", weather.getOwner());
        assertEquals("1.2.3", weather.getVersion(), "顶层 version 优先于 metadata.version");
        assertEquals("infra", weather.getMetadata().get("team"), "未知的 metadata 键要原样保留");
        assertTrue(weather.getDiscoveredAt() > 0L, "discoveredAt 必须是正的时间戳");
        assertEquals(Arrays.asList("SKILL.md", "scripts/fetch.py"), resourcePaths(weather));
        assertEquals(Arrays.asList(512L, 2048L), resourceSizes(weather));
        assertTrue(weather.getContentHash().matches("[0-9a-f]{64}"));
    }

    @Test
    public void indexEpochTimestamp_isNotTruncatedTo32Bits() {
        List<SkillDto> out = normalizer.normalize(localRaw("/fixtures/A4/index-extra-fields.json"), source,
                new ArrayList<SkillIssueDto>());
        SkillDto weather = bySlug(out, "weather-lookup");

        assertEquals(1700000000000L, weather.getUpdatedAt(),
                "updatedAt 是 epoch 毫秒(long), 按 int 取值会溢出成负数, sort=updated 与上游漂移检测都会失真");
        assertTrue(weather.getUpdatedAt() > 0L, "updatedAt 必须是正的时间戳");
    }

    // ---------- 坏 JSON ----------

    @Test
    public void malformedPayload_becomesIssueNotException() {
        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
        List<SkillDto> out = normalizer.normalize(localRaw("/fixtures/A4/index-malformed.json"), source, issues);

        assertTrue(out.isEmpty());
        assertEquals(1, issues.size());
        assertEquals("index-unreadable", issues.get(0).getCode());
        assertEquals("error", issues.get(0).getSeverity());
    }

    @Test
    public void nonObjectAndEmptyPayloads_becomeIssuesNotException() throws Exception {
        List<SkillIssueDto> scalarIssues = new ArrayList<SkillIssueDto>();
        assertTrue(normalizer.normalize(localRaw("/fixtures/A4/index-scalar.json"), source, scalarIssues).isEmpty());
        assertEquals(Collections.singletonList("error:index-empty"), codes(scalarIssues));

        List<SkillIssueDto> emptyObjectIssues = new ArrayList<SkillIssueDto>();
        assertTrue(normalizer.normalize(jsonRaw("{}"), source, emptyObjectIssues).isEmpty());
        assertEquals(Collections.singletonList("error:index-empty"), codes(emptyObjectIssues));

        List<SkillIssueDto> emptyArrayIssues = new ArrayList<SkillIssueDto>();
        assertTrue(normalizer.normalize(jsonRaw("{\"skills\":[]}"), source, emptyArrayIssues).isEmpty());
        assertTrue(emptyArrayIssues.isEmpty(), "空目录是合法响应, 不该记账: " + codes(emptyArrayIssues));

        List<SkillIssueDto> nullIssues = new ArrayList<SkillIssueDto>();
        assertTrue(normalizer.normalize(jsonRaw("null"), source, nullIssues).isEmpty());
        assertEquals(Collections.singletonList("error:index-empty"), codes(nullIssues));

        List<SkillIssueDto> noPayloadIssues = new ArrayList<SkillIssueDto>();
        RawSkill nothing = new RawSkill().format(SkillFormat.REGISTRY_INDEX).origin(INDEX_URL)
                .entryRelative("nowhere/index.json").fallbackName("nowhere");
        assertTrue(normalizer.normalize(nothing, source, noPayloadIssues).isEmpty());
        assertEquals(Collections.singletonList("error:index-unreadable"), codes(noPayloadIssues));
    }

    @Test
    public void repeatedNormalization_isStable() {
        RawSkill raw = localRaw(SHARED_INDEX);
        List<SkillDto> first = normalizer.normalize(raw, source, new ArrayList<SkillIssueDto>());
        List<SkillDto> second = normalizer.normalize(localRaw(SHARED_INDEX), source, new ArrayList<SkillIssueDto>());
        assertEquals(slugs(first), slugs(second));
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).getContentHash(), second.get(i).getContentHash(),
                    "同一份索引两次解析的指纹必须一致, 否则上游漂移检测全是噪音");
        }
    }

    // ---------- helpers ----------

    private Path fixture(String classpath) {
        try {
            return Paths.get(RegistryIndexNormalizerTest.class.getResource(classpath).toURI());
        } catch (Exception e) {
            throw new IllegalStateException("fixture not found: " + classpath, e);
        }
    }

    private RawSkill localRaw(String classpath) {
        Path entry = fixture(classpath);
        return new RawSkill().format(SkillFormat.REGISTRY_INDEX).origin(entry.toUri().toString())
                .entry(entry).entryRelative(classpath.replaceFirst("^/", "")).fallbackName("index.json");
    }

    private RawSkill jsonRaw(String literal) throws Exception {
        JsonNode node = mapper.readTree(literal);
        return new RawSkill().format(SkillFormat.REGISTRY_INDEX).origin(INDEX_URL)
                .entryRelative(".well-known/agent-skills/index.json").json(node).fallbackName("inline");
    }

    private static void assertNoEscapingPathIn(List<SkillDto> out) {
        for (SkillDto dto : out) {
            for (String path : resourcePaths(dto)) {
                assertTrue(!path.startsWith("/"), "资源路径不得是绝对路径: " + path);
                assertTrue(!path.contains(".."), "资源路径不得穿越目录: " + path);
                assertTrue(!path.contains("://"), "资源路径不得是外部 URL: " + path);
            }
        }
    }

    private static List<String> slugs(List<SkillDto> in) {
        List<String> out = new ArrayList<String>();
        for (SkillDto d : in) out.add(d.getSlug());
        return out;
    }

    private static List<String> resourcePaths(SkillDto dto) {
        List<String> out = new ArrayList<String>();
        for (SkillResourceDto r : dto.getResources()) out.add(r.getPath());
        return out;
    }

    private static List<String> resourceKinds(SkillDto dto) {
        List<String> out = new ArrayList<String>();
        for (SkillResourceDto r : dto.getResources()) out.add(r.getKind());
        return out;
    }

    private static List<Long> resourceSizes(SkillDto dto) {
        List<Long> out = new ArrayList<Long>();
        for (SkillResourceDto r : dto.getResources()) out.add(r.getSizeBytes());
        return out;
    }

    private static SkillDto bySlug(List<SkillDto> in, String slug) {
        for (SkillDto d : in) {
            if (slug.equals(d.getSlug())) return d;
        }
        throw new AssertionError("没有收录 slug=" + slug + ", 实际: " + slugs(in));
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
