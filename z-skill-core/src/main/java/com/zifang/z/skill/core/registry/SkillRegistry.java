package com.zifang.z.skill.core.registry;

import com.zifang.z.skill.api.dto.AggregateReportDto;
import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.dto.SkillInstallDto;
import com.zifang.z.skill.api.dto.SkillSourceDto;
import com.zifang.z.skill.api.exception.SkillException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Skill 注册中心: 聚合结果的可查询视图 + 安装状态.
 *
 * <p>以 {@code id} 为唯一键(聚合器保证跨来源不撞), 同时维护 slug / name 的二级索引, 因为消费方
 * 一半按 {@code pdf-processing} 找、一半按 {@code anthropic/pdf-processing} 找.
 *
 * <p>线程安全; 一次聚合走 {@link #replaceAll} 原子换表, 读侧不会看到半张目录.
 */
public class SkillRegistry {

    private final ConcurrentMap<String, SkillDto> skills = new ConcurrentHashMap<String, SkillDto>();
    private final ConcurrentMap<String, SkillInstallDto> installs = new ConcurrentHashMap<String, SkillInstallDto>();
    private final ConcurrentMap<String, String> aliasIndex = new ConcurrentHashMap<String, String>();
    private volatile List<SkillSourceDto> sources = Collections.emptyList();
    private volatile AggregateReportDto lastReport;
    private volatile List<String> added = Collections.emptyList();
    private volatile List<String> changed = Collections.emptyList();
    private volatile List<String> removed = Collections.emptyList();

    /**
     * 用一次聚合的结果整体替换目录, 同时算出与上一次的差量(上游漂移检测).
     *
     * <p>已安装但目录里消失的 skill 保留安装记录并标记 dangling, 不静默回收 —— 那是用户装的东西.
     */
    public synchronized void replaceAll(Map<String, SkillDto> next, List<SkillSourceDto> nextSources,
                                        AggregateReportDto report) {
        Map<String, SkillDto> previous = new LinkedHashMap<String, SkillDto>(skills);
        List<String> addedIds = new ArrayList<String>();
        List<String> changedIds = new ArrayList<String>();
        for (Map.Entry<String, SkillDto> e : next.entrySet()) {
            SkillDto old = previous.get(e.getKey());
            if (old == null) {
                addedIds.add(e.getKey());
            } else if (old.getContentHash() != null && !old.getContentHash().equals(e.getValue().getContentHash())) {
                changedIds.add(e.getKey());
            }
        }
        List<String> removedIds = new ArrayList<String>();
        for (String id : previous.keySet()) {
            if (!next.containsKey(id)) removedIds.add(id);
        }
        skills.clear();
        skills.putAll(next);

        aliasIndex.clear();
        for (SkillDto dto : next.values()) index(dto);
        this.sources = nextSources == null ? Collections.<SkillSourceDto>emptyList()
                : Collections.unmodifiableList(new ArrayList<SkillSourceDto>(nextSources));
        this.lastReport = report;
        Collections.sort(addedIds);
        Collections.sort(changedIds);
        Collections.sort(removedIds);
        this.added = Collections.unmodifiableList(addedIds);
        this.changed = Collections.unmodifiableList(changedIds);
        this.removed = Collections.unmodifiableList(removedIds);
    }

    /** 一条 skill 的全部可寻址写法都落到它的 id; 先到先得, 覆盖交给聚合器判. */
    private void index(SkillDto dto) {
        String id = dto.getId();
        indexKey(id, id);
        indexKey(dto.getSlug(), id);
        indexKey(dto.getName(), id);
        for (String alias : dto.getAliases()) indexKey(alias, id);
    }

    private void indexKey(String key, String id) {
        if (key == null || key.isEmpty()) return;
        aliasIndex.putIfAbsent(key.toLowerCase(Locale.ROOT), id);
    }

    public void register(SkillDto dto) {
        if (dto == null || dto.getId() == null) {
            throw new IllegalArgumentException("skill.id required");
        }
        skills.put(dto.getId(), dto);
        index(dto);
    }

    public void unregister(String id) {
        skills.remove(id);
        installs.remove(id);
        rebuildAliasIndex();
    }

    /** 撤下一条会改变"先到先得"的 alias 归属, 只能整体重建. */
    private void rebuildAliasIndex() {
        aliasIndex.clear();
        for (SkillDto dto : skills.values()) index(dto);
    }

    public void clear() {
        skills.clear();
        aliasIndex.clear();
        sources = Collections.emptyList();
    }

    /**
     * installed 是注册表自己的状态, 不是来源给的数据: 谁出口谁盖章.
     * 早先只有检索层在盖, 于是绕过检索层的两个出口 — {@code /skill/detail} 和
     * "我的安装" — 都稳定地回答"没装", 而同一行旁边就躺着安装记录.
     * 反方向同样要盖: 上游若在 payload 里自称 installed, 这里按本地事实改写, 不替它背书.
     */
    private SkillDto stampInstalled(SkillDto dto) {
        boolean now = installs.containsKey(dto.getId());
        return dto.isInstalled() == now ? dto : dto.toBuilder().installed(now).build();
    }

    /** 按 id / slug / name / alias 查, 大小写不敏感. */
    public Optional<SkillDto> get(String idOrSlug) {
        if (idOrSlug == null) return Optional.empty();
        SkillDto direct = skills.get(idOrSlug);
        if (direct != null) return Optional.of(stampInstalled(direct));
        String mapped = aliasIndex.get(idOrSlug.toLowerCase(Locale.ROOT));
        if (mapped != null) {
            SkillDto byAlias = skills.get(mapped);
            if (byAlias != null) return Optional.of(stampInstalled(byAlias));
        }
        return Optional.empty();
    }

    public SkillDto require(String idOrSlug) {
        return get(idOrSlug).orElseThrow(() -> SkillException.notFound(idOrSlug));
    }

    public List<SkillDto> listAll() {
        List<SkillDto> out = new ArrayList<SkillDto>(skills.size());
        for (SkillDto dto : skills.values()) out.add(stampInstalled(dto));
        Collections.sort(out, ID_ORDER);
        return out;
    }

    public static final Comparator<SkillDto> ID_ORDER = new Comparator<SkillDto>() {
        @Override
        public int compare(SkillDto a, SkillDto b) {
            return String.valueOf(a.getId()).compareTo(String.valueOf(b.getId()));
        }
    };

    public Map<String, SkillDto> snapshot() {
        Map<String, SkillDto> out = new LinkedHashMap<String, SkillDto>();
        for (Map.Entry<String, SkillDto> e : skills.entrySet()) out.put(e.getKey(), stampInstalled(e.getValue()));
        return Collections.unmodifiableMap(out);
    }

    public int skillCount() {
        return skills.size();
    }

    public int installedCount() {
        return installs.size();
    }

    public List<SkillSourceDto> getSources() {
        return sources;
    }

    public AggregateReportDto getLastReport() {
        return lastReport;
    }

    public List<String> getAdded() { return added; }
    public List<String> getChanged() { return changed; }
    public List<String> getRemoved() { return removed; }

    public SkillInstallDto install(String idOrSlug, String installedBy, String channel) {
        SkillDto dto = require(idOrSlug);
        String id = dto.getId();
        if (installs.containsKey(id)) {
            throw SkillException.alreadyInstalled(id);
        }
        SkillInstallDto rec = new SkillInstallDto(id, dto.getName(), dto.getVersion(), installedBy,
                System.currentTimeMillis(), channel == null ? "marketplace" : channel, dto.getSource(),
                dto.getContentHash(), dto.getOrigin());
        installs.put(id, rec);
        return rec;
    }

    public SkillInstallDto uninstall(String idOrSlug) {
        SkillDto dto = get(idOrSlug).orElse(null);
        String id = dto == null ? idOrSlug : dto.getId();
        SkillInstallDto removed = installs.remove(id);
        if (removed == null) {
            throw SkillException.notFound(idOrSlug);
        }
        return removed;
    }

    public boolean isInstalled(String idOrSlug) {
        return get(idOrSlug).map(d -> installs.containsKey(d.getId())).orElse(false);
    }

    public Optional<SkillInstallDto> getInstallRecord(String idOrSlug) {
        return get(idOrSlug).flatMap(d -> Optional.ofNullable(installs.get(d.getId())));
    }

    public List<SkillInstallDto> installRecords() {
        List<SkillInstallDto> out = new ArrayList<SkillInstallDto>(installs.values());
        Collections.sort(out, new Comparator<SkillInstallDto>() {
            @Override
            public int compare(SkillInstallDto a, SkillInstallDto b) {
                return a.getSkillId().compareTo(b.getSkillId());
            }
        });
        return out;
    }

    /** 安装记录指向的 skill 已被上游撤下. */
    public List<String> danglingInstalls() {
        List<String> out = new ArrayList<String>();
        for (String id : installs.keySet()) {
            if (!skills.containsKey(id)) out.add(id);
        }
        Collections.sort(out);
        return out;
    }

    public List<SkillDto> installedSkills() {
        List<SkillDto> out = new ArrayList<SkillDto>();
        for (String id : installs.keySet()) {
            SkillDto dto = skills.get(id);
            if (dto != null) out.add(stampInstalled(dto));
        }
        Collections.sort(out, ID_ORDER);
        return out;
    }
}
