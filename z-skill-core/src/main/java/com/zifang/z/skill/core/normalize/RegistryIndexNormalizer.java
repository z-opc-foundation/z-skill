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
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * {@code .well-known/agent-skills/index.json} 注册表索引适配器.
 *
 * <p>规范形状: {@code {"skills":[{"name","description","files":[...]}]}}, 要求 name 合规、files 全部相对
 * 且至少有一项以 skill.md 结尾(大小写不敏感). 这是"任何平台都能自报家门"的最低成本接口, 因此必须支持.
 */
public class RegistryIndexNormalizer implements SkillNormalizer {

    private final ObjectMapper mapper;

    public RegistryIndexNormalizer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public SkillFormat format() {
        return SkillFormat.REGISTRY_INDEX;
    }

    @Override
    public List<SkillDto> normalize(RawSkill raw, SkillSource source, List<SkillIssueDto> issues) {
        List<SkillDto> out = new ArrayList<SkillDto>();
        JsonNode root;
        try {
            root = JsonSupport.readJson(raw, mapper);
        } catch (Exception e) {
            issues.add(SkillIssueDto.error("index-unreadable", raw.getFallbackName(), source.getId(),
                    raw.getEntryRelative(), e.getMessage()));
            return out;
        }
        JsonNode arr = JsonSupport.skillArray(root);
        if (arr == null) {
            // 这份"索引"根本没有 skills 数组 → 这个候选一条都没交出来 = 被丢弃, 才是 error 档
            issues.add(SkillIssueDto.error("index-empty", raw.getFallbackName(), source.getId(),
                    raw.getEntryRelative(), "索引里没有 skills 数组"));
            return out;
        }
        if (arr.size() == 0) return out; // 空目录是合法响应, 不记账(与 skills.sh 的 data:[] 同口径)
        String base = raw.getOrigin();
        for (JsonNode node : arr) {
            String name = JsonSupport.text(node, "name", "slug", "id");
            String slug = SkillSpec.slugify(name);
            if (slug == null) {
                issues.add(SkillIssueDto.error("index-name-invalid", String.valueOf(name), source.getId(),
                        raw.getEntryRelative(), "name '" + name + "' 不符合 " + SkillSpec.NAME.pattern()));
                continue;
            }
            if (!name.equals(slug)) {
                // code 必须是有界的裸码: 把 name->slug 混进 code 会让"按码计数"的口径碎成一条一名
                issues.add(SkillIssueDto.warning("name-non-conforming", slug,
                        source.getId(), raw.getEntryRelative(), name + "->" + slug));
            }
            String description = JsonSupport.text(node, "description", "summary", "readme");
            if (description == null) {
                issues.add(SkillIssueDto.error("description-missing", slug, source.getId(),
                        raw.getEntryRelative(), "索引条目缺少 description"));
                continue;
            }
            List<SkillResourceDto> resources = new ArrayList<SkillResourceDto>();
            String skillMdPath = null;
            JsonNode files = node.get("files");
            int rejectedPaths = 0;
            if (files != null && files.isArray()) {
                for (JsonNode f : files) {
                    String path = f.isTextual() ? f.asText() : JsonSupport.text(f, "path", "file");
                    if (path == null) continue;
                    if (!SkillFiles.isSafeRelativePath(path)) {
                        // 条目本身还在(别的文件仍然可用), 所以剔单个越界路径记 warning; 全剔光才 error+丢弃
                        issues.add(SkillIssueDto.warning("unsafe-path", slug, source.getId(), path,
                                "索引声明了越界路径, 已拒绝"));
                        rejectedPaths++;
                        continue;
                    }
                    String norm = path.replace('\\', '/');
                    resources.add(new SkillResourceDto(norm, kindOf(norm), sizeOf(f)));
                    if (SkillFiles.isSkillMd(lastSegment(norm)) && skillMdPath == null) skillMdPath = norm;
                }
            }
            if (rejectedPaths > 0 && resources.isEmpty()) {
                // 宽容收录针对的是"格式脏"(缺描述、name 不合规), 不是"这条目除了越界路径什么都没有"
                issues.add(SkillIssueDto.error("index-all-paths-unsafe", slug, source.getId(),
                        raw.getEntryRelative(), "声明的 " + (resources.size() + rejectedPaths) + " 个路径全部越界, 丢弃该条目"));
                continue;
            }
            if (skillMdPath == null) {
                issues.add(SkillIssueDto.warning("index-missing-skill-md", slug, source.getId(),
                        raw.getEntryRelative(), "files 中没有 skill.md, 只能作为目录条目收录"));
            }
            JsonNode meta = node.get("metadata");
            Map<String, String> metadata = new java.util.LinkedHashMap<String, String>();
            if (meta != null && meta.isObject()) {
                for (Iterator<String> it = meta.fieldNames(); it.hasNext(); ) {
                    String k = it.next();
                    metadata.put(k, meta.get(k).asText());
                }
            }
            String version = JsonSupport.text(node, "version");
            if (version == null) version = metadata.get("version");
            out.add(SkillDto.builder()
                    .slug(slug)
                    .name(name)
                    .description(MarkdownSkillNormalizer.collapseWhitespace(description))
                    .version(version)
                    .license(JsonSupport.text(node, "license"))
                    .category(firstNonNull(JsonSupport.text(node, "category"), source.getCategoryHint(), "registry"))
                    .tags(JsonSupport.text(node, "tags") == null
                            ? splitList(node.get("tags")) : splitList(node.get("tags")))
                    .author(JsonSupport.text(node, "author"))
                    .owner(JsonSupport.text(node, "owner", "namespace"))
                    .source(source.getId())
                    .sourceType("well-known")
                    .origin(joinBase(base, skillMdPath == null ? slug + "/SKILL.md" : skillMdPath))
                    // 主文件位必须是这个 skill 自己的 SKILL.md: 索引文档自己一旦顶在这个位置上,
                    // .well-known 再吐出去时客户端会以为整个 index.json 就是该 skill 的正文
                    .skillFilePath(skillMdPath == null ? raw.getEntryRelative() : skillMdPath)
                    .format(SkillFormat.REGISTRY_INDEX)
                    .resources(resources)
                    .contentHash(SkillFiles.sha256(slug + "|" + description + "|" + (skillMdPath == null ? "" : skillMdPath)))
                    .metadata(metadata)
                    .installCount(JsonSupport.integer(node, "installs", "installCount", "downloads"))
                    .discoveredAt(System.currentTimeMillis())
                    .updatedAt(JsonSupport.longValue(node, 0L, "updatedAt", "updated_at", "lastUpdated") == 0L
                            ? System.currentTimeMillis()
                            : JsonSupport.longValue(node, 0L, "updatedAt", "updated_at", "lastUpdated"))
                    .build());
        }
        return out;
    }

    private static List<String> splitList(JsonNode node) {
        List<String> out = new ArrayList<String>();
        if (node == null || node.isNull()) return out;
        if (node.isArray()) {
            for (JsonNode n : node) {
                String s = n.asText();
                if (s != null && !s.trim().isEmpty()) out.add(s.trim());
            }
            return out;
        }
        for (String s : node.asText().split("[,;\\s]+")) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    private static long sizeOf(JsonNode file) {
        if (file != null && file.isObject()) {
            JsonNode size = file.get("size");
            if (size != null && size.isNumber()) return size.asLong();
        }
        return 0L;
    }

    private static String lastSegment(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    private static String kindOf(String path) {
        String head = path.contains("/") ? path.substring(0, path.indexOf('/')).toLowerCase() : "";
        if (head.equals("scripts") || head.equals("bin")) return "script";
        if (head.startsWith("reference")) return "reference";
        if (head.equals("assets")) return "asset";
        return "other";
    }

    private static String joinBase(String origin, String rel) {
        if (origin == null) return rel;
        int cut = origin.lastIndexOf('/');
        if (cut < 0) return rel;
        return origin.substring(0, cut + 1) + rel;
    }

    private static String firstNonNull(String... in) {
        for (String s : in) {
            if (s != null) return s;
        }
        return "general";
    }
}
