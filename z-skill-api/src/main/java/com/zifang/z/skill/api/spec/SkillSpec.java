package com.zifang.z.skill.api.spec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Agent Skills 规范里的硬约束 + 宽松兼容层的清洗规则.
 *
 * <p>严格模式(skills-ref validate 口径)与宽松模式(真实世界)的差别都在这里,
 * 因为要聚合的各家仓库里有相当比例不符合规范(例: name 带大写、description 折行、allowed-tools 用逗号).
 */
public final class SkillSpec {

    /** name: 1-64 字符, a-z0-9 与单个连字符, 不得以连字符开头/结尾, 不得含 --. */
    public static final Pattern NAME = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    public static final int NAME_MAX = 64;
    public static final int DESCRIPTION_MAX = 1024;
    public static final int COMPATIBILITY_MAX = 500;
    /** 规范建议 SKILL.md 正文 &lt; 500 行. */
    public static final int BODY_LINES_SOFT_MAX = 500;
    /** 目录嵌套扫描深度上限(各家实现普遍 4-6). */
    public static final int MAX_SCAN_DEPTH = 6;
    /** 单个来源最多收录多少个 Skill, 防止误指向 monorepo 根. */
    public static final int MAX_SKILLS_PER_SOURCE = 2000;
    /** 搜索关键词最短长度, 对齐 skills.sh. */
    public static final int MIN_QUERY_LENGTH = 2;

    private static final Pattern NON_SLUG_CHARS = Pattern.compile("[^a-z0-9-]+");
    private static final Pattern MULTI_HYPHEN = Pattern.compile("-{2,}");

    private SkillSpec() {
    }

    /**
     * 把任意来源写法清洗成合规 slug: 转小写、非法字符转连字符、折叠多余连字符、去首尾、截断到 64.
     *
     * @return 清洗结果; 清洗后为空则返回 null(调用方回退到目录名)
     */
    public static String slugify(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase();
        s = s.replace('_', '-').replace(' ', '-').replace('.', '-');
        s = NON_SLUG_CHARS.matcher(s).replaceAll("-");
        s = MULTI_HYPHEN.matcher(s).replaceAll("-");
        while (s.startsWith("-")) s = s.substring(1);
        while (s.endsWith("-")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return null;
        if (s.length() > NAME_MAX) s = s.substring(0, NAME_MAX);
        while (s.endsWith("-")) s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? null : s;
    }

    public static boolean isValidName(String name) {
        return name != null && !name.isEmpty() && name.length() <= NAME_MAX && NAME.matcher(name).matches();
    }

    /**
     * 按规范校验一个 Skill 的元信息, 返回问题列表(空即完全合规).
     *
     * <p>只报 error 与 warning, 不抛异常 — 聚合器的策略是"能收就收, 收不下就记账".
     *
     * @param rawName     来源原文写法
     * @param slug        清洗后的标识, 可为 null
     * @param description 描述原文
     * @param compatibility 兼容性声明
     */
    public static List<String> validate(String rawName, String slug, String description, String compatibility) {
        List<String> issues = new ArrayList<String>();
        if (rawName == null || rawName.trim().isEmpty()) {
            issues.add("error:name-missing");
            return issues;
        }
        if (!NAME.matcher(rawName).matches()) {
            if (slug == null) {
                issues.add("error:name-unsanitizeable:" + rawName);
            } else if (!rawName.equals(slug)) {
                issues.add("warning:name-non-conforming:" + rawName + "->" + slug);
            }
        }
        if (rawName.length() > NAME_MAX) {
            issues.add("warning:name-too-long:" + rawName.length());
        }
        if (description == null || description.trim().isEmpty()) {
            issues.add("error:description-missing");
        } else {
            if (description.length() > DESCRIPTION_MAX) {
                issues.add("warning:description-too-long:" + description.length());
            }
            if (description.indexOf('\n') >= 0) {
                issues.add("info:description-multiline");
            }
        }
        if (compatibility != null && compatibility.length() > COMPATIBILITY_MAX) {
            issues.add("warning:compatibility-too-long");
        }
        return issues;
    }

    /**
     * allowed-tools 拆分: 规范是空格分隔, Qoder 实际发的是逗号分隔, Cursor 也见过分号.
     *
     * <p>注意不能无脑按空格切 — {@code Bash(git:*)} 这种带参工具名内部没有空格, 但 {@code Read} 与
     * {@code Write} 之间是空格, 所以按 [,;] 与空白双重切分即可, 括号内不含逗号空格.
     */
    public static List<String> splitAllowedTools(String raw) {
        if (raw == null || raw.trim().isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<String>();
        for (String part : raw.split("[,;\\s]+")) {
            String t = part.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }
}
