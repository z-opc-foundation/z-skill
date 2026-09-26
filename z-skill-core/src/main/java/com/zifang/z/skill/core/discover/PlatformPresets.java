package com.zifang.z.skill.core.discover;

import com.zifang.z.skill.api.spec.SkillFormat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 已安装平台的自动发现 — 把"聚合各家 skill 平台"变成开箱即用.
 *
 * <p>这些落点不是编的: Agent Skills 生态的约定是 {@code .<client>/skills} 与 {@code .agents/skills},
 * Qoder 的插件装在 {@code ~/.qoder-cn/plugins}(带 installed_plugins_v2.json 清单), Cursor 的 rules 在
 * {@code .cursor/rules/*.mdc}, Copilot 的仓库指令在 {@code .github/instructions}. 本机没装的平台直接跳过,
 * 不留一个报错来源污染聚合报告.
 */
public final class PlatformPresets {

    private static final String[] CLIENTS = {"claude", "agents", "codex", "cursor", "qoder", "copilot", "gemini", "amp", "cline"};

    private PlatformPresets() {
    }

    /**
     * @param userHome 家目录, 空则取 {@code user.home}
     * @param projectRoot 项目根, 可为空
     * @param startPriority 起始优先级, 用户级比项目级低(项目覆盖用户)
     */
    public static List<SkillSource> detect(String userHome, String projectRoot, int startPriority) {
        List<SkillSource> out = new ArrayList<SkillSource>();
        String home = userHome == null || userHome.isEmpty() ? System.getProperty("user.home", "") : userHome;
        int priority = startPriority;
        if (!home.isEmpty()) {
            for (String client : CLIENTS) {
                add(out, "user-" + client, Paths.get(home, "." + client, "skills"), priority++, SkillFormat.UNKNOWN,
                        client + "-user", -1);
            }
            add(out, "user-qoder-plugins", Paths.get(home, ".qoder-cn", "plugins"), priority++,
                    SkillFormat.UNKNOWN, "qoder", 2);
            add(out, "user-qoder-skills", Paths.get(home, ".qoder-cn", "skills"), priority++, SkillFormat.UNKNOWN,
                    "qoder-user", -1);
            add(out, "user-agents-skills", Paths.get(home, ".agents", "skills"), priority++, SkillFormat.UNKNOWN,
                    "agents-user", -1);
        }
        if (projectRoot != null && !projectRoot.isEmpty()) {
            int projectPriority = Math.max(1, startPriority - 100);
            Path root = Paths.get(projectRoot);
            for (String client : CLIENTS) {
                add(out, "project-" + client, root.resolve(Paths.get("." + client, "skills")), projectPriority++,
                        SkillFormat.UNKNOWN, client + "-project", -1);
            }
            add(out, "project-agents-skills", root.resolve(Paths.get(".agents", "skills")), projectPriority++,
                    SkillFormat.UNKNOWN, "agents-project", -1);
            add(out, "project-cursor-rules", root.resolve(Paths.get(".cursor", "rules")), projectPriority++,
                    SkillFormat.CURSOR_RULES, "editor-rules", -1);
            add(out, "project-copilot", root.resolve(".github"), projectPriority++,
                    SkillFormat.COPILOT_INSTRUCTIONS, "editor-rules", -1);
            // 项目根的 AGENTS.md: 只看根这一层, 否则整个仓库会被扫穿
            add(out, "project-agents-md", root, projectPriority++, SkillFormat.AGENTS_MD, "project-instructions", 0);
        }
        return out;
    }

    private static void add(List<SkillSource> out, String id, Path dir, int priority, SkillFormat format,
                            String hint, int maxDepth) {
        if (dir == null || !Files.isDirectory(dir)) return;
        out.add(new SkillSource(id, format, dir.toAbsolutePath().toString(), priority)
                .categoryHint(hint)
                .maxDepth(maxDepth));
    }
}
