package com.zifang.z.skill.core.normalize;

import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.dto.SkillIssueDto;
import com.zifang.z.skill.api.spec.SkillFormat;
import com.zifang.z.skill.api.spec.SkillSpec;
import com.zifang.z.skill.core.discover.RawSkill;
import com.zifang.z.skill.core.discover.SkillSource;
import com.zifang.z.skill.core.spec.Frontmatter;
import com.zifang.z.skill.core.spec.SkillFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Markdown 家族的统一归一化器 — 覆盖 Agent Skills(SKILL.md)、z-skill 平铺 .md、Cursor rules(.mdc)、
 * AGENTS.md、Copilot instructions 五种写法.
 *
 * <p>它们共用 frontmatter + 正文的形状, 差别只在"名字从哪来、作用域声明在哪个键、有没有 frontmatter",
 * 因此集中一处按 {@link SkillFormat} 分支, 避免五份复制粘贴的解析器各自腐烂.
 */
public class MarkdownSkillNormalizer implements SkillNormalizer {

    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    /** 规范里的六个键 + 各家实践出来的常见扩展键, 这些之外的顶层键会进 metadata 透传. */
    private static final List<String> KNOWN_KEYS = new ArrayList<String>();

    static {
        Collections.addAll(KNOWN_KEYS, "name", "description", "license", "compatibility", "metadata",
                "allowed-tools", "allowedTools", "allowed_tools", "tools", "version", "author", "category",
                "tags", "topics", "trigger", "when_to_use", "when-to-use", "paths", "globs", "applyTo",
                "alwaysApply", "disable-model-invocation", "icon", "color", "argument-hint", "arguments",
                "description_zh", "descriptionZh", "model", "homepage", "repository", "keywords");
    }

    private final java.util.Set<SkillFormat> handled = new java.util.HashSet<SkillFormat>(
            java.util.Arrays.asList(SkillFormat.AGENT_SKILLS, SkillFormat.FLAT_MARKDOWN, SkillFormat.CURSOR_RULES,
                    SkillFormat.AGENTS_MD, SkillFormat.COPILOT_INSTRUCTIONS));

    public java.util.Set<SkillFormat> handledFormats() {
        return handled;
    }

    @Override
    public SkillFormat format() {
        return SkillFormat.AGENT_SKILLS;
    }

    @Override
    public List<SkillDto> normalize(RawSkill raw, SkillSource source, List<SkillIssueDto> issues) {
        SkillFormat format = raw.getFormat() == null ? SkillFormat.UNKNOWN : raw.getFormat();
        if (!handled.contains(format)) return Collections.emptyList();

        String text = raw.getText();
        if (text == null) {
            text = readEntry(raw, issues);
            // 回写一次, 让后续风险扫描不必再读盘
            raw.setText(text);
        }
        if (text == null) return Collections.emptyList();

        Frontmatter fm = Frontmatter.parse(text);
        for (String note : fm.issues()) {
            if (note.startsWith("error:frontmatter-missing")) {
                // 规范说 agent-skills 必须有 frontmatter, 真实仓库里大量 SKILL.md 只有正文(本仓 lead 的 10 个技能就是).
                // 聚合器的立场是"照样收录, 把不合规记账": 直接 return 空列表会让整个平台看起来是空的.
                // 记账只能是 warning — 这条确实进了目录, 记成 error 就等于自己打自己的收录数.
                if (requiresFrontmatter(format)) {
                    issues.add(SkillIssueDto.warning("frontmatter-missing", raw.getFallbackName(), source.getId(),
                            raw.getEntryRelative(), "缺少 frontmatter, 已按目录名 + 正文首标题宽松收录"));
                }
                continue;
            }
            addPrefixedIssue(issues, note, raw.getFallbackName(), source.getId(), raw.getEntryRelative());
        }

        String body = fm.body() == null ? "" : fm.body();
        String skillMdName = fm.string("name");
        String name = resolveName(format, skillMdName, raw);
        String slug = SkillSpec.slugify(name != null ? name : raw.getFallbackName());
        if (slug == null) {
            issues.add(SkillIssueDto.error("name-unsanitizeable", raw.getFallbackName(), source.getId(),
                    raw.getEntryRelative(), "无法从 '" + name + "' 得到合法标识"));
            return Collections.emptyList();
        }
        String description = resolveDescription(fm);
        if (description == null || description.trim().isEmpty()) {
            String derived = firstHeadingOrLine(body);
            if (derived != null) {
                description = derived;
                issues.add(SkillIssueDto.info("description-derived-from-body", slug, source.getId(),
                        raw.getEntryRelative(), "frontmatter 未给 description, 已取正文首个标题/首段"));
            }
        }
        String compatibility = fm.string("compatibility");

        List<String> specIssues = SkillSpec.validate(name, slug, description, compatibility);
        if (description != null && fm.foldedKeys().contains("description")
                && !specIssues.contains("info:description-multiline")) {
            // 折叠过的多行描述在 Marketplace 里读起来像一句话, 但它是从列表/段落拼出来的 — 留痕, 别静默改写
            specIssues.add("info:description-multiline");
        }
        boolean fatal = false;
        for (String issue : specIssues) {
            // 只有"连标识都给不出来"才丢弃; description 之类的不合规条目仍然收录, 让 Marketplace 能看到它脏在哪.
            boolean drops = issue.startsWith("error:") && issue.contains(":name");
            fatal |= drops;
            int colon = issue.indexOf(':');
            String bare = colon < 0 ? issue : issue.substring(colon + 1);
            int detail = bare.indexOf(':');
            // code 只能是有界的裸码: name->slug 这类取值一旦进了 code, "按码计数"就碎成一条一名
            String code = detail < 0 ? bare : bare.substring(0, detail);
            if (drops) {
                issues.add(SkillIssueDto.error(code, slug, source.getId(), raw.getEntryRelative(), issue));
            } else if (issue.startsWith("error:")) {
                // 规范层判 error 但条目照收 — 此时对外只能记 warning, 否则"进了目录"和"报错"互相打脸
                issues.add(SkillIssueDto.warning(code, slug, source.getId(), raw.getEntryRelative(),
                        issue + ", 已宽松收录"));
            } else if (!issue.startsWith("info:")) {
                issues.add(SkillIssueDto.warning(code, slug, source.getId(), raw.getEntryRelative(), issue));
            } else {
                issues.add(SkillIssueDto.info(code, slug, source.getId(), raw.getEntryRelative(), issue));
            }
        }
        if (fatal) return Collections.emptyList();

        if (format == SkillFormat.AGENT_SKILLS && skillMdName != null && raw.getDirName() != null
                && !skillMdName.equals(raw.getDirName())) {
            issues.add(SkillIssueDto.warning("name-dir-mismatch", slug, source.getId(), raw.getEntryRelative(),
                    "name=" + skillMdName + " 与目录名 " + raw.getDirName() + " 不一致"));
        }
        int bodyLines = SkillFiles.countLines(body);
        if (bodyLines > SkillSpec.BODY_LINES_SOFT_MAX) {
            issues.add(SkillIssueDto.warning("body-too-long", slug, source.getId(), raw.getEntryRelative(),
                    "正文 " + bodyLines + " 行, 超过规范建议的 " + SkillSpec.BODY_LINES_SOFT_MAX + " 行"));
        }

        Map<String, String> metadata = new LinkedHashMap<String, String>(fm.map("metadata"));
        for (Map.Entry<String, Object> e : fm.values().entrySet()) {
            if (!KNOWN_KEYS.contains(e.getKey())) {
                metadata.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }

        List<String> tags = new ArrayList<String>(fm.list("tags"));
        tags.addAll(fm.list("keywords"));
        tags.addAll(fm.list("topics"));

        List<String> allowedTools = new ArrayList<String>();
        String allowedRaw = fm.firstOf("allowed-tools", "allowedTools", "allowed_tools");
        if (allowedRaw != null) {
            allowedTools.addAll(SkillSpec.splitAllowedTools(allowedRaw));
        }
        allowedTools.addAll(fm.list("tools"));
        allowedTools.addAll(fm.list("arguments"));

        List<String> scopes = new ArrayList<String>(fm.list("paths"));
        if (scopes.isEmpty()) {
            String globs = fm.firstOf("globs", "applyTo");
            if (globs != null) {
                for (String g : globs.split("[,;\\s]+")) {
                    if (!g.trim().isEmpty()) scopes.add(g.trim());
                }
            }
        }
        if (scopes.isEmpty() && isTrue(fm.firstOf("alwaysApply", "always_apply"))) {
            // Cursor 的 alwaysApply:true 就是"这条规则全局生效"; 不落进作用域就等于把规则的含义丢掉
            scopes.add("**");
        }
        if (scopes.isEmpty() && format == SkillFormat.AGENTS_MD && raw.getEntryRelative() != null) {
            String rel = raw.getEntryRelative();
            int slash = rel.lastIndexOf('/');
            scopes.add(slash > 0 ? rel.substring(0, slash) + "/**" : "**");
        }

        String category = fm.firstOf("category", "kind", "type");
        if (category == null && source.getCategoryHint() != null) category = source.getCategoryHint();
        if (category == null) category = defaultCategory(format);
        String version = fm.firstOf("version");
        if (version == null) version = metadata.get("version");

        String riskSeed = text;
        return Collections.singletonList(SkillDto.builder()
                .slug(slug)
                .name(name == null ? slug : name)
                .description(collapseWhitespace(description))
                .version(version)
                .license(fm.string("license"))
                .compatibility(compatibility)
                .category(category)
                .tags(dedupe(tags))
                .author(firstNonBlank(fm.string("author"), metadata.get("author"), metadata.get("owner")))
                .owner(fm.firstOf("owner", "namespace"))
                .allowedTools(dedupe(allowedTools))
                .paths(scopes)
                .trigger(firstNonBlank(fm.string("trigger"), fm.string("when_to_use"), fm.string("when-to-use")))
                .source(source.getId())
                .sourceType(source.getSourceType())
                .origin(raw.getOrigin())
                .skillFilePath(raw.getEntryRelative())
                .format(format)
                .resources(raw.getResources())
                .contentHash(SkillFiles.sha256(riskSeed))
                .bodyLines(bodyLines)
                .bodyChars(body.length())
                .metadata(metadata)
                .discoveredAt(System.currentTimeMillis())
                .updatedAt(lastModified(raw))
                .build());
    }

    private static long lastModified(RawSkill raw) {
        if (raw.getEntry() == null) return System.currentTimeMillis();
        try {
            return Files.getLastModifiedTime(raw.getEntry()).toMillis();
        } catch (IOException e) {
            return System.currentTimeMillis();
        }
    }

    private String readEntry(RawSkill raw, List<SkillIssueDto> issues) {
        if (raw.getEntry() == null) {
            issues.add(SkillIssueDto.error("no-entry", raw.getFallbackName(), "-", raw.getEntryRelative(), "没有可读的条目文件"));
            return null;
        }
        try {
            long size = Files.size(raw.getEntry());
            if (size > MAX_FILE_BYTES) {
                issues.add(SkillIssueDto.error("file-too-large", raw.getFallbackName(), "-",
                        raw.getEntryRelative(), size + " bytes 超过上限"));
                return null;
            }
            return new String(Files.readAllBytes(raw.getEntry()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            issues.add(SkillIssueDto.error("read-failed", raw.getFallbackName(), "-",
                    raw.getEntryRelative(), e.getMessage()));
            return null;
        }
    }

    private static boolean requiresFrontmatter(SkillFormat format) {
        return format == SkillFormat.AGENT_SKILLS || format == SkillFormat.FLAT_MARKDOWN
                || format == SkillFormat.CURSOR_RULES || format == SkillFormat.COPILOT_INSTRUCTIONS;
    }

    /**
     * 解析器的 note 形如 {@code error:xxx} / {@code warning:xxx}. 解析阶段的 note 一律不会让条目出局
     * (真要给不出标识的条目判死刑的是后面的标识解析), 所以这里最重只能记 warning — 口径见
     * {@link SkillIssueDto}: error 的意思是"这条没进目录".
     */
    private static void addPrefixedIssue(List<SkillIssueDto> issues, String note,
                                         String skillId, String sourceId, String path) {
        int colon = note.indexOf(':');
        String severity = colon < 0 ? "warning" : note.substring(0, colon);
        String bare = colon < 0 ? note : note.substring(colon + 1);
        int detail = bare.indexOf(':');
        // 同 spec 那一支: unparsed-line:<原文> 的取值只能留在 message 里, 否则按码计数会一条一名
        String code = detail < 0 ? bare : bare.substring(0, detail);
        if ("info".equals(severity)) {
            issues.add(SkillIssueDto.info(code, skillId, sourceId, path, note));
        } else {
            issues.add(SkillIssueDto.warning(code, skillId, sourceId, path, note));
        }
    }

    /** frontmatter 里的布尔写法各家不一(yaml/字符串都见过), 只有明确为真才算真. */
    private static boolean isTrue(String raw) {
        if (raw == null) return false;
        String v = raw.trim();
        return "true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v) || "1".equals(v);
    }

    /** 没有 frontmatter 时给条目一个能看的说明: 正文首个标题(去掉井号), 否则第一段非空文本. */
    private static String firstHeadingOrLine(String body) {
        if (body == null) return null;
        boolean inFence = false;
        for (String raw : body.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("```") || line.startsWith("~~~")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) continue;
            if (line.isEmpty() || line.startsWith(">") || line.startsWith("---")) continue;
            if (line.startsWith("#")) line = line.replaceFirst("^#+\\s*", "").trim();
            line = line.replaceAll("[*_`]", "").trim();
            if (line.isEmpty()) continue;
            return line.length() > 300 ? line.substring(0, 300) : line;
        }
        return null;
    }

    /**
     * 名字来源各家不同: 规范说 SKILL.md 的 name 要与目录名一致; AGENTS.md 根本没有 name;
     * Cursor/Copilot 用文件名. 统一成"有 name 用 name, 否则回退到目录名/文件名".
     */
    private static String resolveName(SkillFormat format, String fmName, RawSkill raw) {
        if (format == SkillFormat.AGENTS_MD) {
            String dir = raw.getDirName();
            String rel = raw.getEntryRelative() == null ? "AGENTS.md" : raw.getEntryRelative();
            int slash = rel.lastIndexOf('/');
            String scope = slash > 0 ? rel.substring(0, slash) : (dir == null ? "" : dir);
            return scope.isEmpty() ? "agents-md" : scope + "-instructions";
        }
        if (fmName != null && !fmName.isEmpty()) return fmName;
        return raw.getFallbackName();
    }

    /**
     * 描述字段各家叫法不同, 但"取不到描述"这件事只有一条处理路径: 交给调用方按正文兜底并记 info.
     *
     * <p>曾经在这里给 AGENTS.md 特判一个兜底字符串 —— 那等于同一件"从正文猜描述"的动作, 换个格式就
     * 不记账了(而且猜出来的还是句常量, 检索时一条顶所有 AGENTS.md).
     */
    private static String resolveDescription(Frontmatter fm) {
        String d = fm.string("description");
        if (d != null) return d;
        return fm.firstOf("descriptionZh", "description_zh");
    }

    private static String defaultCategory(SkillFormat format) {
        switch (format) {
            case AGENTS_MD:
                return "project-instructions";
            case CURSOR_RULES:
                return "editor-rules";
            case COPILOT_INSTRUCTIONS:
                return "editor-rules";
            default:
                return "general";
        }
    }

    static String collapseWhitespace(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }

    static List<String> dedupe(List<String> in) {
        List<String> out = new ArrayList<String>();
        for (String s : in) {
            if (s == null) continue;
            String t = s.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    static String firstNonBlank(String... in) {
        for (String s : in) {
            if (s != null && !s.trim().isEmpty()) return s.trim();
        }
        return null;
    }
}
