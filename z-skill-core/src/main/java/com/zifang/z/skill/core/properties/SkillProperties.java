package com.zifang.z.skill.core.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * z.skill.* 配置.
 */
@ConfigurationProperties(prefix = "z.skill")
public class SkillProperties {

    private String basePath = "";
    private boolean exposeAdmin = false;
    private String scanDir; // 启动时扫描的 Skill 目录(可选)

    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = basePath; }

    public boolean isExposeAdmin() { return exposeAdmin; }
    public void setExposeAdmin(boolean exposeAdmin) { this.exposeAdmin = exposeAdmin; }

    public String getScanDir() { return scanDir; }
    public void setScanDir(String scanDir) { this.scanDir = scanDir; }
}