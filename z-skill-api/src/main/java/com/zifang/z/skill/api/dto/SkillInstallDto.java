package com.zifang.z.skill.api.dto;

/**
 * 安装记录.
 *
 * <p>{@code contentHash} 是更新检测的抓手: 再聚合一次后 hash 变了就说明上游漂移了.
 */
public final class SkillInstallDto {

    private final String skillId;
    private final String skillName;
    private final String version;
    private final String installedBy;
    private final long installedAt;
    private final String source;
    private final String sourceId;
    private final String contentHash;
    private final String installRef;

    public SkillInstallDto(String skillId, String skillName, String version, String installedBy, long installedAt,
                           String source, String sourceId, String contentHash, String installRef) {
        this.skillId = skillId;
        this.skillName = skillName;
        this.version = version == null ? "" : version;
        this.installedBy = installedBy;
        this.installedAt = installedAt;
        this.source = source == null ? "local" : source;
        this.sourceId = sourceId;
        this.contentHash = contentHash;
        this.installRef = installRef;
    }

    public String getSkillId() { return skillId; }
    public String getSkillName() { return skillName; }
    public String getVersion() { return version; }
    public String getInstalledBy() { return installedBy; }
    public long getInstalledAt() { return installedAt; }
    public String getSource() { return source; }
    public String getSourceId() { return sourceId; }
    public String getContentHash() { return contentHash; }
    /** 安装来源的不透明引用(file:// 路径 或 owner/repo@skill), 消费方不得自行拼接 URL. */
    public String getInstallRef() { return installRef; }
}
