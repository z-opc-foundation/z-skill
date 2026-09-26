package com.zifang.z.skill.core.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.dto.SkillIssueDto;
import com.zifang.z.skill.api.dto.SkillResourceDto;
import com.zifang.z.skill.api.spec.SkillFormat;
import com.zifang.z.skill.api.spec.SkillSpec;
import com.zifang.z.skill.core.discover.RawSkill;
import com.zifang.z.skill.core.discover.SkillSource;
import com.zifang.z.skill.core.spec.SkillFiles;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * skills.sh 风格注册表 API 适配器.
 *
 * <p>它有三副面孔, 都在这里吃掉: 列表 {@code {"data":[V1Skill]}}, 搜索 {@code {"skills":[...]}},
 * 详情 {@code {id,source,slug,installs,hash,files:[{path,contents}]}}. 字段名各版本文档里还不完全一致
 * (installs / installCount / downloads), 所以取值一律多候选.
 */
public class SkillsShApiNormalizer implements SkillNormalizer {

    private static final List<String> RISK_ORDER = Arrays.asList("none", "safe", "low", "unknown", "unscanned",
            "medium", "high", "critical");

    private final ObjectMapper mapper;

    public SkillsShApiNormalizer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public SkillFormat format() {
        return SkillFormat.SKILLS_SH_API;
    }

    @Override
    public List<SkillDto> normalize(RawSkill raw, SkillSource source, List<SkillIssueDto> issues) {
        List<SkillDto> out = new ArrayList<SkillDto>();
        JsonNode root;
        try {
            root = JsonSupport.readJson(raw, mapper);
        } catch (Exception e) {
            issues.add(SkillIssueDto.error("api-unreadable", raw.getFallbackName(), source.getId(),
                    raw.getEntryRelative(), e.getMessage()));
            return out;
        }
        JsonNode arr = JsonSupport.skillArray(root);
        if (arr == null) {
            if (root != null && root.has("slug")) {
                SkillDto one = toSkill(root, source, raw, issues);
                if (one != null) out.add(one);
                return out;
            }
            // {error, message} 是上游明确说"这次请求失败了", 记成 warning 会把一个坏掉的来源显示成"今天恰好没有 skill"
            if (root != null && (root.has("error") || root.has("message"))) {
                String code = JsonSupport.text(root, "error", "code");
                String message = JsonSupport.text(root, "message", "detail", "error_description");
                issues.add(SkillIssueDto.error("api-error", raw.getFallbackName(), source.getId(),
                        raw.getEntryRelative(), (code == null ? "" : code + ": ")
                                + (message == null ? "上游错误响应未带 message" : message)));
                return out;
            }
            issues.add(SkillIssueDto.error("api-empty", raw.getFallbackName(), source.getId(),
                    raw.getEntryRelative(), "响应里既没有 data[] 也没有 skills[]"));
            return out;
        }
        for (JsonNode node : arr) {
            SkillDto dto = toSkill(node, source, raw, issues);
            if (dto != null) out.add(dto);
        }
        return out;
    }

    private SkillDto toSkill(JsonNode node, SkillSource source, RawSkill raw, List<SkillIssueDto> issues) {
        String slugRaw = JsonSupport.text(node, "slug", "name", "skillId", "id");
        String id = JsonSupport.text(node, "id");
        String sourceRepo = JsonSupport.text(node, "source", "repo", "owner");
        String description = JsonSupport.text(node, "description", "summary", "readme", "tagline");
        String name = JsonSupport.text(node, "name", "title", "displayName");
        if (name == null) name = slugRaw;
        String slug = SkillSpec.slugify(slugRaw);
        if (slug == null) {
            // 这条 entry 真的没进目录 → error 档(见 SkillIssueDto 的口径)
            issues.add(SkillIssueDto.error("api-entry-skipped", String.valueOf(slugRaw), source.getId(),
                    raw.getEntryRelative(), "缺少可用标识"));
            return null;
        }
        if (description == null) {
            issues.add(SkillIssueDto.warning("description-missing", slug, source.getId(),
                    raw.getEntryRelative(), "API 条目没有描述, 收录但不可被语义命中"));
            description = "";
        }
        List<SkillResourceDto> resources = new ArrayList<SkillResourceDto>();
        JsonNode files = node.get("files");
        if (files != null && files.isArray()) {
            for (JsonNode f : files) {
                String path = JsonSupport.text(f, "path", "file", "name");
                if (path == null || !SkillFiles.isSafeRelativePath(path)) {
                    // 条目仍然可用(files 只是附件清单), 所以剔一个越界路径是 warning;
                    // 但这条越界声明必须留痕 —— 它是上游在试图让我们写盘到目录外
                    issues.add(SkillIssueDto.warning("unsafe-path", slug, source.getId(), String.valueOf(path),
                            "API 返回了越界文件路径, 已拒绝"));
                    continue;
                }
                resources.add(new SkillResourceDto(path.replace('\\', '/'), "other", 0L));
            }
        }
        String hash = JsonSupport.text(node, "hash", "contentHash", "sha");
        if (hash == null) {
            hash = SkillFiles.sha256(slug + "|" + description + "|" + sourceRepo);
        }
        return SkillDto.builder()
                .slug(slug)
                .name(name == null ? slug : name)
                .description(MarkdownSkillNormalizer.collapseWhitespace(description))
                .version(JsonSupport.text(node, "version"))
                .license(JsonSupport.text(node, "license"))
                .category(firstNonNull(JsonSupport.text(node, "category", "topic"), source.getCategoryHint(), "registry"))
                .tags(tagsOf(node))
                .author(firstNonNull(JsonSupport.text(node, "author"), sourceRepo))
                .owner(sourceRepo)
                .trigger(JsonSupport.text(node, "whenToUse", "when_to_use"))
                .source(source.getId())
                .sourceType(firstNonNull(JsonSupport.text(node, "sourceType"), "api"))
                .origin(firstNonNull(JsonSupport.text(node, "installUrl", "url"), id, raw.getOrigin()))
                .skillFilePath(raw.getEntryRelative())
                .format(SkillFormat.SKILLS_SH_API)
                .resources(resources)
                .contentHash(hash)
                .installCount(JsonSupport.integer(node, "installs", "installCount", "downloads", "uses"))
                .riskLevel(riskOf(node))
                .discoveredAt(System.currentTimeMillis())
                .build();
    }

    private static List<String> tagsOf(JsonNode node) {
        List<String> out = new ArrayList<String>();
        JsonNode t = node.get("tags");
        if (t == null) t = node.get("topics");
        if (t == null || t.isNull()) return out;
        if (t.isArray()) {
            for (JsonNode n : t) {
                String s = n.asText();
                if (s != null && !s.trim().isEmpty()) out.add(s.trim());
            }
        } else {
            for (String s : t.asText().split("[,;\\s]+")) {
                if (!s.trim().isEmpty()) out.add(s.trim());
            }
        }
        return out;
    }

    /** 多家安全审计取最差的一档. */
    private static String riskOf(JsonNode node) {
        String worst = knownRisk(JsonSupport.text(node, "riskLevel", "risk"));
        JsonNode audits = node.get("audits");
        if (audits != null && audits.isArray()) {
            for (JsonNode a : audits) {
                worst = worse(worst, knownRisk(JsonSupport.text(a, "riskLevel", "risk")));
            }
        }
        return worst == null ? "unscanned" : worst;
    }

    /**
     * 审计条目上的 {@code status}(pass/warn/flagged/pending) 是"这次扫描的结论", 不是风险档位 —
     * 把它当档位吃进来会让一个没扫过的 skill 顶掉别人已经报出的 critical.
     */
    private static String knownRisk(String raw) {
        if (raw == null) return null;
        String v = raw.toLowerCase(java.util.Locale.ROOT);
        return RISK_ORDER.contains(v) ? v : null;
    }

    private static String worse(String a, String b) {
        if (b == null) return a;
        if (a == null) return b;
        int ia = RISK_ORDER.indexOf(a.toLowerCase());
        int ib = RISK_ORDER.indexOf(b.toLowerCase());
        if (ia < 0) return a;
        if (ib < 0) return b;
        return ib >= ia ? b : a;
    }

    private static String firstNonNull(String... in) {
        for (String s : in) {
            if (s != null) return s;
        }
        return null;
    }
}
