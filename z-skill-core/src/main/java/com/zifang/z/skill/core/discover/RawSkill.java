package com.zifang.z.skill.core.discover;

import com.fasterxml.jackson.databind.JsonNode;
import com.zifang.z.skill.api.dto.SkillResourceDto;
import com.zifang.z.skill.api.spec.SkillFormat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 扫描器产出的一条"原始 Skill 候选" — 还没被归一化, 只保证知道它是什么格式、在哪里、内容是什么.
 */
public final class RawSkill {

    private SkillFormat format;
    private String origin;
    private Path root;
    private Path entry;
    private String entryRelative;
    private String dirName;
    private String text;
    private JsonNode json;
    private List<SkillResourceDto> resources = new ArrayList<SkillResourceDto>();
    private String fallbackName;

    public SkillFormat getFormat() { return format; }
    public RawSkill format(SkillFormat v) { this.format = v; return this; }

    public String getOrigin() { return origin; }
    public RawSkill origin(String v) { this.origin = v; return this; }

    public Path getRoot() { return root; }
    public RawSkill root(Path v) { this.root = v; return this; }

    public Path getEntry() { return entry; }
    public RawSkill entry(Path v) { this.entry = v; return this; }

    public String getEntryRelative() { return entryRelative; }
    public RawSkill entryRelative(String v) { this.entryRelative = v; return this; }

    public String getDirName() { return dirName; }
    public RawSkill dirName(String v) { this.dirName = v; return this; }

    public String getText() { return text; }

    public void setText(String v) { this.text = v; }
    public RawSkill text(String v) { this.text = v; return this; }

    public JsonNode getJson() { return json; }
    public RawSkill json(JsonNode v) { this.json = v; return this; }

    public List<SkillResourceDto> getResources() { return resources; }
    public RawSkill resources(List<SkillResourceDto> v) { this.resources = v == null ? new ArrayList<SkillResourceDto>() : v; return this; }

    public String getFallbackName() { return fallbackName; }
    public RawSkill fallbackName(String v) { this.fallbackName = v; return this; }
}
