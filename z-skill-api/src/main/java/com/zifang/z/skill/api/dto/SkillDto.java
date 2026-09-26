package com.zifang.z.skill.api.dto;

import com.zifang.z.skill.api.spec.SkillFormat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 归一化后的 Skill 条目 — z-skill 对"一个 Skill 长什么样"的唯一口径.
 *
 * <p>三个身份字段不要混:
 * <ul>
 *   <li>{@code name}: 来源平台原样写法(可能不合规, 如 {@code RedBookSkills}), 只用于展示</li>
 *   <li>{@code slug}: 按 Agent Skills 规范清洗过的标识 (小写 a-z0-9 与单个连字符)</li>
 *   <li>{@code id}: 聚合后的全局唯一键, 冲突时为 {@code sourceId/slug}, 注册中心按它索引</li>
 * </ul>
 *
 * <p>不可变; 通过 {@link #builder()} 构造.
 */
public final class SkillDto {

    public static final String RISK_SAFE = "safe";
    public static final String RISK_LOW = "low";
    public static final String RISK_MEDIUM = "medium";
    public static final String RISK_HIGH = "high";
    public static final String RISK_CRITICAL = "critical";
    public static final String RISK_UNSCANNED = "unscanned";

    private final String id;
    private final String slug;
    private final String name;
    private final String description;
    private final String version;
    private final String license;
    private final String compatibility;
    private final String category;
    private final List<String> tags;
    private final String author;
    private final String owner;
    private final List<String> allowedTools;
    private final List<String> paths;
    private final String trigger;
    private final String source;
    private final String sourceType;
    private final String origin;
    private final String skillFilePath;
    private final SkillFormat format;
    private final List<SkillResourceDto> resources;
    private final String contentHash;
    private final int bodyLines;
    private final int bodyChars;
    private final int installCount;
    private final boolean installed;
    private final String pinnedVersion;
    private final String riskLevel;
    private final int issueCount;
    private final long discoveredAt;
    private final long updatedAt;
    private final Map<String, String> metadata;
    private final List<String> aliases;
    private final List<String> issues;

    private SkillDto(Builder b) {
        this.id = b.id;
        this.slug = b.slug;
        this.name = b.name;
        this.description = b.description == null ? "" : b.description;
        this.version = b.version == null ? "" : b.version;
        this.license = b.license;
        this.compatibility = b.compatibility;
        this.category = b.category == null ? "general" : b.category;
        this.tags = unmodifiable(b.tags);
        this.author = b.author == null ? "" : b.author;
        this.owner = b.owner;
        this.allowedTools = unmodifiable(b.allowedTools);
        this.paths = unmodifiable(b.paths);
        this.trigger = b.trigger;
        this.source = b.source;
        this.sourceType = b.sourceType == null ? "local" : b.sourceType;
        this.origin = b.origin;
        this.skillFilePath = b.skillFilePath;
        this.format = b.format;
        this.resources = unmodifiable(b.resources);
        this.contentHash = b.contentHash;
        this.bodyLines = b.bodyLines;
        this.bodyChars = b.bodyChars;
        this.installCount = b.installCount;
        this.installed = b.installed;
        this.pinnedVersion = b.pinnedVersion;
        this.riskLevel = b.riskLevel == null ? "unscanned" : b.riskLevel;
        this.issueCount = b.issues == null ? 0 : b.issues.size();
        this.discoveredAt = b.discoveredAt;
        this.updatedAt = b.updatedAt;
        this.metadata = b.metadata == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(b.metadata));
        this.aliases = unmodifiable(b.aliases);
        this.issues = unmodifiable(b.issues);
    }

    private static <T> List<T> unmodifiable(List<T> in) {
        if (in == null || in.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<T>(in));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 以当前值为起点再改字段 — 聚合链路里 id/风险等级要分阶段回填, 没有它就得手写 27 个参数. */
    public Builder toBuilder() {
        return new Builder()
                .id(id).slug(slug).name(name).description(description).version(version).license(license)
                .compatibility(compatibility).category(category).tags(tags).author(author).owner(owner)
                .allowedTools(allowedTools).paths(paths).trigger(trigger).source(source).sourceType(sourceType)
                .origin(origin).skillFilePath(skillFilePath).format(format).resources(resources)
                .contentHash(contentHash).bodyLines(bodyLines).bodyChars(bodyChars).installCount(installCount)
                .installed(installed).pinnedVersion(pinnedVersion).riskLevel(riskLevel).discoveredAt(discoveredAt)
                .updatedAt(updatedAt).metadata(metadata).aliases(aliases).issues(issues);
    }

    public String getId() { return id; }
    public String getSlug() { return slug; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getVersion() { return version; }
    public String getLicense() { return license; }
    public String getCompatibility() { return compatibility; }
    public String getCategory() { return category; }
    public List<String> getTags() { return tags; }
    public String getAuthor() { return author; }
    public String getOwner() { return owner; }
    public List<String> getAllowedTools() { return allowedTools; }
    public List<String> getPaths() { return paths; }
    public String getTrigger() { return trigger; }
    public String getSource() { return source; }
    public String getSourceType() { return sourceType; }
    public String getOrigin() { return origin; }
    public String getSkillFilePath() { return skillFilePath; }
    public SkillFormat getFormat() { return format; }
    /** format 的字符串形式, 便于 JSON 消费方直接读. */
    public String getFormatId() { return format == null ? SkillFormat.UNKNOWN.id() : format.id(); }
    public List<SkillResourceDto> getResources() { return resources; }
    public String getContentHash() { return contentHash; }
    public int getBodyLines() { return bodyLines; }
    public int getBodyChars() { return bodyChars; }
    public int getInstallCount() { return installCount; }
    public boolean isInstalled() { return installed; }
    public String getPinnedVersion() { return pinnedVersion; }
    public String getRiskLevel() { return riskLevel; }
    public int getIssueCount() { return issueCount; }
    public long getDiscoveredAt() { return discoveredAt; }
    public long getUpdatedAt() { return updatedAt; }
    public Map<String, String> getMetadata() { return metadata; }
    public List<String> getAliases() { return aliases; }
    public List<String> getIssues() { return issues; }

    public static final class Builder {
        private String id;
        private String slug;
        private String name;
        private String description;
        private String version;
        private String license;
        private String compatibility;
        private String category;
        private List<String> tags;
        private String author;
        private String owner;
        private List<String> allowedTools;
        private List<String> paths;
        private String trigger;
        private String source;
        private String sourceType;
        private String origin;
        private String skillFilePath;
        private SkillFormat format = SkillFormat.UNKNOWN;
        private List<SkillResourceDto> resources;
        private String contentHash;
        private int bodyLines;
        private int bodyChars;
        private int installCount;
        private boolean installed;
        private String pinnedVersion;
        private String riskLevel;
        private long discoveredAt;
        private long updatedAt;
        private Map<String, String> metadata;
        private List<String> aliases;
        private List<String> issues;

        public Builder id(String v) { this.id = v; return this; }
        public Builder slug(String v) { this.slug = v; return this; }
        public Builder name(String v) { this.name = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder version(String v) { this.version = v; return this; }
        public Builder license(String v) { this.license = v; return this; }
        public Builder compatibility(String v) { this.compatibility = v; return this; }
        public Builder category(String v) { this.category = v; return this; }
        public Builder tags(List<String> v) { this.tags = v; return this; }
        public Builder author(String v) { this.author = v; return this; }
        public Builder owner(String v) { this.owner = v; return this; }
        public Builder allowedTools(List<String> v) { this.allowedTools = v; return this; }
        public Builder paths(List<String> v) { this.paths = v; return this; }
        public Builder trigger(String v) { this.trigger = v; return this; }
        public Builder source(String v) { this.source = v; return this; }
        public Builder sourceType(String v) { this.sourceType = v; return this; }
        public Builder origin(String v) { this.origin = v; return this; }
        public Builder skillFilePath(String v) { this.skillFilePath = v; return this; }
        public Builder format(SkillFormat v) { this.format = v; return this; }
        public Builder resources(List<SkillResourceDto> v) { this.resources = v; return this; }
        public Builder contentHash(String v) { this.contentHash = v; return this; }
        public Builder bodyLines(int v) { this.bodyLines = v; return this; }
        public Builder bodyChars(int v) { this.bodyChars = v; return this; }
        public Builder installCount(int v) { this.installCount = v; return this; }
        public Builder installed(boolean v) { this.installed = v; return this; }
        public Builder pinnedVersion(String v) { this.pinnedVersion = v; return this; }
        public Builder riskLevel(String v) { this.riskLevel = v; return this; }
        public Builder discoveredAt(long v) { this.discoveredAt = v; return this; }
        public Builder updatedAt(long v) { this.updatedAt = v; return this; }
        public Builder metadata(Map<String, String> v) { this.metadata = v; return this; }
        public Builder aliases(List<String> v) { this.aliases = v; return this; }
        public Builder issues(List<String> v) { this.issues = v; return this; }

        public SkillDto build() {
            return new SkillDto(this);
        }
    }
}
