package com.zifang.z.skill.core.discover;

import com.zifang.z.skill.api.spec.SkillFormat;

/**
 * 一个被聚合的 Skill 来源: 目录(某平台在本机的安装位) 或 HTTP 端点(某平台的注册表 API).
 *
 * <p>priority 越小越权威; 同一个 slug 撞在多个来源上时, 各方 id 都带上 {@code sourceId/} 前缀(谁都不被丢弃),
 * 而"裸 slug"这个别名指向优先级最高的那家 — 聚合器的职责是"都收进来且可追溯", 不是"替用户丢掉第三方内容".
 */
public final class SkillSource {

    private String id;
    private SkillFormat format = SkillFormat.UNKNOWN;
    private String path;
    private String url;
    private int priority = 100;
    private boolean enabled = true;
    private String sourceType = "local";
    private String categoryHint;
    private int maxDepth = -1;

    public SkillSource() {
    }

    public SkillSource(String id, SkillFormat format, String pathOrUrl, int priority) {
        this.id = id;
        this.format = format;
        this.priority = priority;
        bindLocation(pathOrUrl);
    }

    /** 以 http(s):// 开头视为远端注册表, 否则当本地目录. */
    public void bindLocation(String pathOrUrl) {
        if (pathOrUrl == null) return;
        String v = pathOrUrl.trim();
        if (v.startsWith("http://") || v.startsWith("https://")) {
            this.url = v;
            this.sourceType = "api";
        } else {
            this.path = v;
            this.sourceType = "local";
        }
    }

    public boolean isRemote() {
        return url != null && !url.isEmpty();
    }

    public String getId() { return id; }
    public SkillSource id(String v) { this.id = v; return this; }

    public SkillFormat getFormat() { return format; }
    public SkillSource format(SkillFormat v) { this.format = v == null ? SkillFormat.UNKNOWN : v; return this; }

    public String getPath() { return path; }
    public SkillSource path(String v) { this.path = v; return this; }

    public String getUrl() { return url; }
    public SkillSource url(String v) { this.url = v; this.sourceType = "api"; return this; }

    public int getPriority() { return priority; }
    public SkillSource priority(int v) { this.priority = v; return this; }

    public boolean isEnabled() { return enabled; }
    public SkillSource enabled(boolean v) { this.enabled = v; return this; }

    public String getSourceType() { return sourceType; }
    public SkillSource sourceType(String v) { this.sourceType = v; return this; }

    public String getCategoryHint() { return categoryHint; }
    public SkillSource categoryHint(String v) { this.categoryHint = v; return this; }

    /** -1 表示按 {@code SkillSpec.MAX_SCAN_DEPTH}; 0 表示只看来源目录本层的文件. */
    public int getMaxDepth() { return maxDepth; }
    public SkillSource maxDepth(int v) { this.maxDepth = v; return this; }

    public String location() {
        return isRemote() ? url : path;
    }
}
