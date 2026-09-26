package com.zifang.z.skill.api.spec;

/**
 * Skill 生态里可被聚合的平台/格式.
 *
 * <p>z-skill 的定位是"聚合器": 不重新发明 Skill 定义, 而是把各家平台的 Skill 形状
 * 归一化成内部模型, 再按各家 API 形状反向输出(兼容).
 */
public enum SkillFormat {

    /** agentskills.io / Anthropic Agent Skills: {@code <name>/SKILL.md} + scripts/references/assets. */
    AGENT_SKILLS("agent-skills", "SKILL.md"),

    /** z-skill 0.1.x 遗留形状: 目录下一堆平铺 .md, frontmatter 里带 name/version/category. */
    FLAT_MARKDOWN("flat-markdown", "*.md"),

    /** Cursor Rules: {@code .cursor/rules/**&#47;*.mdc} (description / globs / alwaysApply). */
    CURSOR_RULES("cursor-rules", "*.mdc"),

    /** OpenAI Codex / 通用: AGENTS.md (无 frontmatter, 就近覆盖) + AGENTS.override.md. */
    AGENTS_MD("agents-md", "AGENTS.md"),

    /** GitHub Copilot: .github/copilot-instructions.md 与 .github/instructions/*.instructions.md (applyTo). */
    COPILOT_INSTRUCTIONS("copilot-instructions", "copilot-instructions.md"),

    /** .well-known/agent-skills/index.json 注册表索引. */
    REGISTRY_INDEX("registry-index", "index.json"),

    /** skills.sh 风格 HTTP API 返回的条目集合 (data[] / skills[]). */
    SKILLS_SH_API("skills-sh-api", "api/json"),

    /** 插件清单: Qoder installed_plugins_v2.json / .qoder-plugin/plugin.json / .cursor-plugin/marketplace.json. */
    PLUGIN_MANIFEST("plugin-manifest", "plugin.json"),

    /** 未识别 — 聚合时记 issue, 不猜. */
    UNKNOWN("unknown", "");

    private final String id;
    private final String marker;

    SkillFormat(String id, String marker) {
        this.id = id;
        this.marker = marker;
    }

    public String id() {
        return id;
    }

    public String marker() {
        return marker;
    }

    public static SkillFormat fromId(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toLowerCase().replace('_', '-');
        for (SkillFormat f : values()) {
            if (f.id.equals(v)) return f;
        }
        return null;
    }
}
