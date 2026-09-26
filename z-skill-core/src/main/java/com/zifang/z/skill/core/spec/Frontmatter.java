package com.zifang.z.skill.core.spec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 各家 Skill 文件头(YAML frontmatter)的宽松解析器.
 *
 * <p>要聚合的真实世界比规范脏得多, 这里逐个兜住:
 * <ul>
 *   <li>CRLF / UTF-8 BOM / 围栏前空行 / 收尾用 {@code ...}</li>
 *   <li>{@code description:} 值为空、说明写在下一层缩进的续行里(Qoder 内置 skill 就这么发)</li>
 *   <li>块标量 {@code |} / {@code >} 及其 {@code |-} {@code >-} 变体</li>
 *   <li>未加引号的值里带冒号(Codex/Cursor 描述里极常见)</li>
 *   <li>行内列表 {@code [a, b]}、块列表 {@code - a}、逗号串 {@code a, b}(z-skill 0.1.x 与 Qoder)</li>
 *   <li>一层嵌套映射 {@code metadata:}{@code author: x}</li>
 *   <li>规范外的键({@code descriptionZh}/{@code model}/{@code when_to_use}/{@code icon})原样进 metadata, 不丢</li>
 * </ul>
 *
 * <p>解析失败不抛异常: 问题落在 {@link #getIssues()} 里由聚合器记账, 因为"一个坏文件不该拖垮一次聚合".
 */
public final class Frontmatter {

    private static final Pattern KEY_LINE = Pattern.compile("^(\\s*)([A-Za-z_][A-Za-z0-9_.\\-]*)\\s*:(.*)$");
    private static final Pattern ITEM_LINE = Pattern.compile("^(\\s*)-(\\s?)(.*)$");
    private static final Pattern BLOCK_SCALAR = Pattern.compile("^([|>])([+-]?)(\\d*)$");

    private final Map<String, Object> values;
    private final String body;
    private final List<String> issues;
    private final boolean present;
    private final Set<String> foldedKeys;

    private Frontmatter(Map<String, Object> values, String body, List<String> issues, boolean present) {
        this(values, body, issues, present, Collections.<String>emptySet());
    }

    private Frontmatter(Map<String, Object> values, String body, List<String> issues, boolean present,
                        Set<String> foldedKeys) {
        this.values = values;
        this.body = body;
        this.issues = issues;
        this.present = present;
        this.foldedKeys = foldedKeys;
    }

    /** 值由多行折叠而来(块标量或续行)的键 — 上层据此留下"这条描述不是原文一句话"的痕迹. */
    public Set<String> foldedKeys() {
        return foldedKeys;
    }

    public static Frontmatter empty() {
        return new Frontmatter(new LinkedHashMap<String, Object>(), "", Collections.<String>emptyList(), false);
    }

    /**
     * 解析一份文档.
     *
     * @param text 原文, 可为 null
     */
    public static Frontmatter parse(String text) {
        List<String> issues = new ArrayList<String>();
        if (text == null) {
            issues.add("error:empty-content");
            return new Frontmatter(new LinkedHashMap<String, Object>(), "", issues, false);
        }
        String normalized = normalize(text);
        List<String> lines = splitLines(normalized);

        int i = 0;
        while (i < lines.size() && lines.get(i).trim().isEmpty()) i++;
        if (i >= lines.size() || !isFence(lines.get(i))) {
            issues.add("error:frontmatter-missing");
            return new Frontmatter(new LinkedHashMap<String, Object>(), normalized, issues, false);
        }
        int yamlStart = i + 1;
        int yamlEnd = -1;
        for (int j = yamlStart; j < lines.size(); j++) {
            String l = lines.get(j);
            if (isFence(l) || l.trim().equals("...")) {
                yamlEnd = j;
                break;
            }
        }
        if (yamlEnd < 0) {
            issues.add("warning:frontmatter-unclosed");
            yamlEnd = lines.size();
        }
        List<String> yaml = lines.subList(yamlStart, yamlEnd);
        List<String> bodyLines = yamlEnd < lines.size() ? lines.subList(yamlEnd + 1, lines.size()) : Collections.<String>emptyList();
        StringBuilder body = new StringBuilder();
        for (int j = 0; j < bodyLines.size(); j++) {
            if (j > 0) body.append('\n');
            body.append(bodyLines.get(j));
        }

        Map<String, Object> values = new LinkedHashMap<String, Object>();
        Set<String> folded = new java.util.LinkedHashSet<String>();
        parseMapping(yaml, 0, yaml.size(), values, issues, folded);
        return new Frontmatter(values, body.toString(), issues, true, folded);
    }

    private static void parseMapping(List<String> lines, int from, int to, Map<String, Object> out,
                                     List<String> issues, Set<String> foldedKeys) {
        int base = baseIndent(lines, from, to);
        int idx = from;
        while (idx < to) {
            String line = lines.get(idx);
            if (line.trim().isEmpty() || line.trim().startsWith("#")) {
                idx++;
                continue;
            }
            Matcher k = KEY_LINE.matcher(line);
            if (!k.matches()) {
                issues.add("warning:unparsed-line:" + line.trim());
                idx++;
                continue;
            }
            int indent = indentOf(line);
            String key = k.group(2);
            String inline = k.group(3).trim();
            int blockEnd = blockEndOf(lines, idx + 1, to, indent < base ? base : indent);
            if (!inline.isEmpty()) {
                Matcher bs = BLOCK_SCALAR.matcher(inline);
                if (bs.matches()) {
                    out.put(key, blockScalar(inline, lines, idx + 1, blockEnd, bs.group(1), bs.group(2)));
                    if (blockEnd > idx + 1) foldedKeys.add(key);
                } else {
                    out.put(key, unquote(inline));
                }
                idx = blockEnd + 1;
                continue;
            }
            // 空值: 后面可能是块列表 / 嵌套映射 / 折叠续行 / 真的空值
            int childStart = firstNonBlank(lines, idx + 1, to);
            if (childStart < 0 || childStart > to) {
                out.put(key, "");
                idx = blockEnd + 1;
                continue;
            }
            String child = lines.get(childStart);
            int childIndent = indentOf(child);
            if (childIndent <= indent) {
                out.put(key, "");
                idx = childStart;
                continue;
            }
            if (ITEM_LINE.matcher(child).matches()) {
                List<String> list = new ArrayList<String>();
                for (int j = childStart; j <= blockEnd; j++) {
                    Matcher m = ITEM_LINE.matcher(lines.get(j));
                    if (m.matches() && indentOf(lines.get(j)) >= childIndent) {
                        String v = m.group(3).trim();
                        if (!v.isEmpty()) list.add(unquote(v));
                    }
                }
                out.put(key, list);
                idx = blockEnd + 1;
                continue;
            }
            if (KEY_LINE.matcher(child).matches()) {
                Map<String, Object> nested = new LinkedHashMap<String, Object>();
                // blockEnd 是"最后一行的下标", parseMapping 的 to 是开区间 — 少 +1 会吞掉嵌套映射的最后一个键
                parseMapping(lines, childStart, blockEnd + 1, nested, issues, foldedKeys);
                out.put(key, nested);
                idx = blockEnd + 1;
                continue;
            }
            // 折叠续行(plain multiline scalar)
            StringBuilder folded = new StringBuilder();
            for (int j = childStart; j <= blockEnd; j++) {
                String l = lines.get(j);
                if (l.trim().isEmpty() || l.trim().startsWith("#")) continue;
                if (folded.length() > 0) folded.append(' ');
                folded.append(l.trim());
            }
            out.put(key, unquote(folded.toString()));
            if (blockEnd > childStart) foldedKeys.add(key);
            idx = blockEnd + 1;
        }
    }

    private static String blockScalar(String header, List<String> lines, int from, int to, String style, String chomp) {
        int childStart = firstNonBlank(lines, from, to);
        int indent = childStart < 0 ? 0 : indentOf(lines.get(childStart));
        List<String> collected = new ArrayList<String>();
        for (int j = from; j <= to && j < lines.size(); j++) {
            String l = lines.get(j);
            if (l.trim().isEmpty()) {
                collected.add("");
                continue;
            }
            if (indentOf(l) < indent) break;
            collected.add(l.substring(Math.min(indent, l.length())));
        }
        while (!collected.isEmpty() && collected.get(collected.size() - 1).trim().isEmpty()) {
            collected.remove(collected.size() - 1);
        }
        StringBuilder sb = new StringBuilder();
        if (">".equals(style)) {
            boolean prevWasText = false;
            for (String l : collected) {
                if (l.trim().isEmpty()) {
                    sb.append('\n');
                    prevWasText = false;
                } else {
                    if (prevWasText) sb.append(' ');
                    sb.append(l.trim());
                    prevWasText = true;
                }
            }
        } else {
            for (int j = 0; j < collected.size(); j++) {
                if (j > 0) sb.append('\n');
                sb.append(collected.get(j));
            }
        }
        if ("-".equals(chomp)) {
            int end = sb.length();
            return sb.substring(0, end).trim();
        }
        return sb.toString();
    }

    /** 一个键(含其子块)的最后一行下标. */
    private static int blockEndOf(List<String> lines, int from, int to, int indent) {
        int last = from - 1;
        for (int j = from; j < to; j++) {
            String l = lines.get(j);
            if (l.trim().isEmpty()) continue;
            if (indentOf(l) <= indent) break;
            last = j;
        }
        return last;
    }

    private static int firstNonBlank(List<String> lines, int from, int to) {
        for (int j = from; j < to; j++) {
            if (!lines.get(j).trim().isEmpty()) return j;
        }
        return -1;
    }

    private static int baseIndent(List<String> lines, int from, int to) {
        int min = Integer.MAX_VALUE;
        for (int j = from; j < to; j++) {
            String l = lines.get(j);
            if (l.trim().isEmpty() || l.trim().startsWith("#")) continue;
            min = Math.min(min, indentOf(l));
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    private static int indentOf(String line) {
        int n = 0;
        while (n < line.length() && (line.charAt(n) == ' ' || line.charAt(n) == '\t')) n++;
        return n;
    }

    private static boolean isFence(String line) {
        String t = line.trim();
        return t.equals("---") || t.equals("---\t") || t.equals("----");
    }

    static String unquote(String v) {
        if (v == null) return null;
        String s = v.trim();
        if (s.length() >= 2) {
            char a = s.charAt(0);
            char z = s.charAt(s.length() - 1);
            if ((a == '"' && z == '"') || (a == '\'' && z == '\'')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    public static String normalize(String text) {
        String s = text;
        if (!s.isEmpty() && s.charAt(0) == '﻿') s = s.substring(1);
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        return s;
    }

    private static List<String> splitLines(String s) {
        List<String> out = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    public boolean isPresent() {
        return present;
    }

    public Map<String, Object> values() {
        return Collections.unmodifiableMap(values);
    }

    public String body() {
        return body;
    }

    public List<String> issues() {
        return Collections.unmodifiableList(issues);
    }

    /** 取标量值; 列表/映射返回 null. */
    public String string(String key) {
        Object v = values.get(key);
        if (v == null) return null;
        if (v instanceof Map || v instanceof List) return null;
        String s = ((String) v).trim();
        return s.isEmpty() ? null : s;
    }

    public String firstOf(String... keys) {
        for (String k : keys) {
            String v = string(k);
            if (v != null) return v;
        }
        return null;
    }

    /**
     * 取列表语义的值: 支持块列表、行内列表、逗号/空格分隔字符串.
     *
     * @param sepSplit 分隔正则; 传 null 时按逗号切(平台普遍写法)
     */
    public List<String> list(String key, String sepSplit) {
        Object v = values.get(key);
        List<String> out = new ArrayList<String>();
        if (v == null) return out;
        if (v instanceof List) {
            for (Object o : (List<?>) v) {
                if (o == null) continue;
                out.add(String.valueOf(o).trim());
            }
            return dedupe(out);
        }
        if (v instanceof Map) return out;
        String s = ((String) v).trim();
        if (s.isEmpty()) return out;
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        for (String part : s.split(sepSplit == null ? "[,;]" : sepSplit)) {
            String t = unquote(part).trim();
            if (!t.isEmpty()) out.add(t);
        }
        return dedupe(out);
    }

    public List<String> list(String key) {
        return list(key, null);
    }

    /** 取嵌套映射; 扁平的 {@code metadata.foo: bar} 写法也会被收进同一个 map. */
    public Map<String, String> map(String key) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        Object v = values.get(key);
        if (v instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                out.put(String.valueOf(e.getKey()), scalarize(e.getValue()));
            }
        }
        String flatPrefix = key + ".";
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (e.getKey().startsWith(flatPrefix)) {
                out.put(e.getKey().substring(flatPrefix.length()), scalarize(e.getValue()));
            }
        }
        return out;
    }

    private static String scalarize(Object v) {
        if (v == null) return "";
        if (v instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object o : (List<?>) v) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(o);
            }
            return sb.toString();
        }
        return String.valueOf(v).trim();
    }

    private static List<String> dedupe(List<String> in) {
        List<String> out = new ArrayList<String>();
        for (String s : in) {
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
        }
        return out;
    }
}
