package com.zifang.z.skill.core.registry;

import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.dto.SkillInstallDto;
import com.zifang.z.skill.api.exception.SkillException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Skill 注册中心 — 内存版默认实现.
 *
 * <p>线程安全. 两个视图:
 * <ul>
 *   <li>{@code skills}: 全部已注册 Skill (按 name)</li>
 *   <li>{@code installed}: 已安装 Skill (子集, 用于 Marketplace "我的安装")</li>
 * </ul>
 *
 * <p>对应 z-opc 老 z-agent-skill-center 的内存缓存层蒸馏.
 */
public class SkillRegistry {

    private final ConcurrentMap<String, SkillEntry> skills = new ConcurrentHashMap<String, SkillEntry>();
    private final ConcurrentMap<String, SkillInstallDto> installed = new ConcurrentHashMap<String, SkillInstallDto>();

    /**
     * 注册一个 Skill (来自本地扫描 / Marketplace 同步).
     */
    public void register(SkillEntry entry) {
        if (entry == null || entry.name == null) {
            throw new IllegalArgumentException("entry.name required");
        }
        skills.put(entry.name, entry);
    }

    public void unregister(String name) {
        skills.remove(name);
        installed.remove(name);
    }

    public Optional<SkillEntry> get(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    public List<SkillDto> listAll() {
        List<SkillDto> out = new ArrayList<SkillDto>(skills.size());
        for (SkillEntry e : skills.values()) {
            out.add(toDto(e, installed.containsKey(e.name)));
        }
        return out;
    }

    public List<SkillDto> listByCategory(String category) {
        List<SkillDto> out = new ArrayList<SkillDto>();
        for (SkillEntry e : skills.values()) {
            if (category == null || category.equals(e.category)) {
                out.add(toDto(e, installed.containsKey(e.name)));
            }
        }
        return out;
    }

    public List<SkillDto> listInstalled() {
        List<SkillDto> out = new ArrayList<SkillDto>();
        for (SkillEntry e : skills.values()) {
            if (installed.containsKey(e.name)) {
                out.add(toDto(e, true));
            }
        }
        return out;
    }

    public List<String> listCategories() {
        java.util.Set<String> cats = new java.util.HashSet<String>();
        for (SkillEntry e : skills.values()) {
            if (e.category != null) cats.add(e.category);
        }
        return new ArrayList<String>(cats);
    }

    /**
     * 安装一个 Skill (标记为已安装).
     */
    public SkillInstallDto install(String name, String installedBy, String source) {
        SkillEntry e = skills.get(name);
        if (e == null) {
            throw SkillException.notFound(name);
        }
        if (installed.containsKey(name)) {
            throw SkillException.alreadyInstalled(name);
        }
        SkillInstallDto rec = new SkillInstallDto(name, e.version, installedBy, System.currentTimeMillis(), source);
        installed.put(name, rec);
        return rec;
    }

    public void uninstall(String name) {
        installed.remove(name);
    }

    public boolean isInstalled(String name) {
        return installed.containsKey(name);
    }

    public Optional<SkillInstallDto> getInstallRecord(String name) {
        return Optional.ofNullable(installed.get(name));
    }

    public int skillCount() {
        return skills.size();
    }

    public int installedCount() {
        return installed.size();
    }

    /**
     * Skill 注册条目(内部用, 包含 DTO 字段 + 触发器 + 工具计数).
     */
    public static final class SkillEntry {
        public final String name;
        public final String description;
        public final String version;
        public final String category;
        public final java.util.List<String> tags;
        public final String author;
        public final String trigger;
        public final int toolCount;

        public SkillEntry(String name, String description, String version, String category,
                          java.util.List<String> tags, String author, String trigger, int toolCount) {
            this.name = name;
            this.description = description;
            this.version = version;
            this.category = category;
            this.tags = tags == null ? Collections.emptyList() : tags;
            this.author = author;
            this.trigger = trigger;
            this.toolCount = toolCount;
        }
    }

    private static SkillDto toDto(SkillEntry e, boolean installed) {
        return new SkillDto(e.name, e.description, e.version, e.category, e.tags,
                e.author, e.toolCount, installed, e.trigger);
    }
}