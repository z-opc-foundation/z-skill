package com.zifang.z.skill.core.normalize;

import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.dto.SkillIssueDto;
import com.zifang.z.skill.api.dto.SkillResourceDto;
import com.zifang.z.skill.api.spec.SkillFormat;
import com.zifang.z.skill.core.discover.FilesystemScanner;
import com.zifang.z.skill.core.discover.RawSkill;
import com.zifang.z.skill.core.discover.SkillSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归一化层: {@link MarkdownSkillNormalizer} 对 markdown 家族五种写法的收口测试.
 *
 * <p>逐格式钉住身份字段(name 保留来源原文写法 / slug 清洗)、描述的多行与块标量形态、
 * version 从 metadata 回落、作用域(globs / applyTo / AGENTS.md 目录 + override 就近覆盖)、
 * 以及"坏条目只记账不炸链".
 */
public class MarkdownSkillNormalizerTest {

    private static final Path FIXTURES = fixturesRoot();

    private final MarkdownSkillNormalizer normalizer = new MarkdownSkillNormalizer();
    private final FilesystemScanner scanner = new FilesystemScanner();

    @TempDir
    Path tmp;

    // ---------------------------------------------------------------- AGENT_SKILLS

    @Test
    public void agentSkillKitchenSinkFillsEveryNormalizedField() throws IOException {
        String body = "# Invoice processor\n\nstep 1\nstep 2\n";
        String text = "---\n"
                + "name: Invoice Processor\n"
                + "description: >-\n"
                + "  Extracts line items.\n"
                + "  Fills PDF forms.\n"
                + "license: MIT\n"
                + "compatibility: Claude Code, Cursor\n"
                + "category: finance\n"
                + "tags: [invoices, pdf]\n"
                + "allowed-tools: Read, Bash(rg:*), Write\n"
                + "metadata:\n"
                + "  author: acme\n"
                + "  version: 2.5.0\n"
                + "  channel: beta\n"
                + "---\n" + body;
        write(tmp.resolve("invoice-processor/SKILL.md"), text);
        SkillSource source = srcAt("agent-fs", tmp);
        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
        SkillDto dto = normalize(rawOf(source, "invoice-processor/SKILL.md"), source, issues).get(0);

        assertEquals("Invoice Processor", dto.getName(), "name 保留来源原文写法, 只用于展示");
        assertEquals("invoice-processor", dto.getSlug(), "slug 按规范清洗(小写、空格转连字符)");
        assertEquals("Extracts line items. Fills PDF forms.", dto.getDescription(), "折叠块标量 >- 的多行描述压成一行");
        assertEquals("2.5.0", dto.getVersion(), "顶层没有 version 时按 javadoc 回落 metadata.version");
        assertEquals(Arrays.asList("MIT", "Claude Code, Cursor"),
                Arrays.asList(dto.getLicense(), dto.getCompatibility()), "license / compatibility(下标对齐)");
        assertEquals("finance", dto.getCategory(), "顶层 category 优先于默认的 general");
        assertEquals(Arrays.asList("invoices", "pdf"), dto.getTags(), "行内列表 tags");
        assertEquals(Arrays.asList("Read", "Bash(rg:*)", "Write"), dto.getAllowedTools(),
                "allowed-tools 逗号/空格双切, 括号内的冒号不参与切分");
        assertEquals("acme", dto.getAuthor(), "author 回落 metadata.author");
        assertEquals(Arrays.asList("AGENT_SKILLS", "local"),
                Arrays.asList(dto.getFormat().name(), dto.getSourceType()), "format / sourceType(下标对齐)");
        assertEquals("invoice-processor/SKILL.md", dto.getSkillFilePath(), "skillFilePath 是相对来源根的正斜杠路径");
        assertTrue(dto.getOrigin().startsWith("file:"), "origin 要能回溯到原始文件: " + dto.getOrigin());
        assertEquals(body.length(), dto.getBodyChars(), "bodyChars 是剥掉 frontmatter 后的正文字符数");
        assertEquals(4, dto.getBodyLines(), "正文 4 行(含空行)");
        assertEquals(sha256Hex(text), dto.getContentHash(), "contentHash 覆盖整份文档(含 frontmatter)");
        List<String> codes = codes(issues);
        assertTrue(codes.contains("warning:name-non-conforming"),
                "name 不合规只记 warning 不丢弃, 且 code 必须是有界裸码(不带 spec: 命名空间、不带 \"->slug\" 取值尾巴,"
                        + " 取值留在 message): " + codes);
    }

    @Test
    public void contentHashIsStableAndFollowsContent() throws IOException {
        write(tmp.resolve("hashing/SKILL.md"), "---\nname: hashing\ndescription: d\n---\nline one\n");
        SkillSource source = srcAt("h", tmp);

        String first = normalize(rawOf(source, "hashing/SKILL.md"), source, new ArrayList<SkillIssueDto>()).get(0)
                .getContentHash();
        String again = normalize(rawOf(source, "hashing/SKILL.md"), source, new ArrayList<SkillIssueDto>()).get(0)
                .getContentHash();
        assertEquals(first, again, "同一内容两次归一化必须同 hash(且是 64 位 SHA-256 十六进制), 否则跨平台折叠无从做起");
        assertEquals(64, first.length(), "SHA-256 十六进制应当 64 位");

        write(tmp.resolve("hashing/SKILL.md"), "---\nname: hashing\ndescription: d\n---\nline one\nline two\n");
        String changed = normalize(rawOf(source, "hashing/SKILL.md"), source, new ArrayList<SkillIssueDto>()).get(0)
                .getContentHash();
        assertFalse(first.equals(changed), "正文变了 hash 必须变, 否则上游漂移检测失效");
    }

    @Test
    public void nameStaysVerbatimWhileSlugIsCleaned() throws IOException {
        SkillSource source = src("conf", "agent-skills/conforming");
        SkillDto dto = normalize(rawOf(source, "RedBookSkills/SKILL.md"), source,
                new ArrayList<SkillIssueDto>()).get(0);

        assertEquals("RedBookSkills", dto.getName(), "SkillDto javadoc: name 是来源原样写法, 只用于展示");
        assertEquals("redbookskills", dto.getSlug(), "全大写名要清洗成全小写 slug");
    }

    @Test
    public void everyNestedMetadataKeyMustSurvive() throws IOException {
        SkillSource source = src("conf", "agent-skills/conforming");
        SkillDto dataImport = normalize(rawOf(source, "data-import/SKILL.md"), source,
                new ArrayList<SkillIssueDto>()).get(0);
        SkillDto pdf = normalize(rawOf(source, "pdf-processing/SKILL.md"), source,
                new ArrayList<SkillIssueDto>()).get(0);

        assertEquals("0.3.1", dataImport.getMetadata().get("version"), "非末尾的嵌套键正常落进 metadata");
        assertEquals("when the user asks to load a dataset", dataImport.getMetadata().get("trigger"),
                "回归: Frontmatter javadoc 承诺一层嵌套映射原样进 metadata 不丢, 曾因 parseMapping 把闭区间末下标"
                        + "当开区间上界传下去而吞掉嵌套块的最后一个键: " + dataImport.getMetadata());
        assertTrue(pdf.getMetadata().containsKey("tags"),
                "同一处回归的另一例: pdf-processing 的 metadata.tags 也在嵌套块最后一行: " + pdf.getMetadata());
    }

    @Test
    public void blockScalarAndIndentedContinuationDescriptionsAreBothRead() throws IOException {
        SkillSource source = src("conf", "agent-skills/conforming");
        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
        SkillDto dataImport = normalize(rawOf(source, "data-import/SKILL.md"), source, issues).get(0);

        assertEquals("Import CSV and Parquet files into the warehouse."
                        + " Handles delimiter sniffing, type inference, and dedup keys.",
                dataImport.getDescription(), "| 块标量的两行描述被折叠成一行");
        assertTrue(codes(issues).contains("info:description-multiline"),
                "原文多行要留一条 info, 让上层还能看出折叠过: " + codes(issues));

        SkillDto viaContinuation = normalize(textOnly("qoder-style", SkillFormat.AGENT_SKILLS,
                "---\nname: qoder-style\ndescription:\n  说明写在下一层缩进的续行里。\n---\nbody\n"), source,
                new ArrayList<SkillIssueDto>()).get(0);
        assertEquals("说明写在下一层缩进的续行里。", viaContinuation.getDescription(),
                "description 值为空、正文在缩进续行的写法(Qoder 内置 skill)要能读到");
    }

    @Test
    public void brokenAgentSkillsAreRecordedNotSilentlyDropped() throws IOException {
        SkillSource source = src("conf", "agent-skills/conforming");

        List<SkillIssueDto> noDesc = new ArrayList<SkillIssueDto>();
        List<SkillDto> kept = normalize(rawOf(source, "broken-missing-desc/SKILL.md"), source, noDesc);
        assertEquals(1, kept.size(),
                "SkillIssueDto 的口径: error 只表示'这条没进目录', 宽松收录下缺 description 必须仍收录: " + codes(noDesc));
        assertEquals("No description at all", kept.get(0).getDescription(), "frontmatter 没给 description 时取正文首个标题兜底");
        assertTrue(codes(noDesc).contains("info:description-derived-from-body"), "兜底必须留痕: " + codes(noDesc));

        List<SkillIssueDto> noFence = new ArrayList<SkillIssueDto>();
        List<SkillDto> lenient = normalize(rawOf(source, "broken-no-fence/SKILL.md"), source, noFence);
        assertEquals(1, lenient.size(), "整份文件没有 frontmatter 也要按目录名宽松收录, 不能静默丢掉");
        assertEquals("broken-no-fence", lenient.get(0).getSlug(), "回退标识取目录名");
        // 收了就要记 warning —— 记 error 会和"已收录"的读数互相打脸(见 SkillIssueDto)
        assertTrue(codes(noFence).contains("warning:frontmatter-missing"), "缺围栏要记账且不丢弃: " + codes(noFence));
    }

    @Test
    public void bodyOverTheSoftLimitIsKeptButFlagged() throws IOException {
        SkillSource source = src("mismatch", "agent-skills/mismatch");
        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
        SkillDto dto = normalize(rawOf(source, "body-too-long/SKILL.md"), source, issues).get(0);

        assertEquals(520, dto.getBodyLines(), "语料正文是 line 0..line 519 共 520 行");
        assertTrue(codes(issues).contains("warning:body-too-long"), "超 500 行只该 warning 不该丢: " + codes(issues));
    }

    @Test
    public void resourcesAndSpecFieldsDiscoveredByTheScannerReachTheDto() throws IOException {
        SkillSource source = src("conf", "agent-skills/conforming");
        SkillDto pdf = normalize(rawOf(source, "pdf-processing/SKILL.md"), source,
                new ArrayList<SkillIssueDto>()).get(0);

        assertEquals(Arrays.asList("assets/logo.txt", "references/notes.md", "scripts/extract.py"),
                resourcePaths(pdf), "scripts/references/assets 要跟着进 DTO");
    }

    // ---------------------------------------------------------------- FLAT_MARKDOWN

    @Test
    public void legacyFlatMarkdownFrontmatterKeysStillLand() throws IOException {
        SkillSource source = src("legacy", "flat-legacy");
        SkillDto dto = normalize(rawOf(source, "code-review.md"), source, new ArrayList<SkillIssueDto>()).get(0);

        assertEquals(Arrays.asList("FLAT_MARKDOWN", "code-review", "code-review.md", "对 PR 做代码审查",
                        "1.0.0", "development", "yuku123"),
                Arrays.asList(dto.getFormat().name(), dto.getSlug(), dto.getSkillFilePath(), dto.getDescription(),
                        dto.getVersion(), dto.getCategory(), dto.getAuthor()),
                "遗留扁平格式的顶层 version/category/author 键一个都不能掉(下标对齐: format/slug/path/description/version/category/author)");
        assertEquals(Arrays.asList("code", "review", "pr"), dto.getTags(), "逗号串要拆成 tags");
        assertEquals("当用户请求 PR review 或代码审查时加载", dto.getTrigger(), "遗留的 trigger 键不能被丢");
    }

    // ---------------------------------------------------------------- CURSOR_RULES

    @Test
    public void cursorRulesDescriptionAndGlobsBecomeScope() throws IOException {
        SkillSource source = src("cursor", "cursor-rules");
        SkillDto react = normalize(rawOf(source, "react.mdc"), source, new ArrayList<SkillIssueDto>()).get(0);

        assertEquals(Arrays.asList("CURSOR_RULES", "react", "React 组件规范", "editor-rules"),
                Arrays.asList(react.getFormat().name(), react.getSlug(), react.getDescription(), react.getCategory()),
                ".mdc 用文件名当标识, description/category 照常收(下标对齐: format/slug/description/category)");
        assertEquals(Arrays.asList("**/*.tsx", "**/*.ts"), react.getPaths(), "Cursor 的 globs 就是作用域");
    }

    @Test
    public void cursorAlwaysApplyRuleMustLeaveATrace() throws IOException {
        SkillSource source = src("cursor", "cursor-rules");
        SkillDto always = normalize(rawOf(source, "always.mdc"), source, new ArrayList<SkillIssueDto>()).get(0);

        assertFalse(always.getPaths().isEmpty() && !always.getMetadata().containsKey("alwaysApply"),
                "alwaysApply:true 的 Cursor 规则是全局生效的, 但既没落成作用域也没进 metadata, 这个信息被彻底丢掉: paths="
                        + always.getPaths() + " metadata=" + always.getMetadata());
    }

    // ---------------------------------------------------------------- AGENTS_MD

    @Test
    public void agentsMdPlainBodyNeedsNoFrontmatter() throws IOException {
        SkillSource source = src("agents", "agents-md");
        SkillDto dto = normalize(rawOf(source, "AGENTS.md"), source, new ArrayList<SkillIssueDto>()).get(0);

        assertEquals(Arrays.asList("AGENTS_MD", "agents-md-instructions", "AGENTS.md", "project-instructions",
                        "Repository rules"),
                Arrays.asList(dto.getFormat().name(), dto.getSlug(), dto.getSkillFilePath(), dto.getCategory(),
                        dto.getDescription()),
                "AGENTS.md 无 frontmatter 也要成一条可检索的 skill(下标对齐: format/slug/path/category/description)");
    }

    /**
     * "描述是从正文猜来的"这件事必须留痕, 而且各家格式走同一条路.
     *
     * <p>AGENTS.md 曾经有一条专属兜底分支(不记账, 且兜出来是一句常量), 于是同一个动作换个格式就查不到
     * 出处 —— 严重级口径要求"收录但非规范/是猜的"至少要有一条 info.
     */
    @Test
    public void agentsMdDerivedDescriptionLeavesPaperTrail() throws IOException {
        Path repo = tmp.resolve("trailrepo");
        write(repo.resolve("AGENTS.md"), "# Root rules\n\nroot body line\n");
        Bag bag = scanAndNormalize(srcAt("repo", repo));

        assertEquals("Root rules", bySlug(bag.dtos, "trailrepo-instructions").getDescription(),
                "无 frontmatter 时取正文首个标题, 这条不变");
        assertTrue(codes(bag.issues).contains("info:description-derived-from-body"),
                "猜来的描述必须能被查到: " + codes(bag.issues));
    }

    /** 正文里什么可取的东西都没有: 不编描述, 条目照收但记 warning. */
    @Test
    public void agentsMdWithNothingToDescribeIsKeptAndFlagged() throws IOException {
        Path repo = tmp.resolve("emptyrepo");
        write(repo.resolve("AGENTS.md"), "\n\n   \n");
        Bag bag = scanAndNormalize(srcAt("repo", repo));

        assertEquals(1, bag.dtos.size(), "缺描述不该把条目丢掉: " + slugs(bag.dtos));
        assertEquals("", bySlug(bag.dtos, "emptyrepo-instructions").getDescription(), "没有描述就给空, 不要造句");
        assertTrue(codes(bag.issues).contains("warning:description-missing"),
                "收了但非规范 = warning(SkillIssueDto 口径): " + codes(bag.issues));
    }

    @Test
    public void agentsMdScopeCoversItsOwnDirectoryTree() throws IOException {
        Path repo = tmp.resolve("repo");
        write(repo.resolve("AGENTS.md"), "# Root rules\n\nroot body line\n");
        write(repo.resolve("frontend/AGENTS.md"), "# Frontend rules\n\nfrontend body\n");
        SkillSource source = srcAt("repo", repo);
        List<SkillDto> dtos = scanAndNormalize(source).dtos;

        assertEquals(Arrays.asList("frontend-instructions", "repo-instructions"), slugs(dtos),
                "来源根与子目录各出一条, 无 override 时普通 AGENTS.md 照常生效");
        assertEquals(Collections.singletonList("**"), pathsOf(dtos, "repo-instructions"), "仓库根 AGENTS.md 覆盖全仓");
        assertEquals(Collections.singletonList("frontend/**"), pathsOf(dtos, "frontend-instructions"));
    }

    @Test
    public void agentsOverrideMdWinsOverPlainAgentsMdInSameDirectory() throws IOException {
        Path repo = tmp.resolve("repo");
        write(repo.resolve("AGENTS.md"), "# Root rules\n\nroot body line\n");
        write(repo.resolve("backend/AGENTS.md"), "# Backend rules\n\nbackend plain\n");
        write(repo.resolve("backend/AGENTS.override.md"), "# Backend override rules\n\nbackend override\n");
        SkillSource source = srcAt("repo", repo);
        List<SkillDto> dtos = scanAndNormalize(source).dtos;

        assertEquals(Arrays.asList("backend-instructions", "repo-instructions"), slugs(dtos),
                "SkillFormat#AGENTS_MD javadoc 承诺'就近覆盖': 同目录已有 AGENTS.override.md 时普通 AGENTS.md 要让位,"
                        + " 现在两条同名条目同时入表 -> " + slugs(dtos));
        assertEquals(sha256Hex(read(repo.resolve("backend/AGENTS.override.md"))),
                hashOf(dtos, "backend-instructions"), "留在表里的 backend 条目必须是 override 的内容");
    }

    // ---------------------------------------------------------------- COPILOT

    @Test
    public void copilotInstructionsBecomeSkillsAndApplyToBecomesScope() throws IOException {
        SkillSource source = src("copilot", "copilot");
        List<SkillDto> dtos = scanAndNormalize(source).dtos;

        assertEquals(Arrays.asList("copilot-instructions", "typescript-instructions"), slugs(dtos),
                "copilot-instructions.md 本体与 instructions/*.instructions.md 各成一条 skill");
        assertEquals(Arrays.asList(".github/copilot-instructions.md", "Repo-wide copilot instructions"),
                Arrays.asList(bySlug(dtos, "copilot-instructions").getSkillFilePath(),
                        bySlug(dtos, "copilot-instructions").getDescription()),
                "仓库级 copilot-instructions 的 path/description");
        assertEquals(Arrays.asList(".github/instructions/typescript.instructions.md",
                        Arrays.asList("**/*.ts", "**/*.tsx")),
                Arrays.asList(bySlug(dtos, "typescript-instructions").getSkillFilePath(),
                        bySlug(dtos, "typescript-instructions").getPaths()),
                "Copilot 的 applyTo 就是作用域");
    }

    // ---------------------------------------------------------------- helpers

    private static SkillSource src(String id, String fixtureRel) {
        Path p = FIXTURES.resolve(fixtureRel);
        if (!Files.isDirectory(p)) throw new IllegalStateException("fixture 目录不存在: " + p);
        return new SkillSource(id, SkillFormat.UNKNOWN, p.toString(), 50);
    }

    private static SkillSource srcAt(String id, Path root) {
        return new SkillSource(id, SkillFormat.UNKNOWN, root.toString(), 50);
    }

    private RawSkill rawOf(SkillSource source, String entryRelative) throws IOException {
        for (RawSkill raw : scanner.scan(source, new ArrayList<SkillIssueDto>())) {
            if (entryRelative.equals(raw.getEntryRelative())) return raw;
        }
        throw new AssertionError("没扫到条目 " + entryRelative + " (来源 " + source.location() + ")");
    }

    private List<SkillDto> normalize(RawSkill raw, SkillSource source, List<SkillIssueDto> issues) {
        return normalizer.normalize(raw, source, issues);
    }

    private static final class Bag {
        final List<SkillDto> dtos = new ArrayList<SkillDto>();
        final List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
    }

    private Bag scanAndNormalize(SkillSource source) throws IOException {
        Bag bag = new Bag();
        for (RawSkill raw : scanner.scan(source, new ArrayList<SkillIssueDto>())) {
            bag.dtos.addAll(normalizer.normalize(raw, source, bag.issues));
        }
        return bag;
    }

    private static RawSkill textOnly(String name, SkillFormat format, String text) {
        return new RawSkill().format(format).fallbackName(name).dirName(name)
                .entryRelative(name + "/SKILL.md").text(text);
    }

    private static List<String> slugs(List<SkillDto> dtos) {
        List<String> out = new ArrayList<String>();
        for (SkillDto d : dtos) out.add(d.getSlug());
        Collections.sort(out);
        return out;
    }

    private static List<String> resourcePaths(SkillDto dto) {
        List<String> out = new ArrayList<String>();
        for (SkillResourceDto r : dto.getResources()) out.add(r.getPath());
        return out;
    }

    private static List<String> pathsOf(List<SkillDto> dtos, String slug) {
        return bySlug(dtos, slug).getPaths();
    }

    private static String hashOf(List<SkillDto> dtos, String slug) {
        SkillDto found = null;
        for (SkillDto d : dtos) {
            if (slug.equals(d.getSlug())) {
                if (found != null) {
                    throw new AssertionError("slug=" + slug + " 在表里有多条, 就近覆盖没生效: " + slugs(dtos));
                }
                found = d;
            }
        }
        if (found == null) throw new AssertionError("没有 slug=" + slug + ", 实际=" + slugs(dtos));
        return found.getContentHash();
    }

    private static SkillDto bySlug(List<SkillDto> dtos, String slug) {
        for (SkillDto d : dtos) {
            if (slug.equals(d.getSlug())) return d;
        }
        throw new AssertionError("没有 slug=" + slug + ", 实际=" + slugs(dtos));
    }

    private static List<String> codes(List<SkillIssueDto> issues) {
        List<String> out = new ArrayList<String>();
        for (SkillIssueDto i : issues) out.add(i.getSeverity() + ":" + i.getCode());
        return out;
    }

    /** 独立实现的 SHA-256 十六进制, 不复用被审计的 SkillFiles.sha256. */
    private static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String read(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读不到 " + p, e);
        }
    }

    private static void write(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    }

    /** 优先 src/test/resources(源码树), 回落 target/test-classes(surefire 复制位); 都找不到就抛. */
    private static Path fixturesRoot() {
        List<Path> candidates = new ArrayList<Path>();
        try {
            Path classes = Paths.get(MarkdownSkillNormalizerTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            candidates.add(classes.resolve("fixtures"));
            if (classes.getParent() != null && classes.getParent().getParent() != null) {
                candidates.add(classes.getParent().getParent().resolve("src/test/resources/fixtures"));
            }
        } catch (Exception ignored) {
            // location 不可用时回落到 user.dir 上溯
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
