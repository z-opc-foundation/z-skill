package com.zifang.z.skill.core.aggregate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.skill.api.dto.AggregateReportDto;
import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.dto.SkillIssueDto;
import com.zifang.z.skill.api.dto.SkillSourceDto;
import com.zifang.z.skill.api.spec.SkillFormat;
import com.zifang.z.skill.core.discover.FilesystemScanner;
import com.zifang.z.skill.core.discover.HttpFetcher;
import com.zifang.z.skill.core.discover.PluginManifestReader;
import com.zifang.z.skill.core.discover.RawSkill;
import com.zifang.z.skill.core.discover.SkillSource;
import com.zifang.z.skill.core.normalize.MarkdownSkillNormalizer;
import com.zifang.z.skill.core.normalize.RegistryIndexNormalizer;
import com.zifang.z.skill.core.normalize.SkillNormalizer;
import com.zifang.z.skill.core.normalize.SkillsShApiNormalizer;
import com.zifang.z.skill.core.registry.SkillRegistry;
import com.zifang.z.skill.core.scan.SkillSecurityScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 多平台 Skill 聚合器 — z-skill 的本职.
 *
 * <p>流水线: 来源队列 → 扫描/取数 → 归一化 → 命名空间 → 冲突消解 → 静态风险判级 → 原子换表.
 *
 * <p>三条刻意的策略, 因为"聚合别人的目录"跟"自建目录"不是一回事:
 * <ul>
 *   <li>同名不丢: 真撞 id 时各方都被加 {@code sourceId/} 前缀, 全都保留, 冲突记进报告</li>
 *   <li>同内容折叠: 一份 SKILL.md 常被多个平台目录互链(如 {@code .claude/skills} 与 {@code .agents/skills}),
 *       按 contentHash 折成一条, 其余降级为 alias, 安装量累加</li>
 *   <li>坏条目记账不炸链: 单文件解析失败只进 issues, 一次聚合照常完成</li>
 * </ul>
 */
public class SkillAggregator {

    private static final Logger log = LoggerFactory.getLogger(SkillAggregator.class);
    private static final int MAX_DERIVED_DEPTH = 3;

    private final List<SkillSource> declaredSources = new ArrayList<SkillSource>();
    private final Map<SkillFormat, SkillNormalizer> normalizers = new LinkedHashMap<SkillFormat, SkillNormalizer>();
    private final FilesystemScanner scanner = new FilesystemScanner();
    private final SkillRegistry registry;
    private final PluginManifestReader pluginReader;
    private final SkillSecurityScanner securityScanner;
    private final ObjectMapper mapper;
    private HttpFetcher httpFetcher = new HttpFetcher.Jdk();

    public SkillAggregator(SkillRegistry registry, ObjectMapper mapper) {
        this(registry, mapper, new SkillSecurityScanner());
    }

    public SkillAggregator(SkillRegistry registry, ObjectMapper mapper, SkillSecurityScanner securityScanner) {
        this.registry = registry;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
        this.securityScanner = securityScanner;
        this.pluginReader = new PluginManifestReader(this.mapper);
        MarkdownSkillNormalizer markdown = new MarkdownSkillNormalizer();
        normalizers.put(SkillFormat.AGENT_SKILLS, markdown);
        normalizers.put(SkillFormat.FLAT_MARKDOWN, markdown);
        normalizers.put(SkillFormat.CURSOR_RULES, markdown);
        normalizers.put(SkillFormat.AGENTS_MD, markdown);
        normalizers.put(SkillFormat.COPILOT_INSTRUCTIONS, markdown);
        normalizers.put(SkillFormat.REGISTRY_INDEX, new RegistryIndexNormalizer(this.mapper));
        normalizers.put(SkillFormat.SKILLS_SH_API, new SkillsShApiNormalizer(this.mapper));
    }

    public SkillAggregator addSource(SkillSource source) {
        if (source != null && source.getId() != null) declaredSources.add(source);
        return this;
    }

    public SkillAggregator setHttpFetcher(HttpFetcher fetcher) {
        this.httpFetcher = fetcher == null ? new HttpFetcher.Jdk() : fetcher;
        return this;
    }

    public List<SkillSource> getDeclaredSources() {
        return Collections.unmodifiableList(new ArrayList<SkillSource>(declaredSources));
    }

    /**
     * 跑一次聚合并把结果换进注册中心.
     *
     * @return 本次聚合的体检报告
     */
    public synchronized AggregateReportDto refresh() {
        long started = System.currentTimeMillis();
        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
        List<SkillSourceDto> sourceViews = new ArrayList<SkillSourceDto>();
        Map<String, List<Candidate>> bySlug = new LinkedHashMap<String, List<Candidate>>();
        int rawCount = 0;
        int producedCount = 0;
        int droppedCount = 0;

        List<SkillSource> ordered = new ArrayList<SkillSource>(declaredSources);
        Collections.sort(ordered, new Comparator<SkillSource>() {
            @Override
            public int compare(SkillSource a, SkillSource b) {
                int byPriority = Integer.compare(a.getPriority(), b.getPriority());
                return byPriority != 0 ? byPriority : String.valueOf(a.getId()).compareTo(String.valueOf(b.getId()));
            }
        });

        Set<String> visited = new HashSet<String>();
        Deque<Pending> queue = new ArrayDeque<Pending>();
        for (SkillSource s : ordered) {
            if (s.isEnabled()) queue.add(new Pending(s, 0));
        }

        while (!queue.isEmpty()) {
            Pending pending = queue.poll();
            SkillSource source = pending.source;
            if (!visited.add(source.getId() + "|" + String.valueOf(source.location()))) continue;

            long sourceStarted = System.currentTimeMillis();
            List<SkillIssueDto> sourceIssues = new ArrayList<SkillIssueDto>();
            List<String> producedIds = new ArrayList<String>();
            Set<String> observed = new TreeSet<String>();
            String status = "ok";
            try {
                List<RawSkill> raws = source.isRemote() ? readRemote(source, sourceIssues) : scanner.scan(source, sourceIssues);
                rawCount += raws.size();
                int derived = 0;
                for (RawSkill raw : raws) {
                    if (raw.getFormat() != null) observed.add(raw.getFormat().id());
                    if (raw.getFormat() == SkillFormat.PLUGIN_MANIFEST) {
                        // 清单本身不是条目, 是"再开几个来源": 既不算产出也不算丢弃
                        derived += spawnDerivedSources(raw, source, pending.depth, queue);
                        continue;
                    }
                    SkillNormalizer normalizer = normalizers.get(raw.getFormat());
                    if (normalizer == null) {
                        sourceIssues.add(SkillIssueDto.error("no-normalizer", raw.getFallbackName(), source.getId(),
                                raw.getEntryRelative(), "格式 " + raw.getFormat() + " 没有归一化器, 该候选被丢弃"));
                        droppedCount++;
                        continue;
                    }
                    List<SkillDto> produced = normalizer.normalize(raw, source, sourceIssues);
                    producedCount += produced.size();
                    if (produced.isEmpty()) droppedCount++;
                    for (SkillDto dto : produced) {
                        Candidate candidate = new Candidate(dto.toBuilder().id(dto.getSlug()).build(), source);
                        if (securityScanner != null) {
                            SkillSecurityScanner.Verdict verdict = securityScanner.scan(candidate.dto, raw.getText());
                            // 上游注册表自带的审计档位不能被本机纯文本判定抹平: 两个尺子取更差的一档.
                            // 但 dto 的 riskLevel 缺省就是 unscanned(没人声明过), 那种情况只能采信本机判定.
                            String declared = candidate.dto.getRiskLevel();
                            candidate.risk = declared == null || SkillDto.RISK_UNSCANNED.equals(declared)
                                    ? verdict.getRiskLevel()
                                    : SkillSecurityScanner.worse(declared, verdict.getRiskLevel());
                            candidate.findings = verdict.getFindings();
                        }
                        List<Candidate> bucket = bySlug.get(candidate.dto.getSlug());
                        if (bucket == null) {
                            bucket = new ArrayList<Candidate>();
                            bySlug.put(candidate.dto.getSlug(), bucket);
                        }
                        bucket.add(candidate);
                        producedIds.add(candidate.dto.getSlug());
                    }
                }
                if (derived > 0) {
                    sourceIssues.add(SkillIssueDto.info("plugin-manifest-derived", source.getId(), source.getId(),
                            source.location(), "从插件清单派生出 " + derived + " 个 skill 目录来源"));
                }
            } catch (Exception e) {
                status = "error";
                sourceIssues.add(SkillIssueDto.error("source-failed", source.getId(), source.getId(),
                        source.location(), e.getClass().getSimpleName() + ": " + e.getMessage()));
                log.warn("z-skill: source {} failed: {}", source.getId(), e.toString());
            }
            Collections.sort(producedIds);
            issues.addAll(sourceIssues);
            sourceViews.add(new SkillSourceDto(source.getId(),
                    source.getFormat() == null ? SkillFormat.UNKNOWN.id() : source.getFormat().id(),
                    new ArrayList<String>(observed),
                    source.location(), source.getPriority(), source.isEnabled(), status, producedIds.size(),
                    producedIds, sourceIssues.size(), head(sourceIssues, 8),
                    System.currentTimeMillis() - sourceStarted, System.currentTimeMillis()));
        }

        MergeResult merged = merge(bySlug, issues);

        Map<String, Integer> formatDist = new TreeMap<String, Integer>();
        for (SkillDto dto : merged.skills.values()) {
            String f = dto.getFormatId();
            Integer n = formatDist.get(f);
            formatDist.put(f, n == null ? 1 : n + 1);
        }
        Map<String, Integer> severityDist = new TreeMap<String, Integer>();
        for (SkillIssueDto issue : issues) {
            Integer n = severityDist.get(issue.getSeverity());
            severityDist.put(issue.getSeverity(), n == null ? 1 : n + 1);
        }
        AggregateReportDto report = new AggregateReportDto(System.currentTimeMillis() - started,
                sourceViews.size(), merged.skills.size(), rawCount, producedCount, droppedCount,
                merged.deduped, merged.conflicts, sourceViews, issues, formatDist, severityDist);
        registry.replaceAll(merged.skills, sourceViews, report);
        log.info("z-skill: aggregated {} skills from {} sources in {} ms ({} raw candidates, {} produced, {} dropped, {} issues, {} conflicts, {} deduped)",
                merged.skills.size(), sourceViews.size(), report.getDurationMs(), rawCount, producedCount,
                droppedCount, issues.size(), merged.conflicts, merged.deduped);
        return report;
    }

    private int spawnDerivedSources(RawSkill raw, SkillSource source, int depth, Deque<Pending> queue) {
        if (depth >= MAX_DERIVED_DEPTH || raw.getEntry() == null) return 0;
        List<SkillSource> derived = pluginReader.derive(raw.getEntry(), source);
        for (SkillSource s : derived) {
            queue.add(new Pending(s, depth + 1));
        }
        return derived.size();
    }

    private List<RawSkill> readRemote(SkillSource source, List<SkillIssueDto> issues) throws IOException {
        String body;
        try {
            body = httpFetcher.get(source.getUrl());
        } catch (Exception e) {
            issues.add(SkillIssueDto.error("fetch-failed", source.getId(), source.getId(), source.getUrl(),
                    String.valueOf(e.getMessage())));
            throw new IOException("fetch failed: " + source.getUrl(), e);
        }
        SkillFormat format = source.getFormat() == null || source.getFormat() == SkillFormat.UNKNOWN
                ? SkillFormat.SKILLS_SH_API : source.getFormat();
        RawSkill raw = new RawSkill().format(format).origin(source.getUrl())
                .entryRelative(source.getUrl()).fallbackName(source.getId()).text(body);
        try {
            raw.json(mapper.readTree(body));
        } catch (Exception e) {
            issues.add(SkillIssueDto.error("payload-not-json", source.getId(), source.getId(), source.getUrl(),
                    String.valueOf(e.getMessage())));
            return Collections.emptyList();
        }
        List<RawSkill> out = new ArrayList<RawSkill>();
        out.add(raw);
        return out;
    }

    private MergeResult merge(Map<String, List<Candidate>> bySlug, List<SkillIssueDto> issues) {
        List<String> slugs = new ArrayList<String>(bySlug.keySet());
        Collections.sort(slugs);
        MergeResult result = new MergeResult();
        Set<String> usedIds = new HashSet<String>();

        for (String slug : slugs) {
            List<Candidate> bucket = bySlug.get(slug);
            Collections.sort(bucket, new Comparator<Candidate>() {
                @Override
                public int compare(Candidate a, Candidate b) {
                    int byPriority = Integer.compare(a.source.getPriority(), b.source.getPriority());
                    if (byPriority != 0) return byPriority;
                    return String.valueOf(a.source.getId()).compareTo(String.valueOf(b.source.getId()));
                }
            });
            int bare = 0;
            for (Candidate c : bucket) {
                if (isBare(c.dto.getId())) bare++;
            }
            boolean needsNamespacing = bare > 1;

            Candidate primary = null;
            List<Candidate> divergent = new ArrayList<Candidate>();
            for (Candidate c : bucket) {
                String id = c.dto.getId();
                if (needsNamespacing && isBare(id)) {
                    id = c.source.getId() + "/" + slug;
                    result.conflicts++;
                    issues.add(SkillIssueDto.warning("name-conflict", id, c.source.getId(),
                            c.dto.getSkillFilePath(), "标识 '" + slug + "' 在 " + bare + " 个来源重复, 已加前缀"));
                }
                id = unique(id, usedIds);
                if (primary == null) {
                    primary = new Candidate(c.dto.toBuilder().id(id).build(), c.source);
                    primary.risk = c.risk;
                    primary.findings = new ArrayList<String>(c.findings);
                    primary.resolvedId = id;
                    continue;
                }
                if (sameContent(primary.dto, c.dto)) {
                    primary.aliases.add(id);
                    primary.installCount += c.dto.getInstallCount();
                    result.deduped++;
                } else {
                    // 同 slug 但内容不同: 独立保留一条, 只记冲突不做折叠.
                    // 上面那次 unique 已经把这条该占的 id 占好了, 再套一层就会长出 "x#2#2" 这种尾巴.
                    SkillDto sibling = c.dto.toBuilder()
                            .id(id)
                            .riskLevel(c.risk)
                            .issues(c.findings)
                            .build();
                    divergent.add(new Candidate(sibling, c.source));
                    result.conflicts++;
                    issues.add(SkillIssueDto.warning("content-divergence", sibling.getId(), c.source.getId(),
                            c.dto.getSkillFilePath(), "同名 skill 内容与 " + primary.resolvedId + " 不一致"));
                }
            }
            if (primary == null) continue;
            List<String> aliases = new ArrayList<String>();
            aliases.add(slug);
            aliases.addAll(primary.aliases);
            SkillDto dto = primary.dto.toBuilder()
                    .aliases(dedupe(aliases))
                    .installCount(primary.dto.getInstallCount() + primary.installCount)
                    .riskLevel(primary.risk)
                    .issues(primary.findings)
                    .build();
            // 权威条目先进表: 注册中心的裸 slug 别名是先到先得, 低优先级的同名单子不能来抢
            result.skills.put(dto.getId(), dto);
            result.sourceById.put(dto.getId(), primary.source);
            for (Candidate sibling : divergent) {
                result.skills.put(sibling.dto.getId(), sibling.dto);
                result.sourceById.put(sibling.dto.getId(), sibling.source);
            }
        }
        return result;
    }

    private static boolean isBare(String id) {
        return id != null && id.indexOf('/') < 0 && id.indexOf(':') < 0;
    }

    private static boolean sameContent(SkillDto a, SkillDto b) {
        return a.getContentHash() != null && a.getContentHash().equals(b.getContentHash());
    }

    private static String unique(String id, Set<String> used) {
        String candidate = id;
        int n = 2;
        while (!used.add(candidate)) {
            candidate = id + "#" + n++;
        }
        return candidate;
    }

    private static List<String> dedupe(List<String> in) {
        List<String> out = new ArrayList<String>();
        for (String s : in) {
            if (s != null && !s.isEmpty() && !out.contains(s)) out.add(s);
        }
        return out;
    }

    private static List<String> head(List<SkillIssueDto> in, int n) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < in.size() && i < n; i++) {
            out.add(in.get(i).getCode());
        }
        return out;
    }

    private static final class Candidate {
        final SkillDto dto;
        final SkillSource source;
        final List<String> aliases = new ArrayList<String>();
        String risk = SkillDto.RISK_UNSCANNED;
        List<String> findings = new ArrayList<String>();
        String resolvedId;
        int installCount;

        Candidate(SkillDto dto, SkillSource source) {
            this.dto = dto;
            this.source = source;
        }
    }

    private static final class Pending {
        final SkillSource source;
        final int depth;

        Pending(SkillSource source, int depth) {
            this.source = source;
            this.depth = depth;
        }
    }

    static final class MergeResult {
        final Map<String, SkillDto> skills = new LinkedHashMap<String, SkillDto>();
        final Map<String, SkillSource> sourceById = new LinkedHashMap<String, SkillSource>();
        int deduped;
        int conflicts;

        Iterator<String> ids() {
            return skills.keySet().iterator();
        }
    }

    /** 目录是否存在, 供 starter 把"本机没装这个平台"的来源安静降级. */
    public static boolean directoryExists(String path) {
        if (path == null || path.isEmpty()) return false;
        try {
            return Files.isDirectory(Paths.get(path));
        } catch (Exception e) {
            return false;
        }
    }
}
