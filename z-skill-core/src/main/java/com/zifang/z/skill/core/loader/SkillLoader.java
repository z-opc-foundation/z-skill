package com.zifang.z.skill.core.loader;

import com.zifang.z.skill.api.exception.SkillException;
import com.zifang.z.skill.core.registry.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skill 文件系统加载器 — 默认实现.
 *
 * <p>扫描一个目录, 把每个 .md 文件当作一个 Skill, 从 frontmatter 解析元信息(name/description/version/category/tags/author/trigger).
 *
 * <p>frontmatter 格式 (YAML-like, 简化解析):
 * <pre>{@code
 * ---
 * name: code-review
 * description: 对 PR 做代码审查
 * version: 1.0.0
 * category: development
 * tags: code, review, pr
 * author: yuku123
 * trigger: 当用户请求 PR review 或代码审查时加载
 * ---
 * # Skill 正文 (Markdown)
 * ...
 * }</pre>
 *
 * <p>对应 z-opc 老 z-agent-engine/agent/builtins/extensions/skills/CatalogSkillRegistry 蒸馏.
 */
public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);

    private static final Pattern FRONTMATTER = Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$",
            Pattern.DOTALL | Pattern.MULTILINE);
    private static final Pattern KV = Pattern.compile("^([\\w-]+):\\s*(.+)$", Pattern.MULTILINE);

    private final SkillRegistry registry;

    public SkillLoader(SkillRegistry registry) {
        this.registry = registry;
    }

    /**
     * 扫描目录, 把每个 .md 文件注册为 Skill.
     *
     * @param dir 目录(不存在返回 0)
     * @return 成功注册的 Skill 数
     */
    public int scanDirectory(File dir) {
        if (dir == null || !dir.isDirectory()) {
            log.warn("SkillLoader: directory not found or not a directory: {}", dir);
            return 0;
        }
        int count = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".md")) {
                try {
                    loadFile(f);
                    count++;
                } catch (Exception e) {
                    log.warn("SkillLoader: failed to load {}: {}", f.getAbsolutePath(), e.getMessage());
                }
            }
        }
        log.info("SkillLoader: scanned {} skills from {}", count, dir.getAbsolutePath());
        return count;
    }

    /**
     * 加载单个文件并注册.
     */
    public void loadFile(File file) throws IOException {
        String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        SkillRegistry.SkillEntry entry = parseFrontmatter(content, file.getAbsolutePath());
        registry.register(entry);
    }

    /**
     * 解析 frontmatter (仅支持简化 key: value 格式, 列表用逗号分隔).
     */
    static SkillRegistry.SkillEntry parseFrontmatter(String content, String path) {
        Matcher m = FRONTMATTER.matcher(content);
        if (!m.matches()) {
            throw SkillException.invalidFrontmatter(path, "missing or malformed frontmatter");
        }
        String yaml = m.group(1);
        String body = m.group(2);
        int toolCount = (int) body.lines().filter(l -> l.trim().startsWith("- ")).count();

        String name = null, description = null, version = null, category = null;
        String author = null, trigger = null;
        List<String> tags = new ArrayList<String>();

        Matcher kv = KV.matcher(yaml);
        while (kv.find()) {
            String key = kv.group(1).trim();
            String value = kv.group(2).trim();
            // 去除引号
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            switch (key) {
                case "name": name = value; break;
                case "description": description = value; break;
                case "version": version = value; break;
                case "category": category = value; break;
                case "author": author = value; break;
                case "trigger": trigger = value; break;
                case "tags":
                    for (String t : value.split(",")) {
                        String tag = t.trim();
                        if (!tag.isEmpty()) tags.add(tag);
                    }
                    break;
                default:
                    // 忽略未知字段
            }
        }
        if (name == null || name.isEmpty()) {
            throw SkillException.invalidFrontmatter(path, "name field required");
        }
        return new SkillRegistry.SkillEntry(name, description, version, category,
                Collections.unmodifiableList(tags), author, trigger, toolCount);
    }
}