package com.zifang.z.skill.api.dto;

import java.util.List;

/**
 * Skill 注册条目(API 层 DTO).
 *
 * <p>对应 kernel.skill.Skill, 加了 category + tags + installed 状态, 用于 Marketplace 列表展示.
 */
public final class SkillDto {

    private final String name;
    private final String description;
    private final String version;
    private final String category;
    private final List<String> tags;
    private final String author;
    private final int toolCount;
    private final boolean installed;
    private final String trigger;

    public SkillDto(String name, String description, String version, String category,
                    List<String> tags, String author, int toolCount, boolean installed, String trigger) {
        this.name = name;
        this.description = description == null ? "" : description;
        this.version = version == null ? "0.0.0" : version;
        this.category = category == null ? "general" : category;
        this.tags = tags == null ? java.util.Collections.emptyList() : java.util.Collections.unmodifiableList(tags);
        this.author = author == null ? "" : author;
        this.toolCount = toolCount;
        this.installed = installed;
        this.trigger = trigger;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getVersion() { return version; }
    public String getCategory() { return category; }
    public List<String> getTags() { return tags; }
    public String getAuthor() { return author; }
    public int getToolCount() { return toolCount; }
    public boolean isInstalled() { return installed; }
    public String getTrigger() { return trigger; }
}