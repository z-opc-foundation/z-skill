package com.zifang.z.skill.core.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * z.skill.* 配置.
 *
 * <pre>{@code
 * z:
 *   skill:
 *     enabled: true
 *     refresh-on-startup: true
 *     discover-installed-platforms: true   # 自动挂上本机装过的 claude/cursor/codex/qoder 目录
 *     sources:
 *       - id: z-opc-doc-skills
 *         format: agent-skills
 *         path: /abs/path/z-opc/_doc/005_skills
 *         priority: 10
 *       - id: company-registry
 *         format: registry-index
 *         url: https://skills.example.com/.well-known/agent-skills/index.json
 * }</pre>
 */
@ConfigurationProperties(prefix = "z.skill")
public class SkillProperties {

    private boolean enabled = true;
    private boolean refreshOnStartup = true;
    private boolean exposeAdmin = false;
    private boolean discoverInstalledPlatforms = true;
    private String projectRoot = "";
    private String userHome = "";
    private int maxDepth = 6;
    private boolean scanRemoteOnStartup = false;
    private List<Source> sources = new ArrayList<Source>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isRefreshOnStartup() { return refreshOnStartup; }
    public void setRefreshOnStartup(boolean v) { this.refreshOnStartup = v; }

    public boolean isExposeAdmin() { return exposeAdmin; }
    public void setExposeAdmin(boolean v) { this.exposeAdmin = v; }

    public boolean isDiscoverInstalledPlatforms() { return discoverInstalledPlatforms; }
    public void setDiscoverInstalledPlatforms(boolean v) { this.discoverInstalledPlatforms = v; }

    public String getProjectRoot() { return projectRoot; }
    public void setProjectRoot(String v) { this.projectRoot = v; }

    public String getUserHome() { return userHome; }
    public void setUserHome(String v) { this.userHome = v; }

    public int getMaxDepth() { return maxDepth; }
    public void setMaxDepth(int v) { this.maxDepth = v; }

    public boolean isScanRemoteOnStartup() { return scanRemoteOnStartup; }
    public void setScanRemoteOnStartup(boolean v) { this.scanRemoteOnStartup = v; }

    public List<Source> getSources() { return sources; }
    public void setSources(List<Source> v) { this.sources = v == null ? new ArrayList<Source>() : v; }

    /** 一个来源的声明. */
    public static class Source {
        private String id;
        private String format;
        private String path;
        private String url;
        private int priority = 100;
        private boolean enabled = true;
        private String category;
        private int maxDepth;

        public String getId() { return id; }
        public void setId(String v) { this.id = v; }

        public String getFormat() { return format; }
        public void setFormat(String v) { this.format = v; }

        public String getPath() { return path; }
        public void setPath(String v) { this.path = v; }

        public String getUrl() { return url; }
        public void setUrl(String v) { this.url = v; }

        public int getPriority() { return priority; }
        public void setPriority(int v) { this.priority = v; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }

        public String getCategory() { return category; }
        public void setCategory(String v) { this.category = v; }

        public int getMaxDepth() { return maxDepth; }
        public void setMaxDepth(int v) { this.maxDepth = v; }
    }
}
