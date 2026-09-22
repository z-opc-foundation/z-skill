package com.zifang.z.skill.api.dto;

/**
 * Skill 安装记录(API 层 DTO).
 */
public final class SkillInstallDto {

    private final String skillName;
    private final String version;
    private final String installedBy;
    private final long installedAt;
    private final String source; // "marketplace" / "local" / "git"

    public SkillInstallDto(String skillName, String version, String installedBy, long installedAt, String source) {
        this.skillName = skillName;
        this.version = version;
        this.installedBy = installedBy;
        this.installedAt = installedAt;
        this.source = source == null ? "local" : source;
    }

    public String getSkillName() { return skillName; }
    public String getVersion() { return version; }
    public String getInstalledBy() { return installedBy; }
    public long getInstalledAt() { return installedAt; }
    public String getSource() { return source; }
}