package com.zifang.z.skill.api.dto;

/**
 * Skill 附属资源 (progressive disclosure 第三层): scripts / references / assets / 其它随包文件.
 */
public final class SkillResourceDto {

    private final String path;
    private final String kind;
    private final long sizeBytes;

    public SkillResourceDto(String path, String kind, long sizeBytes) {
        this.path = path;
        this.kind = kind == null ? "other" : kind;
        this.sizeBytes = sizeBytes;
    }

    public String getPath() { return path; }
    public String getKind() { return kind; }
    public long getSizeBytes() { return sizeBytes; }
}
