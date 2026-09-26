package com.zifang.z.skill.core.spec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SkillFiles 的文件侧工具契约: 指纹、路径安全、旁文件排除、行数统计.
 *
 * <p>SHA-256 期望值取自公开测试向量(FIPS 180-2 / 常见参考值), 不是自证.
 */
public class SkillFilesTest {

    @TempDir
    Path tmp;

    @Test
    public void sha256_matchesPublishedTestVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", SkillFiles.sha256(""));
        assertEquals("ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb", SkillFiles.sha256("a"));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", SkillFiles.sha256("abc"));
    }

    @Test
    public void sha256_isUtf8ByteBased_notCharBased() {
        // U+00E9 预组合是 c3 a9; e + U+0301 组合式是 65 cc 81 —— 视觉相同, UTF-8 字节不同
        assertEquals("4a99557e4033c3539de2eb65472017cad5f9557f7a0625a09f1c3f6e2ba69c4c", SkillFiles.sha256("\u00e9"));
        assertNotEquals(SkillFiles.sha256("\u00e9"), SkillFiles.sha256("e\u0301"), "必须按 UTF-8 字节摘要");
        assertEquals(64, SkillFiles.sha256("\u4e2d\u6587").length());
    }

    @Test
    public void sha256_isStableForIdenticalBytes_andDiffersOnAnyDrift() {
        String doc = "---\nname: pdf\ndescription: 中文描述\n---\n# Body\n";
        assertEquals(SkillFiles.sha256(doc), SkillFiles.sha256(doc), "同内容必须同指纹, 否则跨平台去重失效");
        assertNotEquals(SkillFiles.sha256(doc), SkillFiles.sha256(doc + " "));
        assertNotEquals(SkillFiles.sha256("a"), SkillFiles.sha256("a\n"));
    }

    @Test
    public void sha256_isLowercaseHexOf64Chars() {
        String h = SkillFiles.sha256("some skill content");
        assertEquals(64, h.length());
        assertTrue(h.matches("[0-9a-f]{64}"), "应为小写十六进制: " + h);
    }

    @Test
    public void isSafeRelativePath_rejectsAbsoluteAndTraversal() {
        assertFalse(SkillFiles.isSafeRelativePath("../escape.md"));
        assertFalse(SkillFiles.isSafeRelativePath("/etc/passwd"));
        assertFalse(SkillFiles.isSafeRelativePath("references/../../secret.md"));
        assertFalse(SkillFiles.isSafeRelativePath("scripts\\..\\..\\evil.sh"));
        assertFalse(SkillFiles.isSafeRelativePath(".."));
        assertFalse(SkillFiles.isSafeRelativePath(""));
        assertFalse(SkillFiles.isSafeRelativePath(null));
    }

    @Test
    public void isSafeRelativePath_acceptsNormalNestedRelativePaths() {
        assertTrue(SkillFiles.isSafeRelativePath("SKILL.md"));
        assertTrue(SkillFiles.isSafeRelativePath("references/notes.md"));
        assertTrue(SkillFiles.isSafeRelativePath("a/b/c/SKILL.md"));
        assertTrue(SkillFiles.isSafeRelativePath("scripts\\run.sh"), "反斜杠只是分隔符写法");
    }

    @Test
    public void relativize_returnsSlashSeparatedRelativePath_orNullWhenOutsideRoot() throws IOException {
        Path file = tmp.resolve("sub/deep/SKILL.md");
        Files.createDirectories(file.getParent());
        Files.write(file, "x".getBytes(StandardCharsets.UTF_8));
        assertEquals("sub/deep/SKILL.md", SkillFiles.relativize(tmp, file));
        assertEquals("not-created/yet.md", SkillFiles.relativize(tmp, tmp.resolve("not-created/yet.md")));
        assertNull(SkillFiles.relativize(tmp, tmp.resolve("../evil.md")), "越出根目录必须 null");
        assertNull(SkillFiles.relativize(tmp, tmp.resolveSibling("other.md")));
    }

    @Test
    public void slashify_normalizesWindowsSeparators() {
        assertEquals("a/b/c.md", SkillFiles.slashify("a\\b\\c.md"));
        assertEquals("a/b.md", SkillFiles.slashify("a/b.md"));
        assertNull(SkillFiles.slashify(null));
    }

    @Test
    public void isSkillMd_isCaseInsensitiveAndExact() {
        assertTrue(SkillFiles.isSkillMd("SKILL.md"));
        assertTrue(SkillFiles.isSkillMd("skill.md"));
        assertTrue(SkillFiles.isSkillMd("Skill.MD"));
        assertFalse(SkillFiles.isSkillMd("README.md"));
        assertFalse(SkillFiles.isSkillMd("SKILL.md.bak"));
    }

    @Test
    public void isLooseSkillMd_excludesDocumentedSidecarFiles() {
        List<String> sidecars = Arrays.asList("readme.md", "changelog.md", "license.md", "licence.md",
                "contributing.md", "code_of_conduct.md", "security.md", "agents.md", "claude.md",
                "index.md", "skill.md");
        for (String name : sidecars) {
            assertFalse(SkillFiles.isLooseSkillMd(name), name + " 是旁文件, 不能当成扁平 skill 条目");
        }
        assertFalse(SkillFiles.isLooseSkillMd("README.MD"), "判定要大小写不敏感");
    }

    @Test
    public void isLooseSkillMd_includesRealReferenceNotes() {
        assertTrue(SkillFiles.isLooseSkillMd("notes.md"));
        assertTrue(SkillFiles.isLooseSkillMd("pdf-forms.md"));
        assertTrue(SkillFiles.isLooseSkillMd("REFERENCE.md"));
        assertFalse(SkillFiles.isLooseSkillMd("notes.txt"), "非 .md 不收");
        assertFalse(SkillFiles.isLooseSkillMd("md"));
    }

    @Test
    public void countLines_handlesTrailingNewlineCrlfAndEmpty() {
        assertEquals(0, SkillFiles.countLines(""));
        assertEquals(0, SkillFiles.countLines(null));
        assertEquals(1, SkillFiles.countLines("a"));
        assertEquals(1, SkillFiles.countLines("a\n"), "结尾换行不该多算一行");
        assertEquals(2, SkillFiles.countLines("a\nb"));
        assertEquals(2, SkillFiles.countLines("a\nb\n"));
        assertEquals(2, SkillFiles.countLines("a\n\n"), "中间空行要算");
        assertEquals(2, SkillFiles.countLines("a\r\nb\r\n"), "CRLF 与 LF 同结果");
        assertEquals(2, SkillFiles.countLines("a\r\nb"));
        assertEquals(3, SkillFiles.countLines("a\nb\nc"));
    }

    @Test
    public void read_returnsUtf8TextVerbatimIncludingBom() throws IOException {
        Path p = tmp.resolve("SKILL.md");
        String doc = "\ufeff---\nname: x\ndescription: \u4e2d\u6587\n---\nbody\n";
        Files.write(p, doc.getBytes(StandardCharsets.UTF_8));

        String raw = SkillFiles.read(p);
        assertEquals(doc, raw, "read 只做 UTF-8 解码, 不吃 BOM");
        assertEquals(5, SkillFiles.countLines(raw));

        Frontmatter fm = Frontmatter.parse(raw);
        assertTrue(fm.isPresent(), "read 的结果应能直接喂给 Frontmatter");
        assertEquals("\u4e2d\u6587", fm.string("description"));
        assertEquals("body\n", fm.body());
        assertEquals(Collections.<String>emptyList(), fm.issues());
    }
}
