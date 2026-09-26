package com.zifang.z.skill.core.scan;

import com.zifang.z.skill.api.dto.SkillDto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Skill 静态风险体检 — 对齐优秀市场里"多provider审计 + riskLevel"那一层, 但只做纯文本判定, 绝不执行任何东西.
 *
 * <p>为什么要这一层: 聚合别人仓库的 skill 等于把第三方指令注入自己的 agent 上下文,
 * SKILL.md 里一句 "先运行 scripts/setup.sh" 就是远程代码执行入口.
 *
 * <p>判级从严, 但对干净内容必须闭嘴: 每条规则都要能指出命中片段, 便于人核对.
 */
public class SkillSecurityScanner {

    /** 命中即 critical: 把远程内容直接交给 shell 执行. */
    private static final Rule[] CRITICAL = {
            new Rule("pipe-to-shell", "(?i)(curl|wget)[^\\n]{0,200}\\|\\s*(ba)?sh"),
            new Rule("pipe-to-shell", "(?i)(curl|wget)[^\\n]{0,200}\\|\\s*(python|perl|node|ruby)\\w*"),
            new Rule("destructive-root", "(?i)rm\\s+-[a-z]*r[a-z]*f\\s+/(\\s|$|\\*)"),
            new Rule("fork-bomb", ":\\(\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:"),
            new Rule("disk-overwrite", "(?i)\\bdd\\s+if=[^\\n]{0,80}\\s+of=/dev/"),
            new Rule("decode-and-exec", "(?i)base64\\s+(-d|--decode)[^\\n]{0,80}\\|\\s*(ba)?sh"),
    };

    private static final Rule[] HIGH = {
            new Rule("destructive-delete", "(?i)\\brm\\s+-[a-z]*r[a-z]*f\\b"),
            new Rule("credential-read", "(?i)(cat|cp|scp|tar|read)[^\\n]{0,60}(~?/\\.ssh/|id_rsa|id_ed25519)"),
            new Rule("secret-in-plainview", "(?i)(aws_secret_access_key|github_pat_|xox[baprs]-[a-z0-9]{10,}|sk-[a-z0-9]{20,})"),
            new Rule("env-file-exfil", "(?i)(cat|curl -d|scp|POST)[^\\n]{0,60}\\.env\\b"),
            new Rule("wildcard-permission", "(?i)chmod\\s+(-R\\s+)?777\\b"),
            new Rule("reverse-shell", "(?i)(nc\\s+-l|ncat\\s+-l|/dev/tcp/|socat\\s+.*exec)"),
            new Rule("tunnel-exposure", "(?i)\\b(ngrok|cloudflared\\s+tunnel)\\b"),
            new Rule("force-push", "(?i)git\\s+push[^\\n]{0,60}(--force|-f)\\s"),
            new Rule("sudo", "(?im)^\\s*sudo\\s+\\S"),
            new Rule("escalated-container", "(?i)docker\\s+run[^\\n]{0,120}(--privileged|-v\\s+/:)"),
    };

    private static final Rule[] MEDIUM = {
            new Rule("installs-third-party", "(?i)\\b(npm\\s+install\\s+-g|pip\\s+install|pip3\\s+install|brew\\s+install|cargo\\s+install)"),
            new Rule("npx-fetch-run", "(?i)\\bnpx\\s+(-y\\s+)?[a-z@]"),
            new Rule("raw-http-endpoint", "(?i)http://(?!localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0)"),
            new Rule("broad-shell-tool", null),
            new Rule("system-file-write", "(?i)(>|>>|tee\\s+)[^\\n]{0,40}(/etc/|/usr/|/var/|~/.zshrc|~/.bashrc|~/.profile)"),
            new Rule("process-kill", "(?i)\\b(killall|pkill)\\s"),
    };

    private static final Pattern BROAD_WRITE_GLOB = Pattern.compile("(?i)^(Write|Edit|Bash\\(.*\\*.*\\))$");

    /**
     * @param content skill 正文(SKILL.md 全文), 可为 null(注册表条目只有元信息)
     * @return 判级 + 命中说明
     */
    public Verdict scan(SkillDto dto, String content) {
        List<String> findings = new ArrayList<String>();
        String text = content == null ? "" : content;
        String level = "safe";
        for (Rule r : CRITICAL) {
            String hit = r.match(text);
            if (hit != null) {
                findings.add("critical:" + r.id + " -> " + snippet(hit));
                level = "critical";
            }
        }
        if (findings.isEmpty()) {
            for (Rule r : HIGH) {
                String hit = r.match(text);
                if (hit != null) {
                    findings.add("high:" + r.id + " -> " + snippet(hit));
                    level = worse(level, "high");
                }
            }
        }
        for (Rule r : MEDIUM) {
            if (r.pattern == null) continue;
            String hit = r.match(text);
            if (hit != null) {
                findings.add("medium:" + r.id + " -> " + snippet(hit));
                level = worse(level, "medium");
            }
        }
        if (dto != null) {
            Set<String> tools = new LinkedHashSet<String>(dto.getAllowedTools());
            for (String tool : tools) {
                if (BROAD_WRITE_GLOB.matcher(tool).matches() || "Bash".equalsIgnoreCase(tool)) {
                    findings.add("medium:broad-shell-tool -> allowed-tools=" + tool);
                    level = worse(level, "medium");
                    break;
                }
            }
            for (com.zifang.z.skill.api.dto.SkillResourceDto res : dto.getResources()) {
                String path = res.getPath() == null ? "" : res.getPath().toLowerCase();
                if ("script".equals(res.getKind()) && (path.endsWith(".sh") || path.endsWith(".py")
                        || path.endsWith(".js") || path.endsWith(".rb"))) {
                    findings.add("info:executes-bundled-script -> " + res.getPath());
                    break;
                }
            }
        }
        return new Verdict(level, findings);
    }

    static String snippet(String hit) {
        String s = hit.replaceAll("\\s+", " ").trim();
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    /** 取两个档位里更差的一档; 认不出的档位(null / 上游自定义值)一律让位给认得出的那个. */
    public static String worse(String a, String b) {
        List<String> order = Arrays.asList("safe", "low", "unscanned", "medium", "high", "critical");
        int ia = order.indexOf(a);
        int ib = order.indexOf(b);
        if (ia < 0) return b;
        if (ib < 0) return a;
        return ib >= ia ? b : a;
    }

    private static final class Rule {
        final String id;
        final Pattern pattern;

        Rule(String id, String regex) {
            this.id = id;
            this.pattern = regex == null ? null : Pattern.compile(regex);
        }

        String match(String text) {
            if (pattern == null) return null;
            java.util.regex.Matcher m = pattern.matcher(text);
            return m.find() ? m.group() : null;
        }
    }

    public static final class Verdict {
        private final String riskLevel;
        private final List<String> findings;

        Verdict(String riskLevel, List<String> findings) {
            this.riskLevel = riskLevel;
            this.findings = findings;
        }

        public String getRiskLevel() { return riskLevel; }
        public List<String> getFindings() { return findings; }
        public boolean isClean() { return findings.isEmpty(); }
    }
}
