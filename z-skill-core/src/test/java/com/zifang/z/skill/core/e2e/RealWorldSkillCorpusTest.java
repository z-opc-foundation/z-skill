package com.zifang.z.skill.core.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.skill.api.dto.AggregateReportDto;
import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.dto.SkillIssueDto;
import com.zifang.z.skill.api.dto.SkillResourceDto;
import com.zifang.z.skill.api.dto.SkillSearchResultDto;
import com.zifang.z.skill.api.dto.SkillSourceDto;
import com.zifang.z.skill.api.spec.SkillFormat;
import com.zifang.z.skill.api.spec.SkillSpec;
import com.zifang.z.skill.core.aggregate.SkillAggregator;
import com.zifang.z.skill.core.content.SkillContentReader;
import com.zifang.z.skill.core.controller.SkillCompatController;
import com.zifang.z.skill.core.discover.RawSkill;
import com.zifang.z.skill.core.discover.SkillSource;
import com.zifang.z.skill.core.normalize.RegistryIndexNormalizer;
import com.zifang.z.skill.core.normalize.SkillsShApiNormalizer;
import com.zifang.z.skill.core.registry.SkillRegistry;
import com.zifang.z.skill.core.search.SkillSearchEngine;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真语料端到端测试 — 把本仓库里<b>实际存在</b>的 SKILL.md / AGENTS.md / 平铺 .md 喂给
 * 扫描 + 归一化 + 聚合 + 检索 + 风险判级 + 兼容输出全链路.
 *
 * <p>断言取自这些文件<b>今天真实的脏形状</b>(大写驼峰 name、YAML 块标量、完全没有 frontmatter、
 * 中文名随包文件、括号带星号的 allowed-tools、目录名与 name 不一致), 不是规范里的 happy path.
 *
 * <p>语料根目录解析顺序(解析不出来直接失败, 绝不静默跳过 —— 跳过的语料测试等于没有):
 * <ol>
 *   <li>系统属性 {@code z.skill.corpus.root}</li>
 *   <li>{@code <user.dir>/corpus-repo}(离线副本用符号链接指回真实仓)</li>
 *   <li>从 {@code user.dir} 逐级向上, 找同时含 {@code z-skill} 与 {@code z-opc} 的目录
 *       (真实树里从 {@code z-skill/z-skill-core} 上溯两级即命中; 为兼容离线副本, 同一次上溯也认 {@code corpus-repo})</li>
 * </ol>
 *
 * <p>语料是<b>不可信数据</b>: 本类只读它的字节与目录项, 从不执行内容, 从不写回.
 */
public class RealWorldSkillCorpusTest {

    /** 语料里真实存在的目录来源, format 一律 UNKNOWN 让扫描器自己认平台形状. */
    private static final String[][] AUTO_CORPUS = {
            {"corpus-xhs", "z-env/skills/XiaohongshuSkills"},
            {"corpus-zopc-doc-skills", "z-opc/_doc/005_skills"},
            {"corpus-qoder", "z-opc/.qoder/skills"},
            {"corpus-lead", "z-opc-foundation-lead/003_辅助能力"},
            {"corpus-novel", "z-opc/_doc/003_building/skills"},
            {"corpus-designlib", "z-opc/.design_library"},
            {"corpus-playwright", "z-lc/z-lc-admin-ui/node_modules/playwright-core/lib/tools/skills"}
    };

    /** 与 FilesystemScanner.SKIP_DIRS 同口径, 否则我们算出的"应收条目"会比产线多. */
    private static final Set<String> SCANNER_SKIP_DIRS = new TreeSet<String>(Arrays.asList(
            ".git", "node_modules", "target", "build", "dist", ".idea", ".mvn", "__pycache__", ".venv", "venv"));

    private static final String EMITTED_INDEX_URL = "https://zskill.invalid/.well-known/agent-skills/index.json";
    private static final String EMITTED_API_URL = "https://zskill.invalid/skill/compat/skills-sh/api/v1/skills";

    private static volatile Fixture cached;

    private static final class Fixture {
        final Path root;
        final List<SkillSource> sources = new ArrayList<SkillSource>();
        final Map<String, Path> sourceRoots = new LinkedHashMap<String, Path>();
        final SkillRegistry registry = new SkillRegistry();
        final SkillAggregator aggregator;
        final AggregateReportDto report;
        final ObjectMapper mapper = new ObjectMapper();
        /** key = sourceId + "|" + 斜杠化的相对 SKILL.md 路径 —— 本测试独立走盘得到的"应收录条目". */
        final Map<String, Path> expectedSkillMd = new TreeMap<String, Path>();

        Fixture(Path root) {
            this.root = root;
            int priority = 10;
            for (String[] pair : AUTO_CORPUS) {
                Path dir = root.resolve(pair[1]);
                if (!Files.isDirectory(dir)) continue;
                SkillSource s = new SkillSource(pair[0], SkillFormat.UNKNOWN, dir.toString(), priority);
                sources.add(s);
                sourceRoots.put(pair[0], dir);
                priority += 10;
            }
            Path opcRoot = root.resolve("z-opc");
            if (Files.isDirectory(opcRoot)) {
                sources.add(new SkillSource("corpus-agents-md", SkillFormat.AGENTS_MD, opcRoot.toString(), priority)
                        .maxDepth(0));
                sourceRoots.put("corpus-agents-md", opcRoot);
            }
            assertFalse(sources.isEmpty(), "一个语料来源都没挂上, 根=" + root);
            this.aggregator = new SkillAggregator(registry, new ObjectMapper());
            for (SkillSource s : sources) aggregator.addSource(s);
            this.report = aggregator.refresh();
            for (SkillSource s : sources) {
                if (s.getFormat() != SkillFormat.UNKNOWN) continue;
                Path dir = sourceRoots.get(s.getId());
                for (Path p : skillMdUnder(dir, SkillSpec.MAX_SCAN_DEPTH)) {
                    expectedSkillMd.put(s.getId() + "|" + slashify(dir.relativize(p).toString()), p);
                }
            }
        }

        SkillDto byId(String id) {
            SkillDto dto = registry.get(id).orElse(null);
            assertNotNull(dto, "语料聚合结果里没有 id=" + id + "; 实有: " + registry.snapshot().keySet());
            return dto;
        }

        List<SkillIssueDto> issuesFor(String sourceId, String relPath) {
            List<SkillIssueDto> out = new ArrayList<SkillIssueDto>();
            for (SkillIssueDto i : report.getIssues()) {
                if (sourceId.equals(i.getSource()) && relPath.equals(i.getPath())) out.add(i);
            }
            return out;
        }
    }

    private static Fixture fixture() {
        Fixture f = cached;
        if (f != null) return f;
        synchronized (RealWorldSkillCorpusTest.class) {
            if (cached == null) cached = new Fixture(resolveCorpusRootOrFail());
            return cached;
        }
    }

    /** 失败时把三条规则各自试过哪个路径全列出来 —— "静默跳过的语料测试一文不值". */
    private static Path resolveCorpusRootOrFail() {
        List<String> tried = new ArrayList<String>();
        String prop = System.getProperty("z.skill.corpus.root");
        if (prop != null && !prop.trim().isEmpty()) {
            Path p = Paths.get(prop.trim());
            tried.add("(a) -Dz.skill.corpus.root=" + p);
            if (Files.isDirectory(p)) return p;
        } else {
            tried.add("(a) 未设置 -Dz.skill.corpus.root");
        }
        Path userDir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path link = userDir.resolve("corpus-repo");
        tried.add("(b) " + link);
        if (Files.isDirectory(link)) return link;
        for (Path p = userDir; p != null; p = p.getParent()) {
            tried.add("(c) " + p + " {z-skill=" + Files.isDirectory(p.resolve("z-skill"))
                    + ", z-opc=" + Files.isDirectory(p.resolve("z-opc"))
                    + ", corpus-repo=" + Files.isDirectory(p.resolve("corpus-repo")) + "}");
            if (Files.isDirectory(p.resolve("z-skill")) && Files.isDirectory(p.resolve("z-opc"))) return p;
            if (Files.isDirectory(p.resolve("corpus-repo"))) return p.resolve("corpus-repo");
        }
        throw new AssertionError("找不到真实语料根目录, 本测试拒绝静默跳过. 已尝试: " + tried);
    }

    /** 复刻 FilesystemScanner 的可达范围: 跳过 SKIP_DIRS, 限深度, 大小写不敏感认 SKILL.md. */
    private static List<Path> skillMdUnder(Path root, int maxDepth) {
        List<Path> out = new ArrayList<Path>();
        collectSkillMd(root, 0, maxDepth, out);
        Collections.sort(out);
        return out;
    }

    private static void collectSkillMd(Path dir, int depth, int maxDepth, List<Path> out) {
        if (depth > maxDepth) return;
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> children = s.collect(Collectors.toList());
            Collections.sort(children);
            for (Path c : children) {
                String name = c.getFileName().toString();
                if (Files.isDirectory(c)) {
                    if (SCANNER_SKIP_DIRS.contains(name)) continue;
                    collectSkillMd(c, depth + 1, maxDepth, out);
                } else if (Files.isRegularFile(c) && "skill.md".equals(name.toLowerCase(Locale.ROOT))) {
                    out.add(c);
                }
            }
        } catch (IOException e) {
            throw new AssertionError("语料只读遍历失败: " + dir, e);
        }
    }

    private static String slashify(String p) {
        return p == null ? null : p.replace('\\', '/');
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String firstNonBlankLine(Path file) {
        try {
            for (String line : new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\n")) {
                String t = line.trim();
                if (!t.isEmpty()) return t;
            }
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return "";
    }

    private static boolean hasCode(List<SkillIssueDto> issues, String code) {
        for (SkillIssueDto i : issues) if (code.equals(i.getCode())) return true;
        return false;
    }

    /**
     * code 是有界的裸码 —— 口径见 {@link SkillIssueDto}: 不带 severity、不带 {@code spec:} 这类命名空间
     * 前缀、不带取值(清洗结论 "Xiaohongshu->xiaohongshu" 住在 message 里). 所以这里按全等钉 code + severity,
     * 不再用 contains 放过前缀拼法.
     */
    private static void assertIssueRecorded(List<SkillIssueDto> issues, String severity, String code,
                                            String why) {
        for (SkillIssueDto i : issues) {
            if (severity.equals(i.getSeverity()) && code.equals(i.getCode())) return;
        }
        throw new AssertionError(why + "; 实得 " + codeList(issues));
    }

    /** 取某个码的 message, 用来钉"记账有没有把结论写清楚". */
    private static String messageOf(List<SkillIssueDto> issues, String code) {
        for (SkillIssueDto i : issues) if (code.equals(i.getCode())) return i.getMessage();
        return null;
    }

    private static List<String> codeList(List<SkillIssueDto> issues) {
        List<String> out = new ArrayList<String>();
        for (SkillIssueDto i : issues) out.add(i.getSeverity() + ":" + i.getCode() + "@" + i.getPath());
        return out;
    }

    private static int countSeverity(List<SkillIssueDto> issues, String severity) {
        int n = 0;
        for (SkillIssueDto i : issues) if (severity.equals(i.getSeverity())) n++;
        return n;
    }

    private static int countCode(List<SkillIssueDto> issues, String code) {
        int n = 0;
        for (SkillIssueDto i : issues) if (code.equals(i.getCode())) n++;
        return n;
    }

    private static RawSkill jsonRaw(SkillFormat format, String origin, String entryRelative, JsonNode payload) {
        return new RawSkill().format(format).origin(origin).entryRelative(entryRelative)
                .fallbackName(entryRelative) .json(payload);
    }

    // =====================================================================================
    // 0. 语料必须是真的、够多、够脏 —— 否则下面所有护栏都是空跑
    // =====================================================================================

    @Test
    public void corpusIsRealAndNonTrivial() {
        Fixture f = fixture();
        assertTrue(Files.isDirectory(f.root), "语料根目录不是目录: " + f.root);
        assertEquals(AUTO_CORPUS.length + 1, f.sources.size(),
                "语料来源没全挂上, 缺的目录: 根=" + f.root + " 实挂=" + f.sourceRoots.keySet());
        assertTrue(f.expectedSkillMd.size() >= 10,
                "真实语料里只找到 " + f.expectedSkillMd.size() + " 个可达 SKILL.md(<10), 断言没有意义. 根=" + f.root);
        assertEquals(27, f.expectedSkillMd.size(),
                "本仓实测 27 个 SKILL.md, 现在数量变了, 逐条: " + f.expectedSkillMd.keySet());

        int withFm = 0;
        int withoutFm = 0;
        for (Path p : f.expectedSkillMd.values()) {
            if ("---".equals(firstNonBlankLine(p))) withFm++; else withoutFm++;
        }
        assertTrue(withFm >= 8, "语料里带 frontmatter 的真实文件只有 " + withFm + " 个, 覆盖面是假的");
        assertTrue(withoutFm >= 15, "语料里完全没有 frontmatter 的真实文件只有 " + withoutFm + " 个, 覆盖面是假的");
    }

    // =====================================================================================
    // 1. 绝不抛异常、绝不静默消失
    // =====================================================================================

    @Test
    public void noSourceBlowsUp() {
        Fixture f = fixture();
        List<String> failed = new ArrayList<String>();
        for (SkillSourceDto s : f.report.getSources()) {
            if (!"ok".equals(s.getStatus())) failed.add(s.getId() + " -> " + s.getIssues() + " @ " + s.getOrigin());
        }
        assertTrue(failed.isEmpty(),
                "真实语料让来源整体失败(聚合器把异常咽成了 source-failed), 该来源一条都不会被收录: " + failed);
    }

    @Test
    public void everyRealSkillMdBecomesADtoOrLeavesAnIssueNeverVanishing() {
        Fixture f = fixture();
        Set<String> asDto = new LinkedHashSet<String>();
        for (SkillDto d : f.registry.listAll()) {
            String p = d.getSkillFilePath();
            if (p != null && p.toLowerCase(Locale.ROOT).endsWith("skill.md")) asDto.add(d.getSource() + "|" + p);
        }
        Set<String> asError = new LinkedHashSet<String>();
        for (SkillIssueDto i : f.report.getIssues()) {
            String p = i.getPath();
            if ("error".equals(i.getSeverity()) && p != null && p.toLowerCase(Locale.ROOT).endsWith("skill.md")) {
                asError.add(i.getSource() + "|" + p);
            }
        }
        Set<String> vanished = new LinkedHashSet<String>(f.expectedSkillMd.keySet());
        vanished.removeAll(asDto);
        vanished.removeAll(asError);
        assertTrue(vanished.isEmpty(), "这些真实 SKILL.md 既没变成条目也没留下一条 error —— 静默消失: "
                + locate(f, vanished));

        Set<String> contradictory = new LinkedHashSet<String>(asDto);
        contradictory.retainAll(asError);
        assertTrue(contradictory.isEmpty(), "同一份真实文件既算可用条目又被打成 error, 口径自相矛盾: "
                + contradictory);
    }

    private static List<String> locate(Fixture f, Set<String> keys) {
        List<String> out = new ArrayList<String>();
        for (String k : keys) out.add(k + " @ " + f.expectedSkillMd.get(k));
        return out;
    }

    @Test
    public void aggregateTotalsReconcileDiscoveredEqualsNormalizedPlusErrors() {
        Fixture f = fixture();
        int errors = countSeverity(f.report.getIssues(), "error");
        int perSourceProduced = 0;
        Map<String, Integer> producedBySource = new TreeMap<String, Integer>();
        for (SkillSourceDto s : f.report.getSources()) {
            perSourceProduced += s.getSkillCount();
            producedBySource.put(s.getId(), Integer.valueOf(s.getSkillCount()));
        }
        String counts = "raw=" + f.report.getRawCount() + " produced=" + f.report.getProducedCount()
                + " dropped=" + f.report.getDroppedCount() + " skill=" + f.report.getSkillCount()
                + " deduped=" + f.report.getDedupedCount() + " error=" + errors
                + " 逐来源产出=" + producedBySource;

        assertEquals(f.report.getSkillCount(), f.registry.skillCount(), "报告条目数与注册中心实际数不一致");
        assertEquals(f.report.getIssueCount(), f.report.getIssues().size(), "报告 issueCount 与 issues 长度不符");
        assertEquals(f.report.getSourceCount(), f.sources.size(), "来源数与声明数不符");
        assertEquals(f.report.getProducedCount(), perSourceProduced,
                "各来源自报产出之和与报告 producedCount 不符: " + counts);

        // 两条恒等式由 SkillAggregator.refresh() 的计数方式保证, 与语料形状无关(口径见 AggregateReportDto).
        // 守恒要用这几个字段算, 不能拿 error 条数当"丢了几个"的替身.
        assertEquals(f.report.getProducedCount(), f.report.getSkillCount() + f.report.getDedupedCount(),
                "产出/收录/折叠三档不守恒: " + counts);
        // 下面两条是<b>本语料专属</b>的加强式: 语料里只有 markdown 家族 —— 没有一份索引/API 候选能一开多,
        // 也没有被消费的插件清单, 所以"有产出的候选数"恰好等于 producedCount; 又因为 error 档按
        // SkillIssueDto 的口径专指"这条没进目录", 一次丢弃只留一条 error, 于是 errorCount == droppedCount.
        // 一旦往语料里加索引类来源, 这两条就该删掉, 只留上面两条恒等式.
        assertEquals(f.report.getRawCount(), f.report.getProducedCount() + f.report.getDroppedCount(),
                "候选条目不守恒: " + counts + "; issues=" + codeList(f.report.getIssues()));
        assertEquals(f.report.getDroppedCount(), errors,
                "error 条数与丢弃数对不上(语料专属加强式): " + counts
                        + "; error 明细=" + codesWithSeverity(f.report.getIssues(), "error"));

        // 实测 30 个候选 = 27 个 SKILL.md + 2 个平铺 .md(lead 的 踩坑记录/Maven_Central_发布踩坑记录.md
        // 与 005_skills 的 audit-config-incubation-menu-2026Q4.md) + 1 个 AGENTS.md.
        // 那个 AGENTS.md 是 z-opc 根目录指向 _doc/008_misc/ai-docs/AGENTS.md 的<b>符号链接</b> ——
        // 真实仓库就这么写(Codex/Claude 官方推荐), 扫描器一刀切跳过符号链接会让 AGENTS_MD 形状整条消失,
        // 候选数掉到 29 且没有任何一条 issue 说明它去哪了.
        assertTrue(f.report.getRawCount() >= 30, "候选条目只有 " + f.report.getRawCount() + " 个, 语料没被扫全: "
                + counts);
        assertEquals(Integer.valueOf(1), producedBySource.get("corpus-agents-md"),
                "z-opc/AGENTS.md(根目录符号链接)必须是语料里 AGENTS_MD 形状的样本, 该来源产出变了: " + counts);
        assertTrue(f.report.getSkillCount() >= 30, "收录条目只有 " + f.report.getSkillCount() + " 条, 比实测的 30 个候选少: "
                + counts);
        assertEquals(0, f.report.getDedupedCount(),
                "真语料里不存在跨来源同内容文件, 折叠数应为 0; 变了说明去重口径在误杀");
        assertTrue(f.report.getDurationMs() >= 0);
        assertEquals(0, f.report.getDroppedCount(),
                "真实语料的候选现在一个都不该被丢弃(脏文件一律宽松收录), 丢一条就是回归: " + counts);

        // "至少 19 个真实文件没有 frontmatter"这条脏度覆盖声明, 按新的 severity 口径不再等于 19 条 error,
        // 而是"盘上没 frontmatter 的文件, 逐条留一条 warning 痕, 一条 error 都不许有".
        // 这里直接把盘上量出来的数与记账对上 —— 不认任何手写常量.
        int fmLessOnDisk = 0;
        List<String> uncounted = new ArrayList<String>();
        List<String> errored = new ArrayList<String>();
        for (Map.Entry<String, Path> e : f.expectedSkillMd.entrySet()) {
            if ("---".equals(firstNonBlankLine(e.getValue()))) continue;
            fmLessOnDisk++;
            String key = e.getKey();
            List<SkillIssueDto> hits = f.issuesFor(key.substring(0, key.indexOf('|')),
                    key.substring(key.indexOf('|') + 1));
            boolean warned = false;
            for (SkillIssueDto i : hits) {
                if (!"frontmatter-missing".equals(i.getCode())) continue;
                if ("warning".equals(i.getSeverity())) warned = true;
                if ("error".equals(i.getSeverity())) errored.add(key);
            }
            if (!warned) uncounted.add(key);
        }
        // 27 个 SKILL.md 里实测 17 个没有 frontmatter, 再加 2 个平铺 .md = 19 (下限与
        // corpusIsRealAndNonTrivial 的 withoutFm>=15 同口径, 别在这里另写一个更大的手敲数)
        assertTrue(fmLessOnDisk >= 15, "盘上没 frontmatter 的 SKILL.md 只有 " + fmLessOnDisk + " 个, 覆盖面是假的");
        assertTrue(uncounted.isEmpty(),
                "这些真实文件盘上没有 frontmatter, 却没留下一条 warning(脏在哪得让上游看得见): " + uncounted);
        assertTrue(errored.isEmpty(),
                "已收录的条目被记了 error —— 违反 severity 口径(error = 这条没进目录): " + errored);
    }

    private static List<String> codesWithSeverity(List<SkillIssueDto> issues, String severity) {
        List<String> out = new ArrayList<String>();
        for (SkillIssueDto i : issues) {
            if (severity.equals(i.getSeverity())) out.add(i.getCode() + "@" + i.getSource() + ":" + i.getPath());
        }
        return out;
    }


    @Test
    public void frontmatterLessRealFilesAreRejectedWithAnErrorAndTheirExactPath() {
        Fixture f = fixture();
        assertErrorFor(f, "corpus-lead", "audit/SKILL.md", "frontmatter-missing");
        assertErrorFor(f, "corpus-novel", "novel/character-build/SKILL.md", "frontmatter-missing");
        assertErrorFor(f, "corpus-novel", "novel/sub-execution/SKILL.md", "frontmatter-missing");
        // lead 仓的中文名平铺 .md 同样要落账 —— 中文文件名不能把记账也弄丢
        assertErrorFor(f, "corpus-lead", "踩坑记录/Maven_Central_发布踩坑记录.md", "frontmatter-missing");
        assertErrorFor(f, "corpus-zopc-doc-skills", "z-config-incubation/audit-config-incubation-menu-2026Q4.md",
                "frontmatter-missing");
    }

    private static void assertErrorFor(Fixture f, String sourceId, String relPath, String code) {
        List<SkillIssueDto> hits = f.issuesFor(sourceId, relPath);
        assertFalse(hits.isEmpty(), sourceId + "|" + relPath + " 既没成条目也没记任何 issue, 静默消失. 全部 issue="
                + codeList(f.report.getIssues()));
        assertTrue(hasCode(hits, code), relPath + " 的 issue 是 " + codeList(hits) + ", 期望 " + code);
        assertFalse(f.registry.get(relPath).isPresent(), relPath + " 被判 error 却仍在目录里");
    }

    // =====================================================================================
    // 2. 脏名字的清洗结论(逐条取自真实文件)
    // =====================================================================================

    @Test
    public void messyRealNamesNormalizeToSlugWhileKeepingOriginalName() {
        Fixture f = fixture();

        // z-env/skills/XiaohongshuSkills/SKILL.md: name 是 RedBookSkills, 目录名又是第三种写法
        SkillDto xhs = f.byId("redbookskills");
        assertEquals("corpus-xhs", xhs.getSource());
        assertEquals("SKILL.md", xhs.getSkillFilePath(), "来源根本身就是 skill 目录时, 相对路径应只剩文件名");
        assertEquals("redbookskills", xhs.getSlug(), "大写驼峰 name 必须清洗成全小写 slug");
        assertEquals("RedBookSkills", xhs.getName(), "展示用 name 必须保留原文写法, 不能就地改写");
        assertTrue(SkillSpec.isValidName(xhs.getSlug()), "清洗结果仍不合规: " + xhs.getSlug());
        assertEquals(SkillFormat.AGENT_SKILLS, xhs.getFormat());
        List<SkillIssueDto> xhsIssues = f.issuesFor("corpus-xhs", "SKILL.md");
        assertIssueRecorded(xhsIssues, "warning", "name-non-conforming",
                "大写 name 被清洗却没留 warning, 上游作者永远不知道自己写错了");
        // 清洗结论落在 message 里(code 里不许带取值), 但要能让上游作者一眼看出自己那条被改成了什么
        String conformed = messageOf(xhsIssues, "name-non-conforming");
        assertTrue(conformed != null && conformed.contains("RedBookSkills->redbookskills"),
                "name-non-conforming 的 message 要写明清洗结论, 实得: " + conformed);
        assertIssueRecorded(xhsIssues, "warning", "name-dir-mismatch",
                "RedBookSkills 与目录名 XiaohongshuSkills 不一致必须记账");
        assertIssueRecorded(xhsIssues, "info", "description-multiline", "块标量折出的多行 description 必须记 info");

        // z-opc/.design_library/z-opc/SKILL.md: 目录名 z-opc, name 却是 z-opc-design
        SkillDto design = f.byId("z-opc-design");
        assertEquals("z-opc/SKILL.md", design.getSkillFilePath());
        assertTrue(hasCode(f.issuesFor(design.getSource(), "z-opc/SKILL.md"), "name-dir-mismatch"),
                "name 与目录名不一致却没记 warning");
        assertEquals("true", design.getMetadata().get("user-invocable"),
                "规范外的键必须原样进 metadata 透传, 不能丢");

        // 带数字/连字符的真实名字清洗后保持不变
        assertEquals("z-opc-deploy-250", f.byId("z-opc-deploy-250").getSlug());
        assertEquals("playwright-component-testing", f.byId("playwright-component-testing").getSlug());

        List<String> bad = new ArrayList<String>();
        for (SkillDto d : f.registry.listAll()) {
            if (!SkillSpec.isValidName(d.getSlug())) bad.add(d.getId() + " -> " + d.getSlug());
        }
        assertTrue(bad.isEmpty(), "语料里有 slug 没被清洗干净: " + bad);
    }

    @Test
    public void allowedToolsWithParenthesesAndColonsSurviveSplitting() {
        Fixture f = fixture();
        // playwright-cli 真实写法: allowed-tools: Bash(playwright-cli:*) Bash(npx:*) Bash(npm:*)
        assertEquals(Arrays.asList("Bash(playwright-cli:*)", "Bash(npx:*)", "Bash(npm:*)"),
                f.byId("playwright-cli").getAllowedTools(),
                "空格分隔 + 括号内含冒号/星号的 allowed-tools 被切坏了");
        assertEquals(Collections.singletonList("Bash(npx:*)"), f.byId("playwright-trace").getAllowedTools());
        assertTrue(f.byId("z-mist").getAllowedTools().isEmpty(), "没声明 allowed-tools 的文件不该凭空长出来");
    }

    @Test
    public void bodyShapeAndVersionFactsFromRealFilesHold() {
        Fixture f = fixture();
        SkillDto cli = f.byId("playwright-cli");
        assertEquals(420, cli.getBodyLines(),
                "playwright-cli 正文行数(实测 420)变了, 说明正文边界或 frontmatter 收尾判定改了");
        assertTrue(cli.getBodyChars() > 8000, "正文字符数明显偏小: " + cli.getBodyChars());

        for (SkillDto d : f.registry.listAll()) {
            if (d.getBodyLines() > SkillSpec.BODY_LINES_SOFT_MAX) {
                // code 是有界的裸码: 不带 spec: 这类命名空间前缀(口径见 SkillIssueDto)
                assertTrue(hasCode(f.issuesFor(d.getSource(), d.getSkillFilePath()), "body-too-long"),
                        d.getId() + " 正文 " + d.getBodyLines() + " 行超规范建议却没记 warning");
                assertEquals(0, countSeverity(f.issuesFor(d.getSource(), d.getSkillFilePath()), "error"),
                        d.getId() + " 正文超长但仍被收录, 按 severity 口径不许记 error");
            }
        }
        // metadata 嵌套里的 version 要抬到顶层(005_skills 四个真实文件都这么写)
        assertEquals("1.0.0", f.byId("z-schedule").getVersion());
        assertEquals("1.0.0", f.byId("z-mist").getVersion());
        assertEquals("z-ops", f.byId("z-opc-deploy-250").getMetadata().get("module"));
        assertEquals("/api/ops", f.byId("z-opc-deploy-250").getMetadata().get("base_path"),
                "规范外键要原样透传, 哪怕是长得像路径的值");

        // 真实的 z-opc/AGENTS.md 完全没有 frontmatter, 按 AGENTS_MD 形状仍须收录
        // (它在盘上是指向 _doc/008_misc/ai-docs/AGENTS.md 的符号链接 —— 真实仓库的标准写法)
        SkillDto agents = f.byId("z-opc-instructions");
        assertEquals("AGENTS.md", agents.getSkillFilePath());
        assertEquals(SkillFormat.AGENTS_MD, agents.getFormat());
        assertEquals("project-instructions", agents.getCategory());
        assertFalse(agents.getDescription().isEmpty(),
                "没 frontmatter 的 AGENTS.md 必须从正文兜出描述, 否则整条不可检索");
        // AGENTS_MD 这个形状没有任何 frontmatter 契约(Codex 的 AGENTS.md 就是纯正文;
        // MarkdownSkillNormalizer#requiresFrontmatter 只要求 agent-skills/平铺/cursor/copilot 四种),
        // 所以缺 frontmatter 既不记 error 也不记 warning —— 但它确实进了目录, error 一记就是自己打收录数.
        List<SkillIssueDto> agentsIssues = f.issuesFor("corpus-agents-md", "AGENTS.md");
        assertEquals(0, countSeverity(agentsIssues, "error"),
                "AGENTS.md 已按本形状降级收录, 却记了 error: " + codeList(agentsIssues));
        assertFalse(hasCode(agentsIssues, "frontmatter-missing"),
                "AGENTS_MD 形状不该按 agent-skills 的尺子记它缺 frontmatter: " + codeList(agentsIssues));
    }

    // =====================================================================================
    // 3. 中文与 UTF-8
    // =====================================================================================

    @Test
    public void chineseDescriptionsSurviveUtf8AndAreFindableByChineseQuery() {
        Fixture f = fixture();
        SkillDto mist = f.byId("z-mist");
        assertTrue(mist.getDescription().contains("密钥管理中心"), "中文描述被解码破坏了: " + mist.getDescription());
        assertTrue(mist.getDescription().contains("AES-256 加密"), "中英混排片段掉了: " + mist.getDescription());
        assertFalse(mist.getDescription().contains("\n"), "折行没收口: " + mist.getDescription());
        assertFalse(mist.getDescription().contains("  "), "空白折叠不彻底");

        SkillDto xhs = f.byId("redbookskills");
        assertTrue(xhs.getDescription().contains("小红书"), "YAML 块标量 | 的中文没解析出来: " + xhs.getDescription());
        assertTrue(xhs.getDescription().contains("适用场景：发布图文"),
                "块标量的第二续行被截断了: " + xhs.getDescription());
        assertEquals("发布内容到小红书", xhs.getMetadata().get("trigger"),
                "嵌套 metadata 里的中文值也要原样透传");
        assertNull(xhs.getTrigger(), "嵌套层的 trigger 不该被误当顶层字段");

        SkillDto task = f.byId("z-task");
        assertTrue(task.getDescription().contains("任务协作平台"));
        assertFalse(task.getName().contains("任务"), "name 是标识, 中文只该出现在描述里");

        SkillSearchEngine engine = new SkillSearchEngine(f.registry);
        SkillSearchResultDto one = engine.search(new SkillSearchEngine.Query().q("密钥").perPage(50));
        List<String> ids = new ArrayList<String>();
        for (SkillDto d : one.getSkills()) ids.add(d.getId());
        assertTrue(ids.contains("z-mist"), "中文二元组检索没命中 z-mist(实测该词就在其描述里), 命中集=" + ids);
        assertEquals("fuzzy", one.getSearchType(), "单个中文词应判 fuzzy");
        assertEquals("密钥", one.getQuery(), "query 没回显");
        assertTrue(one.getTotal() >= 1);

        SkillSearchResultDto many = engine.search(new SkillSearchEngine.Query().q("任务 调度").perPage(50));
        Set<String> hit = new LinkedHashSet<String>();
        for (SkillDto d : many.getSkills()) hit.add(d.getId());
        assertTrue(hit.contains("z-task") && hit.contains("z-schedule"), "多中文词检索退化, 命中集=" + hit);

        // 中文文件名的随包文件也要原样活着
        List<String> paths = new ArrayList<String>();
        for (SkillResourceDto r : f.byId("z-opc-deploy-250").getResources()) paths.add(r.getPath());
        assertTrue(paths.contains("直接部署250-opc前后端.md"), "中文名随包文件被丢了或改了编码: " + paths);
    }

    // =====================================================================================
    // 4. id 唯一性与冲突消解
    // =====================================================================================

    @Test
    public void idsAreUniqueAcrossAllRealSourcesWithZeroUnresolvedCollisions() {
        Fixture f = fixture();
        Set<String> seen = new LinkedHashSet<String>();
        List<String> dup = new ArrayList<String>();
        for (SkillDto d : f.registry.listAll()) {
            assertNotNull(d.getId(), "条目没有 id, slug=" + d.getSlug());
            if (!seen.add(d.getId())) dup.add(d.getId());
        }
        assertTrue(dup.isEmpty(), "注册中心里出现重复 id: " + dup);
        assertEquals(f.registry.listAll().size(), f.registry.snapshot().size());

        List<String> malformed = new ArrayList<String>();
        for (String id : f.registry.snapshot().keySet()) {
            if (id.contains("#") || id.startsWith("-") || id.endsWith("-")) malformed.add(id);
        }
        assertTrue(malformed.isEmpty(),
                "聚合器用 #N 后缀硬压冲突(或产出畸形 id), 说明有真实来源撞名没被命名空间化: " + malformed);
        assertEquals(0, f.report.getConflictCount(),
                "真语料跨来源同名冲突实测为 0, 现在变了, 逐条: " + codeList(f.report.getIssues()));

        // 每个条目都能按 id / slug / 原文写法 name 三种寻址找回同一落点
        for (SkillDto d : f.registry.listAll()) {
            assertEquals(d.getId(), f.registry.get(d.getId()).map(SkillDto::getId).orElse(null), "id 寻址失败 " + d);
            assertEquals(d.getId(), f.registry.get(d.getSlug()).map(SkillDto::getId).orElse(null), "slug 寻址失败 " + d);
            assertEquals(d.getId(), f.registry.get(d.getName()).map(SkillDto::getId).orElse(null),
                    "原文 name 寻址失败: name=" + d.getName() + " 应指向 " + d.getId());
        }
    }

    // =====================================================================================
    // 5. 随包资源
    // =====================================================================================

    @Test
    public void resourcesListRealBundledFilesWithoutAbsolutePathOrTraversal() {
        Fixture f = fixture();
        SkillContentReader reader = new SkillContentReader();
        int scripts = 0;
        int references = 0;
        List<String> escapes = new ArrayList<String>();
        List<String> ghosts = new ArrayList<String>();
        List<String> mislabeled = new ArrayList<String>();
        for (SkillDto d : f.registry.listAll()) {
            Path entry = reader.localEntry(d).orElse(null);
            Path dir = entry == null ? null : entry.getParent();
            for (SkillResourceDto r : d.getResources()) {
                String p = r.getPath();
                assertNotNull(p, d.getId() + " 的资源没有路径");
                String s = slashify(p);
                if (p.startsWith("/") || new File(p).isAbsolute()) escapes.add(d.getId() + " -> " + p + " 绝对路径");
                if (p.indexOf('\\') >= 0 || !p.equals(s)) escapes.add(d.getId() + " -> " + p + " 反斜杠");
                if (s.equals("..") || s.startsWith("../") || s.contains("/../") || s.endsWith("/.."))
                    escapes.add(d.getId() + " -> " + p + " 目录穿越");
                if (p.isEmpty()) escapes.add(d.getId() + " -> 空路径");
                String head = s.contains("/") ? s.substring(0, s.indexOf('/')) : "";
                String expectedKind;
                if (head.equals("scripts") || head.equals("bin")) expectedKind = "script";
                else if (head.equals("references") || head.equals("reference")) expectedKind = "reference";
                else if (head.equals("assets") || head.equals("images")) expectedKind = "asset";
                else expectedKind = "other";
                if (!expectedKind.equals(r.getKind())) {
                    mislabeled.add(d.getId() + " -> " + p + " kind=" + r.getKind() + " 应为 " + expectedKind);
                }
                if ("script".equals(r.getKind())) scripts++;
                if ("reference".equals(r.getKind())) references++;
                if (dir != null && !Files.isRegularFile(dir.resolve(s))) ghosts.add(d.getId() + " -> " + p);
            }
        }
        assertTrue(escapes.isEmpty(), "语料资源里出现了越界路径: " + escapes);
        assertTrue(mislabeled.isEmpty(), "kind 与路径首段不匹配: " + mislabeled);
        assertTrue(ghosts.isEmpty(), "资源清单里的文件在盘上不存在(清单没指向真实随包文件): " + ghosts);
        assertTrue(scripts >= 7, "XiaohongshuSkills 有 7 个 scripts/*.py, 实得 script 类资源 " + scripts);
        assertTrue(references >= 17, "z-opc-conventions(8)+playwright-cli(9) 的 references 至少 17, 实得 " + references);

        Set<String> xhs = new LinkedHashSet<String>();
        for (SkillResourceDto r : f.byId("redbookskills").getResources()) xhs.add(slashify(r.getPath()));
        assertTrue(xhs.contains("scripts/publish_pipeline.py"), "真实脚本没进清单: " + xhs);
        for (SkillResourceDto r : f.byId("redbookskills").getResources()) {
            if (r.getPath().equals("scripts/publish_pipeline.py")) {
                assertTrue(r.getSizeBytes() > 1000, "资源要带真实字节数, 实得 " + r.getSizeBytes());
            }
        }
        assertFalse(containsPrefix(xhs, "__pycache__/"), "构建产物 __pycache__ 被当成 skill 资源: " + xhs);
        assertFalse(containsPrefix(xhs, "scripts/__pycache__/"), "嵌套 __pycache__ 没被跳过: " + xhs);
        assertFalse(containsPrefix(xhs, ".git/"), "版本库内部文件被当成 skill 资源: " + xhs);
        assertFalse(xhs.contains("SKILL.md"), "主文件不能既算正文又算资源");
    }

    private static boolean containsPrefix(Set<String> paths, String prefix) {
        for (String p : paths) if (p.startsWith(prefix)) return true;
        return false;
    }

    // =====================================================================================
    // 6. 内容指纹
    // =====================================================================================

    @Test
    public void contentHashesArePresentCoverRealBytesDistinctAndStableAcrossRuns() {
        Fixture f = fixture();
        Map<String, String> firstRun = new TreeMap<String, String>();
        Map<String, List<String>> byHash = new TreeMap<String, List<String>>();
        for (SkillDto d : f.registry.listAll()) {
            String hash = d.getContentHash();
            assertNotNull(hash, d.getId() + " 没有 contentHash, 上游漂移检测失效");
            assertTrue(hash.matches("[0-9a-f]{64}"), d.getId() + " 的 contentHash 不是 sha256 hex: " + hash);
            firstRun.put(d.getId(), hash);
            List<String> bucket = byHash.get(hash);
            if (bucket == null) {
                bucket = new ArrayList<String>();
                byHash.put(hash, bucket);
            }
            bucket.add(d.getId());
        }
        assertEquals(firstRun.size(), byHash.size(),
                "不同内容折叠成同一个 hash(去重会误杀): " + collisions(byHash));

        // hash 必须真的是那份文件的字节指纹
        for (Map.Entry<String, Path> e : f.expectedSkillMd.entrySet()) {
            String key = e.getKey();
            String sourceId = key.substring(0, key.indexOf('|'));
            String rel = key.substring(key.indexOf('|') + 1);
            SkillDto dto = f.registry.listAll().stream()
                    .filter(d -> sourceId.equals(d.getSource()) && rel.equals(d.getSkillFilePath()))
                    .findFirst().orElse(null);
            if (dto == null) continue;
            try {
                assertEquals(sha256(Files.readAllBytes(e.getValue())), dto.getContentHash(),
                        "contentHash 不等于该 SKILL.md 全文的 SHA-256: " + e.getValue());
            } catch (IOException io) {
                throw new AssertionError(io);
            }
        }

        AggregateReportDto second = f.aggregator.refresh();
        assertEquals(second.getSkillCount(), f.registry.skillCount(), "重跑一次聚合条目数漂了");
        for (SkillDto d : f.registry.listAll()) {
            assertEquals(firstRun.get(d.getId()), d.getContentHash(),
                    "同一份文件两次聚合 hash 不稳定, 漂移检测会天天报警: " + d.getId());
        }
        assertTrue(f.registry.getChanged().isEmpty(), "内容没变却报 changed: " + f.registry.getChanged());
        assertTrue(f.registry.getRemoved().isEmpty(), "重跑聚合居然少了条目: " + f.registry.getRemoved());
    }

    private static List<String> collisions(Map<String, List<String>> byHash) {
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, List<String>> e : byHash.entrySet()) {
            if (e.getValue().size() > 1) out.add(e.getKey().substring(0, 8) + "=" + e.getValue());
        }
        return out;
    }

    // =====================================================================================
    // 7. 风险判级
    // =====================================================================================

    @Test
    public void riskScannerJudgesEveryCorpusSkillAndNeverCallsProseCritical() {
        Fixture f = fixture();
        List<String> allowed = Arrays.asList(SkillDto.RISK_SAFE, SkillDto.RISK_LOW, SkillDto.RISK_MEDIUM,
                SkillDto.RISK_HIGH, SkillDto.RISK_CRITICAL, SkillDto.RISK_UNSCANNED);
        List<String> missing = new ArrayList<String>();
        List<String> critical = new ArrayList<String>();
        Map<String, List<String>> tripped = new TreeMap<String, List<String>>();
        for (SkillDto d : f.registry.listAll()) {
            String risk = d.getRiskLevel();
            assertNotNull(risk, d.getId() + " 没有判级");
            assertTrue(allowed.contains(risk), d.getId() + " 判级值非法: " + risk);
            assertFalse(SkillDto.RISK_UNSCANNED.equals(risk),
                    d.getId() + " 挂了扫描器的聚合链路里仍是 unscanned, 判级没回填");
            if (SkillDto.RISK_CRITICAL.equals(risk)) {
                critical.add(d.getId() + " " + d.getIssues() + " @ " + d.getSkillFilePath());
            }
            for (String finding : d.getIssues()) {
                if (finding.startsWith("info:")) continue;
                String body = finding.substring(finding.indexOf(':') + 1);
                int arrow = body.indexOf(" -> ");
                String rule = arrow > 0 ? body.substring(0, arrow) : body;
                List<String> hits = tripped.get(rule);
                if (hits == null) {
                    hits = new ArrayList<String>();
                    tripped.put(rule, hits);
                }
                hits.add(d.getId() + ":" + d.getSkillFilePath());
            }
        }
        assertTrue(critical.isEmpty(),
                "真实语料里的散文/markdown 代码块被判成 critical —— 规则过火: " + critical);

        List<String> overstated = new ArrayList<String>();
        for (String id : Arrays.asList("z-mist", "z-task", "z-schedule", "redbookskills", "z-opc-conventions",
                "z-opc-design")) {
            String risk = f.byId(id).getRiskLevel();
            if (SkillDto.RISK_HIGH.equals(risk) || SkillDto.RISK_CRITICAL.equals(risk)) {
                overstated.add(id + "=" + risk + " " + f.byId(id).getIssues());
            }
        }
        assertTrue(overstated.isEmpty(), "纯说明性 skill 被判成 high/critical, 规则过火: " + overstated);

        assertFalse(tripped.isEmpty(), "真语料一条风险规则都没命中 —— 说明扫描根本没跑起来");
        assertTrue(tripped.containsKey("installs-third-party") || tripped.containsKey("npx-fetch-run"),
                "playwright 语料文档里的 npm install -g / npx 该被 medium 抓到, 实际命中表=" + tripped);
    }

    // =====================================================================================
    // 8. 兼容输出层自洽: 把我们自己吐的 JSON 再喂回适配器
    // =====================================================================================

    @Test
    public void emittedWellKnownIndexRoundTripsThroughRegistryIndexNormalizer() {
        Fixture f = fixture();
        SkillCompatController controller = controller(f);
        Map<String, Object> index = controller.wellKnownIndex();
        assertEquals(f.registry.skillCount(), ((List<?>) index.get("skills")).size(),
                "index.json 吐出的条目数与注册中心不一致");

        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
        SkillSource emitted = new SkillSource("zskill-emitted", SkillFormat.REGISTRY_INDEX, EMITTED_INDEX_URL, 40);
        JsonNode payload = f.mapper.valueToTree(index);
        List<SkillDto> back = new RegistryIndexNormalizer(f.mapper).normalize(
                jsonRaw(SkillFormat.REGISTRY_INDEX, EMITTED_INDEX_URL, ".well-known/agent-skills/index.json", payload),
                emitted, issues);

        assertEquals(f.registry.skillCount(), back.size(),
                "我们自吐的 index.json 有条目被自家适配器丢掉(兼容承诺破了): 回读=" + slugs(back)
                        + " 原目录=" + f.registry.snapshot().keySet());
        Map<String, SkillDto> bySlug = new TreeMap<String, SkillDto>();
        for (SkillDto d : f.registry.listAll()) bySlug.put(d.getSlug(), d);
        for (SkillDto d : back) {
            SkillDto src = bySlug.get(d.getSlug());
            assertNotNull(src, "回读出的 slug 在原目录里不存在: " + d.getSlug());
            assertEquals(src.getSlug(), d.getSlug());
            assertEquals(src.getDescription(), d.getDescription(),
                    "中文描述在 index.json 往返中损坏或截断: " + src.getSlug());
            assertEquals("well-known", d.getSourceType());
            assertEquals(SkillFormat.REGISTRY_INDEX, d.getFormat());
            assertNotNull(d.getContentHash());
            assertTrue(d.getOrigin().startsWith(EMITTED_INDEX_URL.substring(0, EMITTED_INDEX_URL.lastIndexOf('/') + 1)),
                    "index 条目的 origin 没落在索引 URL 的同目录下: " + d.getOrigin());
            assertFalse(d.getResources().isEmpty(), src.getId() + " 往返后一个文件都没有");
            for (SkillResourceDto r : d.getResources()) {
                String p = slashify(r.getPath());
                assertFalse(p.startsWith("/") || p.contains(".."), "往返后资源路径越界: " + p);
            }
        }
        SkillDto mist = null;
        for (SkillDto d : back) if ("z-mist".equals(d.getSlug())) mist = d;
        assertNotNull(mist, "z-mist 没被回读出来");
        assertEquals("1.0.0", mist.getVersion(), "version 走 index.json 的 metadata 通道必须存活");
        // index.json 形状只有 name(=slug)槽位: 原文大写写法在这里注定降级为 slug, 这是形状边界, 钉住它
        SkillDto xhs = null;
        for (SkillDto d : back) if ("redbookskills".equals(d.getSlug())) xhs = d;
        assertNotNull(xhs);
        assertEquals("redbookskills", xhs.getName(),
                "index.json 的 name 槽位按规范就是 slug; 原文写法 RedBookSkills 只能靠详情接口带回");
        // AGENTS_MD 形状的那条(语料里就是 z-opc/AGENTS.md)也必须照样回读进来.
        // 它盘上没有 SKILL.md, 但发射器 SkillCompatController#advertisedFiles 承诺"files[0] 就是这条
        // skill 自己的主文件位", 缺位时按 Agent Skills 的规范布局补一个 <slug>/SKILL.md ——
        // 所以"AGENTS.md 条目必然触发 index-missing-skill-md"这个前提是错的: 真正该钉的是
        // "缺主文件位才记 warning, 一条都不许丢", 两头账必须双向对上.
        SkillDto roundTrippedAgents = null;
        for (SkillDto d : back) if ("z-opc-instructions".equals(d.getSlug())) roundTrippedAgents = d;
        assertNotNull(roundTrippedAgents,
                "AGENTS_MD 形状的那条没能经自家 index.json 回读进目录(兼容承诺破了): 回读=" + slugs(back));
        List<String> agentsFiles = new ArrayList<String>();
        for (SkillResourceDto r : roundTrippedAgents.getResources()) agentsFiles.add(slashify(r.getPath()));
        assertTrue(agentsFiles.contains("AGENTS.md"),
                "补出来的 SKILL.md 位不该把真实主文件顶掉, AGENTS.md 自己必须还在清单里: " + agentsFiles);

        Set<String> entriesWithoutSkillMdSlot = new LinkedHashSet<String>();
        for (JsonNode node : payload.path("skills")) {
            boolean sawSkillMd = false;
            for (JsonNode file : node.path("files")) {
                String p = file.asText();
                if (p == null) continue;
                String leaf = p.substring(p.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                if ("skill.md".equals(leaf)) sawSkillMd = true;
            }
            if (!sawSkillMd) entriesWithoutSkillMdSlot.add(node.path("name").asText());
        }
        assertEquals(0, entriesWithoutSkillMdSlot.size(),
                "自吐 index.json 有条目连主文件位都没给, 客户端拿 files[0] 当正文位置会扑空: "
                        + entriesWithoutSkillMdSlot);
        assertEquals(entriesWithoutSkillMdSlot.size(), countCode(issues, "index-missing-skill-md"),
                "index-missing-skill-md 的条数与真正缺主文件位的条目数对不上: 缺位条目="
                        + entriesWithoutSkillMdSlot + " 记账=" + codeList(issues));
        assertEquals(0, countSeverity(issues, "error"),
                "自家 index.json 让自家适配器报 error: " + codeList(issues));
    }

    @Test
    public void emittedSkillsShDetailRoundTripsEveryMarketedField() {
        Fixture f = fixture();
        SkillCompatController controller = controller(f);
        SkillsShApiNormalizer normalizer = new SkillsShApiNormalizer(f.mapper);
        SkillSource emitted = new SkillSource("zskill-emitted", SkillFormat.SKILLS_SH_API, EMITTED_API_URL, 40);

        for (SkillDto src : f.registry.listAll()) {
            Map<String, Object> detail = controller.shDetail(src.getSlug(), false);
            assertEquals(src.getSource() + "/" + src.getSlug(), detail.get("id"),
                    "skills.sh 的外部 id 写法不是 {source}/{slug}");
            List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
            List<SkillDto> back = normalizer.normalize(
                    jsonRaw(SkillFormat.SKILLS_SH_API, EMITTED_API_URL + "/" + src.getSlug(), "detail.json",
                            f.mapper.valueToTree(detail)), emitted, issues);
            assertEquals(1, back.size(), src.getId() + " 的详情响应回读出 " + back.size() + " 条: " + codeList(issues));
            SkillDto d = back.get(0);
            assertEquals(src.getSlug(), d.getSlug(), "slug 在 skills.sh 详情往返中变了");
            assertEquals(src.getName(), d.getName(), "原文 name(含大写)在详情往返里被清洗掉了");
            assertEquals(src.getDescription(), d.getDescription(), "描述在详情往返中丢失/截断: " + src.getId());
            assertEquals(src.getContentHash(), d.getContentHash(),
                    "hash 字段没把我们算的指纹带回去 —— 客户端无法校验内容一致性: " + src.getId());
            assertEquals(src.getRiskLevel(), d.getRiskLevel(), "riskLevel 在往返中被降级/夸大: " + src.getId());
            assertEquals(src.getSourceType(), d.getSourceType(), "sourceType 在往返中丢了");
            assertEquals(src.getInstallCount(), d.getInstallCount());
            // 兼容面也是公共面: file:/绝对路径 的来源不给 installUrl, 按 skills.sh 自己的口径回落成
            // {source}/{slug}; 远端来源(owner/repo@skill 或 https)必须原样带回, 否则客户端没地方装.
            String origin = src.getOrigin();
            boolean localOrigin = origin != null
                    && (origin.toLowerCase(Locale.ROOT).startsWith("file:") || origin.startsWith("/"));
            assertEquals(localOrigin ? src.getSource() + "/" + src.getSlug() : origin, d.getOrigin(),
                    localOrigin ? "本机路径漏进了公共面的 installUrl: " + src.getId()
                            : "installUrl 没带回来: " + src.getId());
            assertFalse(String.valueOf(detail.get("installUrl")).contains("file:"),
                    "详情响应里还有 file: 地址: " + src.getId());
            assertTrue(issues.isEmpty(), "自吐的详情形状让自家适配器记了问题: " + src.getId() + " " + codeList(issues));
            boolean sawMainFile = false;
            for (SkillResourceDto r : d.getResources()) {
                if (slashify(r.getPath()).toLowerCase(Locale.ROOT).endsWith("skill.md")
                        || slashify(r.getPath()).endsWith("AGENTS.md")) sawMainFile = true;
                assertFalse(slashify(r.getPath()).startsWith("/"), "详情往返后路径越界: " + r.getPath());
            }
            assertTrue(sawMainFile, src.getId() + " 的 files[] 里连主文件都没有");
        }

        // {source}/{slug} 这种外部写法必须能直接命中同一条
        SkillDto xhs = f.byId("redbookskills");
        String external = xhs.getSource() + "/" + xhs.getSlug();
        assertEquals("redbookskills", controller.shDetail(external, false).get("slug"),
                "skills.sh 客户端用 {source}/{slug} 寻址查不到我们的条目");
    }

    @Test
    public void emittedSkillsShSearchShapeKeepsEntriesAndMarksDegradedFields() {
        Fixture f = fixture();
        SkillCompatController controller = controller(f);
        Map<String, Object> search = controller.shSearch("密钥", 50, null);
        assertEquals("密钥", search.get("query"));
        List<?> nodes = (List<?>) search.get("skills");
        assertFalse(nodes.isEmpty(), "中文查询在自家 skills.sh 兼容层上完全搜不到东西");
        assertEquals(nodes.size(), ((Number) search.get("count")).intValue());
        assertEquals("fuzzy", search.get("searchType"));

        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
        SkillSource emitted = new SkillSource("zskill-emitted", SkillFormat.SKILLS_SH_API, EMITTED_API_URL, 40);
        List<SkillDto> back = new SkillsShApiNormalizer(f.mapper).normalize(
                jsonRaw(SkillFormat.SKILLS_SH_API, EMITTED_API_URL + "/search", "search.json",
                        f.mapper.valueToTree(search)), emitted, issues);
        assertEquals(nodes.size(), back.size(), "search 响应里的条目被自家适配器丢了");
        Set<String> slugs = new LinkedHashSet<String>();
        for (SkillDto d : back) slugs.add(d.getSlug());
        assertTrue(slugs.contains("z-mist"), "中文命中的条目回读后不见了: " + slugs);
        // search 形状不带 description: 必须降级收录 + 记 warning, 而不是静默丢弃
        assertTrue(hasIssueAmong(issues, "description-missing"),
                "search 形状缺描述却没记账, 消费方无从知道这是降级条目: " + codeList(issues));
        for (SkillDto d : back) {
            assertTrue(d.getDescription().isEmpty(), "search 形状凭空造出了描述: " + d.getSlug());
            assertEquals(0, countSeverity(issues, "error"), "search 往返居然报 error: " + codeList(issues));
        }
    }

    @Test
    public void compatLayerServesRealCorpusContentAndAuditShape() {
        Fixture f = fixture();
        SkillCompatController controller = controller(f);
        Map<String, Object> detail = controller.shDetail("redbookskills", true);
        List<?> files = (List<?>) detail.get("files");
        Object contents = ((Map<?, ?>) files.get(0)).get("contents");
        assertTrue(contents instanceof String, "files=true 时必须回正文, 兼容客户端靠这个渲染详情页");
        assertTrue(((String) contents).contains("RedBookSkills"), "回读的正文不是那份真实文件的内容");

        Map<String, Object> audit = controller.shAudit("z-mist");
        assertEquals("z-mist", audit.get("slug"));
        List<?> audits = (List<?>) audit.get("audits");
        assertEquals(1, audits.size());
        Map<?, ?> one = (Map<?, ?>) audits.get(0);
        assertEquals("z-skill-static", one.get("provider"), "audit 形状里的 provider 挪位了");
        // 对外形状走第三方那套字典: skills.sh 的审计档位是<b>大写</b>(SAFE/LOW/MEDIUM/NONE),
        // 内部 SkillDto.RISK_SAFE="safe" 只在自家 API 里用(SkillCompatController#externalRisk 负责换形).
        assertEquals("SAFE", one.get("riskLevel"));
        // 状态字典同理: 实测 skills.sh 用 pass/warn/fail, 不是 passed/flagged
        // (SkillCompatController#auditStatus; 没扫过才给扩展值 pending).
        assertEquals("pass", one.get("status"), "safe 判级在 audit 形状里被翻译成了别的值: " + one);
        assertFalse(String.valueOf(one.get("summary")).isEmpty(), "audit summary 必须给人类可读的结论");

        List<Map<String, Object>> qoder = controller.qoderSkills();
        assertEquals(f.registry.skillCount(), qoder.size(), "Qoder 插件目录视图条目数与注册中心不一致");
        Set<String> names = new LinkedHashSet<String>();
        for (Map<String, Object> node : qoder) names.add(String.valueOf(node.get("name")));
        // Qoder 的插件内 skill 标识是 <extensionId>:<skillName> 两段(SkillCompatController#qoderName),
        // 我们的 source 就是那个 extensionId —— 所以这里丢的不是"redbookskills"这个名字, 而是它的命名空间.
        SkillDto xhsEntry = f.byId("redbookskills");
        assertTrue(names.contains(xhsEntry.getSource() + ":redbookskills"),
                "Qoder 视图丢了 XiaohongshuSkills 这条真实 skill(标识 = <source>:<slug>): " + names);
        List<String> unnamespaced = new ArrayList<String>();
        for (String n : names) if (!n.contains(":")) unnamespaced.add(n);
        assertTrue(unnamespaced.isEmpty(), "Qoder 视图里出现了没带 extensionId 命名空间的标识: " + unnamespaced);

        // 兼容层不能凭空造条目
        Map<String, Object> index = controller.wellKnownIndex();
        assertEquals(((List<?>) index.get("skills")).size(), ((Number) index.get("total")).intValue());
    }

    private static SkillCompatController controller(Fixture f) {
        return new SkillCompatController(f.registry, new SkillSearchEngine(f.registry), new SkillContentReader());
    }

    private static boolean hasIssueAmong(List<SkillIssueDto> issues, String code) {
        for (SkillIssueDto i : issues) if (code.equals(i.getCode())) return true;
        return false;
    }

    private static List<String> slugs(List<SkillDto> in) {
        List<String> out = new ArrayList<String>();
        for (SkillDto d : in) out.add(d.getSlug());
        return out;
    }

    // =====================================================================================
    // 9. 语料只读
    // =====================================================================================

    @Test
    public void corpusIsOnlyEverReadNeverMutated() {
        Fixture f = fixture();
        try {
            byte[] bytes = Files.readAllBytes(f.root.resolve("z-opc/_doc/005_skills/z-mist/SKILL.md"));
            assertEquals(5923, bytes.length, "语料文件字节数变了 —— 有测试在往真实仓里写东西");
            assertFalse(Files.exists(f.root.resolve("z-opc/_doc/005_skills/.zskill-test-artifact")),
                    "测试往语料目录里落了产物");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
