package com.zifang.z.skill.core.spec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文件侧的杂项工具: 读文本、算内容指纹、安全相对路径、大小写不敏感找 SKILL.md.
 */
public final class SkillFiles {

    /** 各家 skill 包里常见的说明性旁文件, 不能当成 skill 本体. */
    private static final List<String> NON_SKILL_MD = new ArrayList<String>();

    static {
        NON_SKILL_MD.add("readme.md");
        NON_SKILL_MD.add("changelog.md");
        NON_SKILL_MD.add("license.md");
        NON_SKILL_MD.add("licence.md");
        NON_SKILL_MD.add("contributing.md");
        NON_SKILL_MD.add("code_of_conduct.md");
        NON_SKILL_MD.add("security.md");
        NON_SKILL_MD.add("agents.md");
        NON_SKILL_MD.add("claude.md");
        NON_SKILL_MD.add("index.md");
        NON_SKILL_MD.add("skill.md");
    }

    private SkillFiles() {
    }

    public static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    /** SHA-256 十六进制; 用于跨平台去重与上游漂移检测. */
    public static String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static boolean isSkillMd(String fileName) {
        return "skill.md".equals(fileName.toLowerCase());
    }

    /** 该 .md 是否"看起来像个 skill 条目"(平铺格式下要排除 README 之类的旁文件). */
    public static boolean isLooseSkillMd(String fileName) {
        String f = fileName.toLowerCase();
        if (!f.endsWith(".md")) return false;
        return !NON_SKILL_MD.contains(f);
    }

    /** 正向斜杠的相对路径, 越界(绝对路径 / ..)返回 null. */
    public static String relativize(Path root, Path target) {
        try {
            Path r = root.toAbsolutePath().normalize();
            Path t = target.toAbsolutePath().normalize();
            if (!t.startsWith(r)) return null;
            return slashify(r.relativize(t).toString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static String slashify(String p) {
        return p == null ? null : p.replace('\\', '/');
    }

    /**
     * 注册表索引/API 里的文件路径校验: 必须是一条"就是本地相对路径"的字符串.
     *
     * <p>规范原文只禁了 {@code '/'} 开头、{@code '\'}、{@code '..'} 三种写法, 但那三家注册表都能返回
     * {@code file:///etc/passwd}、{@code https://evil.example/x} —— 只看前缀会把它们当成合法相对路径收进
     * 资源清单, 下游按 {@code base + path} 拼出来就是一个绝对 URL 或绝对路径. 所以这里额外要求:
     * 不含 scheme(冒号在路径段里没有任何合法用法)、不含 {@code //} 开头的 authority.
     */
    public static boolean isSafeRelativePath(String p) {
        if (p == null || p.isEmpty()) return false;
        String s = p.replace('\\', '/');
        if (s.startsWith("/")) return false;
        if (s.contains(":")) return false;
        for (String seg : s.split("/")) {
            if (seg.equals("..")) return false;
        }
        return true;
    }

    public static List<Path> walkMaxDepth(Path root, int maxDepth, int maxEntries) throws IOException {
        if (!Files.isDirectory(root)) return new ArrayList<Path>();
        final int rootDepth = root.toAbsolutePath().normalize().getNameCount();
        try (java.util.stream.Stream<Path> s = Files.walk(root, maxDepth)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.toAbsolutePath().getNameCount() - rootDepth <= maxDepth)
                    .limit(maxEntries)
                    .collect(Collectors.toList());
        }
    }

    public static int countLines(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') n++;
        }
        if (s.endsWith("\n")) n--;
        return n;
    }
}
