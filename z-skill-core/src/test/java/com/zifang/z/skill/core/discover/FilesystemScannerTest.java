package com.zifang.z.skill.core.discover;

import com.zifang.z.skill.api.dto.SkillIssueDto;
import com.zifang.z.skill.api.dto.SkillResourceDto;
import com.zifang.z.skill.api.spec.SkillFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发现层: {@link FilesystemScanner} 的目录形状识别测试.
 *
 * <p>钉住几件事: {@code <name>/SKILL.md} 才算 skill 本体(README / references 旁的 .md 不算)、
 * 资源清单带斜杠相对路径与字节大小且可复现、maxDepth 的层级口径、坏语料只记账不抛异常、
 * 以及来源路径归一化后不得越界(含目录软链)。
 */
public class FilesystemScannerTest {

    private static final Path FIXTURES = fixturesRoot();

    private final FilesystemScanner scanner = new FilesystemScanner();

    @TempDir
    Path tmp;

    // ---------------------------------------------------------------- 规范语料

    @Test
    public void findsExactlyTheSkillDirsAndIgnoresSidecarMarkdown() throws IOException {
        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
        List<RawSkill> raws = scan(SkillFormat.UNKNOWN, fx("agent-skills/conforming"), "conforming", issues);

        assertEquals(Arrays.asList(
                        "RedBookSkills/SKILL.md",
                        "broken-missing-desc/SKILL.md",
                        "broken-no-fence/SKILL.md",
                        "data-import/SKILL.md",
                        "pdf-processing/SKILL.md"),
                rels(raws), "conforming 语料应刚好收 5 个 SKILL.md 目录(README.md 与顶层 references/loose.md 不算)");
        assertEquals(Collections.singletonList(SkillFormat.AGENT_SKILLS.name()), distinctFormats(raws),
                "发现的每个条目都必须是 AGENT_SKILLS");
        assertTrue(issues.isEmpty(), "目录形状本身不该产生 issue: " + codes(issues));
    }

    @Test
    public void skillDirResourcesCarrySlashRelativePathsKindsAndSizes() throws IOException {
        RawSkill pdf = byRel(scan(SkillFormat.UNKNOWN, fx("agent-skills/conforming"), "conforming"), "pdf-processing/SKILL.md");

        assertEquals(Arrays.asList("assets/logo.txt", "references/notes.md", "scripts/extract.py"),
                paths(pdf.getResources()), "资源清单: 斜杠相对路径 + 按路径排序 + SKILL.md 自身不入库");
        assertEquals(Arrays.asList("asset", "reference", "script"), kinds(pdf.getResources()),
                "首层目录决定 kind(scripts/references/assets)");
        assertEquals(Arrays.asList(11L, 32L, 30L), sizes(pdf.getResources()), "sizeBytes 取真实文件字节数");
        RawSkill dataImport = byRel(scan(SkillFormat.UNKNOWN, fx("agent-skills/conforming"), "conforming"), "data-import/SKILL.md");
        assertEquals(Collections.singletonList("scripts/load.sh"), paths(dataImport.getResources()));
    }

    @Test
    public void scansAreDeterministicAcrossRuns() throws IOException {
        List<String> first = fingerprints(scan(SkillFormat.UNKNOWN, fx("agent-skills/conforming"), "a"));
        List<String> second = fingerprints(scan(SkillFormat.UNKNOWN, fx("agent-skills/conforming"), "b"));
        assertEquals(first, second, "同一棵树两次扫描必须产出完全一样的条目+资源序列, 否则聚合结果不可复现");
    }

    @Test
    public void longBodyFixtureIsDiscoveredWithoutComplaintHere() throws IOException {
        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
        List<RawSkill> raws = scan(SkillFormat.UNKNOWN, fx("agent-skills/mismatch"), "mismatch", issues);

        assertEquals(Collections.singletonList("body-too-long/SKILL.md"), rels(raws),
                "超长正文仍要被收进候选, 超长与否是归一化层的事");
        assertTrue(issues.isEmpty(), "扫描层不该报超长: " + codes(issues));
    }

    @Test
    public void brokenCorpusYieldsCandidatesNotExceptions() throws IOException {
        List<RawSkill> raws = scan(SkillFormat.UNKNOWN, fx("agent-skills/conforming"), "conforming");
        assertTrue(rels(raws).containsAll(Arrays.asList("broken-no-fence/SKILL.md", "broken-missing-desc/SKILL.md")),
                "坏围栏/缺 description 的 SKILL.md 也要被发现(丢弃是归一化层的事), 实际=" + rels(raws));
    }

    // ---------------------------------------------------------------- 深度与跳过目录

    @Test
    public void maxDepthBoundsDirectoryDescent() throws IOException {
        Path root = tmp.resolve("skills");
        write(root.resolve("alpha/SKILL.md"), skill("alpha"));
        write(root.resolve("nested/deep/omega/SKILL.md"), skill("omega"));
        write(root.resolve("target/skipme/SKILL.md"), skill("skipme"));

        assertEquals(Arrays.asList("alpha/SKILL.md", "nested/deep/omega/SKILL.md"),
                rels(scanWith(SkillFormat.UNKNOWN, root, -1)),
                "默认深度要能收到嵌套 3 层的 SKILL.md, 且 SKIP_DIRS(target) 下的内容不被收");
        assertEquals(Collections.emptyList(), rels(scanWith(SkillFormat.UNKNOWN, root, 0)),
                "maxDepth=0 只看来源目录本层文件(SkillSource javadoc)");
        assertEquals(Collections.singletonList("alpha/SKILL.md"), rels(scanWith(SkillFormat.UNKNOWN, root, 2)),
                "maxDepth=2 只下探两层目录, nested/deep/omega 在第 3 层");
        assertEquals(Arrays.asList("alpha/SKILL.md", "nested/deep/omega/SKILL.md"),
                rels(scanWith(SkillFormat.UNKNOWN, root, 3)));

        Path deep = tmp.resolve("deep");
        write(deep.resolve("a/b/c/d/e/f/SKILL.md"), skill("at-limit"));
        write(deep.resolve("a/b/c/d/e/f/g/SKILL.md"), skill("too-deep"));
        assertEquals(Collections.singletonList("a/b/c/d/e/f/SKILL.md"),
                rels(scanWith(SkillFormat.UNKNOWN, deep, -1)),
                "默认上限是 SkillSpec.MAX_SCAN_DEPTH=6 层, 第 7 层不该被收");
    }

    // ---------------------------------------------------------------- 空目录 / 坏来源

    @Test
    public void emptyDirectoryIsZeroResultsNotAnError() throws IOException {
        Path empty = tmp.resolve("empty");
        Files.createDirectories(empty);
        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();

        assertEquals(0, scan(SkillFormat.UNKNOWN, empty, "empty", issues).size());
        assertTrue(issues.isEmpty(), "空目录不是错误: " + codes(issues));
    }

    @Test
    public void missingOrNonDirectorySourceIsRecordedAsIssue() throws IOException {
        List<SkillIssueDto> missing = new ArrayList<SkillIssueDto>();
        assertEquals(0, scan(SkillFormat.UNKNOWN, tmp.resolve("never-created"), "ghost", missing).size());
        assertEquals(Collections.singletonList("error:source-missing"), codes(missing));
        assertEquals("ghost", missing.get(0).getSource(), "issue 要能追溯到来源 id");

        List<SkillIssueDto> notADir = new ArrayList<SkillIssueDto>();
        Path file = tmp.resolve("stray.md");
        write(file, "---\nname: stray\ndescription: d\n---\nbody\n");
        assertEquals(0, scan(SkillFormat.UNKNOWN, file, "stray", notADir).size(), "来源指到文件不是目录");
        assertEquals(Collections.singletonList("error:source-missing"), codes(notADir));
    }

    // ---------------------------------------------------------------- 路径安全

    @Test
    public void dotDotSegmentsInSourcePathAreNormalizedAway() throws IOException {
        Path root = tmp.resolve("pkg");
        write(root.resolve("alpha/SKILL.md"), skill("alpha"));
        write(root.resolve("beta/SKILL.md"), skill("beta"));
        List<RawSkill> raws = scan(SkillFormat.UNKNOWN, root.resolve("alpha/../."), "dotdot");

        assertEquals(Arrays.asList("alpha/SKILL.md", "beta/SKILL.md"), rels(raws));
        for (RawSkill r : raws) {
            assertFalse(r.getEntryRelative().contains(".."), "entryRelative 不该残留 .. : " + r.getEntryRelative());
            assertTrue(r.getEntry().startsWith(root.toAbsolutePath().normalize()),
                    "条目必须落在来源目录内: " + r.getEntry());
        }
    }

    @Test
    public void dotLikeDirectoryNamesAreNotPathTraversal() throws IOException {
        Path root = tmp.resolve("odd");
        write(root.resolve("a..b/SKILL.md"), skill("a-dot-b"));

        assertEquals(Collections.singletonList("a..b/SKILL.md"), rels(scan(SkillFormat.UNKNOWN, root, "odd")),
                "目录名里的 .. 不是越界, 逐段判定才不误伤");
    }

    @Test
    public void directorySymlinkEscapingTheSourceIsNotIngested() throws IOException {
        Path root = tmp.resolve("root");
        write(root.resolve("inside/SKILL.md"), skill("inside"));
        Path outside = tmp.resolve("outside");
        write(outside.resolve("smuggled/SKILL.md"), skill("smuggled"));
        Files.createSymbolicLink(root.resolve("link"), outside);

        List<RawSkill> raws = scan(SkillFormat.UNKNOWN, root, "symlink");
        Path realRoot = root.toRealPath();
        List<String> escaped = new ArrayList<String>();
        for (RawSkill r : raws) {
            if (!r.getEntry().toRealPath().startsWith(realRoot)) {
                escaped.add(r.getEntryRelative());
            }
        }
        assertEquals(Collections.emptyList(), escaped,
                "目录软链指向来源外的内容不该被收进来(条目 realPath 越界): " + escaped);
        assertEquals(Collections.singletonList("inside/SKILL.md"), rels(raws), "只应收到来源内的 SKILL.md");
    }

    @Test
    public void fileSymlinkStillInsideTheSourceIsIngested() throws IOException {
        Path root = tmp.resolve("root");
        // 真实仓库的标准写法: 根目录 AGENTS.md 是软链, 正文物理放在 _doc 下(Codex/Claude 都这么推荐).
        // 一律跳过符号链接会让整个 AGENTS_MD 形状从真实语料里静默消失 —— 一条 issue 都不会留下.
        write(root.resolve("_doc/AGENTS.md"), "# AGENTS.md\n\n真实位置在 _doc 下\n");
        Files.createSymbolicLink(root.resolve("AGENTS.md"), root.resolve("_doc/AGENTS.md"));
        // 指向来源外的文件软链: 那是"把根外文件伪装成根内条目"的口子, 仍然不收
        Path outside = tmp.resolve("outside-secret.md");
        write(outside, "# secret\n");
        Files.createSymbolicLink(root.resolve("smuggled.md"), outside);
        Files.createSymbolicLink(root.resolve("dangling.md"), root.resolve("nope/never-exists.md"));

        List<RawSkill> raws = scan(SkillFormat.UNKNOWN, root, "file-links");
        List<String> rels = rels(raws);
        assertTrue(rels.contains("AGENTS.md"),
                "来源根内的文件软链必须照样收录(否则条目静默消失): " + rels);
        assertFalse(rels.contains("smuggled.md"), "文件软链指向来源外不该被收进来: " + rels);
        assertFalse(rels.contains("dangling.md"), "断链不该被收进来: " + rels);
        for (RawSkill r : raws) {
            assertTrue(r.getEntry().toRealPath().startsWith(root.toRealPath()),
                    "收进来的条目真实落点必须还在来源内: " + r.getEntryRelative() + " -> " + r.getEntry().toRealPath());
        }
    }

    // ---------------------------------------------------------------- 显式 format 覆盖启发式

    @Test
    public void declaredFormatOverridesTheAutoHeuristics() throws IOException {
        assertEquals(Arrays.asList("always.mdc", "react.mdc"),
                rels(scan(SkillFormat.CURSOR_RULES, fx("cursor-rules"), "cursor-declared")));
        assertEquals(0, scan(SkillFormat.AGENT_SKILLS, fx("cursor-rules"), "cursor-as-agent").size(),
                "显式声明 AGENT_SKILLS 时不该按启发式去认 .mdc");
        assertEquals(Arrays.asList("code-review.md", "sql-query.md"),
                rels(scan(SkillFormat.UNKNOWN, fx("flat-legacy"), "flat-auto")));
        List<String> forcedFlatOnSkillPackages =
                rels(scan(SkillFormat.FLAT_MARKDOWN, fx("agent-skills/conforming"), "flat-on-conforming"));
        assertEquals(Arrays.asList("pdf-processing/references/notes.md", "references/loose.md"),
                forcedFlatOnSkillPackages, "显式 FLAT_MARKDOWN 会跳过 NON_SKILL_DIRS 启发式, 但 README 仍被 NON_SKILL_MD 挡住");
    }

    // ---------------------------------------------------------------- helpers

    private List<RawSkill> scan(SkillFormat format, Path root, String id) throws IOException {
        return scan(format, root, id, new ArrayList<SkillIssueDto>());
    }

    private List<RawSkill> scan(SkillFormat format, Path root, String id, List<SkillIssueDto> issues) throws IOException {
        return scanner.scan(new SkillSource(id, format, root.toString(), 50), issues);
    }

    private List<RawSkill> scanWith(SkillFormat format, Path root, int maxDepth) throws IOException {
        return scanner.scan(new SkillSource("d" + maxDepth, format, root.toString(), 50).maxDepth(maxDepth),
                new ArrayList<SkillIssueDto>());
    }

    private static List<String> rels(List<RawSkill> raws) {
        List<String> out = new ArrayList<String>();
        for (RawSkill r : raws) out.add(r.getEntryRelative());
        return out;
    }

    /** 去重后的 format 序列: 一批发现结果只应有一种格式时长度为 1. */
    private static List<String> distinctFormats(List<RawSkill> raws) {
        Set<String> seen = new LinkedHashSet<String>();
        for (RawSkill r : raws) seen.add(String.valueOf(r.getFormat()));
        return new ArrayList<String>(seen);
    }

    private static List<String> codes(List<SkillIssueDto> issues) {
        List<String> out = new ArrayList<String>();
        for (SkillIssueDto i : issues) out.add(i.getSeverity() + ":" + i.getCode());
        return out;
    }

    private static List<String> paths(List<SkillResourceDto> resources) {
        List<String> out = new ArrayList<String>();
        for (SkillResourceDto r : resources) out.add(r.getPath());
        return out;
    }

    private static List<String> kinds(List<SkillResourceDto> resources) {
        List<String> out = new ArrayList<String>();
        for (SkillResourceDto r : resources) out.add(r.getKind());
        return out;
    }

    private static List<Long> sizes(List<SkillResourceDto> resources) {
        List<Long> out = new ArrayList<Long>();
        for (SkillResourceDto r : resources) out.add(r.getSizeBytes());
        return out;
    }

    /** "rel|kind|size" 指纹: 一次扫描的全部可观察输出. */
    private static List<String> fingerprints(List<RawSkill> raws) {
        List<String> out = new ArrayList<String>();
        for (RawSkill r : raws) {
            for (SkillResourceDto res : r.getResources()) {
                out.add(r.getEntryRelative() + " -> " + res.getPath() + "|" + res.getKind() + "|" + res.getSizeBytes());
            }
            out.add(r.getEntryRelative() + " [" + r.getFormat() + "]");
        }
        return out;
    }

    private static RawSkill byRel(List<RawSkill> raws, String rel) {
        for (RawSkill r : raws) {
            if (rel.equals(r.getEntryRelative())) return r;
        }
        throw new AssertionError("没有扫到条目 " + rel + ", 实际=" + rels(raws));
    }

    private static String skill(String name) {
        return "---\nname: " + name + "\ndescription: probe skill " + name + "\n---\n# " + name + "\nbody\n";
    }

    private static void write(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    }

    private static Path fx(String rel) {
        Path p = FIXTURES.resolve(rel);
        if (!Files.isDirectory(p)) throw new IllegalStateException("fixture 目录不存在: " + p);
        return p;
    }

    /** 优先 src/test/resources(源码树), 回落 target/test-classes(surefire 复制位); 都找不到就抛. */
    private static Path fixturesRoot() {
        List<Path> candidates = new ArrayList<Path>();
        try {
            Path classes = Paths.get(FilesystemScannerTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            candidates.add(classes.resolve("fixtures"));
            if (classes.getParent() != null && classes.getParent().getParent() != null) {
                candidates.add(classes.getParent().getParent().resolve("src/test/resources/fixtures"));
            }
        } catch (Exception ignored) {
            // 从 jar 里跑或 location 为 null: 继续试 user.dir
        }
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            candidates.add(dir.resolve("src/test/resources/fixtures"));
            candidates.add(dir.resolve("target/test-classes/fixtures"));
            candidates.add(dir.resolve("z-skill-core/src/test/resources/fixtures"));
            dir = dir.getParent();
        }
        for (Path c : candidates) {
            if (Files.isDirectory(c.resolve("agent-skills/conforming"))) return c;
        }
        throw new IllegalStateException("找不到 fixtures 根目录, 试过的路径: " + candidates);
    }
}
