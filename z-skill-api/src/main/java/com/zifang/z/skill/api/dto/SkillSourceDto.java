package com.zifang.z.skill.api.dto;

import java.util.List;

/**
 * 一个被聚合的 Skill 来源(某个平台的目录 / 某个注册表 API / 某个插件清单).
 *
 * <p>{@code format} 是来源配置里"声明"的形状, {@code observedFormats} 是扫描时"实际认出来"的形状.
 * 两者可以不一样, 而且自动发现的本机平台目录一律声明为 unknown —— 没有 observedFormats 的话,
 * "我们聚合了哪些平台形状"这个问题在最显眼的来源表上会回答"一个都没认出来".
 */
public final class SkillSourceDto {

    private final String id;
    private final String format;
    private final List<String> observedFormats;
    private final String origin;
    private final int priority;
    private final boolean enabled;
    private final String status;
    private final int skillCount;
    private final List<String> skillIds;
    private final int issueCount;
    private final List<String> issues;
    private final long durationMs;
    private final long lastRefreshAt;

    public SkillSourceDto(String id, String format, List<String> observedFormats, String origin, int priority,
                          boolean enabled, String status, int skillCount, List<String> skillIds,
                          int issueCount, List<String> issues, long durationMs, long lastRefreshAt) {
        this.id = id;
        this.format = format;
        this.observedFormats = observedFormats == null ? java.util.Collections.<String>emptyList()
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<String>(observedFormats));
        this.origin = origin;
        this.priority = priority;
        this.enabled = enabled;
        this.status = status;
        this.skillCount = skillCount;
        this.skillIds = skillIds == null ? java.util.Collections.<String>emptyList()
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<String>(skillIds));
        this.issueCount = issueCount;
        this.issues = issues == null ? java.util.Collections.<String>emptyList()
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<String>(issues));
        this.durationMs = durationMs;
        this.lastRefreshAt = lastRefreshAt;
    }

    public String getId() { return id; }
    public String getFormat() { return format; }
    public List<String> getObservedFormats() { return observedFormats; }
    public String getOrigin() { return origin; }
    public int getPriority() { return priority; }
    public boolean isEnabled() { return enabled; }
    public String getStatus() { return status; }
    public int getSkillCount() { return skillCount; }
    public List<String> getSkillIds() { return skillIds; }
    public int getIssueCount() { return issueCount; }
    public List<String> getIssues() { return issues; }
    public long getDurationMs() { return durationMs; }
    public long getLastRefreshAt() { return lastRefreshAt; }
}
