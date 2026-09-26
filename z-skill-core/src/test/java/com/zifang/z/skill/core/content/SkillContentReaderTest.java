package com.zifang.z.skill.core.content;

import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.dto.SkillResourceDto;
import com.zifang.z.skill.api.exception.SkillException;
import com.zifang.z.skill.api.spec.SkillFormat;
import com.zifang.z.skill.core.registry.SkillRegistry;
import com.zifang.z.skill.core.spec.SkillFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 详情页读取测试 — 这是一个沙箱: 只能读到某条 skill 自己目录里、且被清单声明过的文件.
 *
 * <p>远端来源(注册表索引 / API)没有本机落点, 必须干净地回"不可读", 而不是抛 NPE.
 */
public class SkillContentReaderTest {

    private final SkillContentReader reader = new SkillContentReader();

    @TempDir
    Path tmp;

    // ---------- fixtures ----------

    private static final String ENTRY = "---\nname: pdf-processing\ndescription: Extract text.\n---\n\n"
            + "# PDF Processing\n\nRead the table.\n";

    private Path skillDir;

    private Path materialiseSkillDir() throws IOException {
        skillDir = Files.createDirectories(tmp.resolve("pdf-processing"));
        Files.write(skillDir.resolve("SKILL.md"), ENTRY.getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(skillDir.resolve("scripts"));
        Files.write(skillDir.resolve("scripts/extract.py"), "print('rows')\n".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(skillDir.resolve("references"));
        Files.write(skillDir.resolve("references/notes.md"), "notes body\n".getBytes(StandardCharsets.UTF_8));
        return skillDir;
    }

    private SkillDto localSkill(String... extraResourcePaths) throws IOException {
        if (skillDir == null) materialiseSkillDir();
        List<SkillResourceDto> resources = new ArrayList<SkillResourceDto>();
        resources.add(new SkillResourceDto("scripts/extract.py", "script", 15L));
        resources.add(new SkillResourceDto("references/notes.md", "reference", 11L));
        for (String p : extraResourcePaths) resources.add(new SkillResourceDto(p, "other", 3L));
        return SkillDto.builder()
                .id("alpha/pdf-processing").slug("pdf-processing").name("pdf-processing")
                .description("Extract text.").source("alpha").sourceType("local")
                .origin(skillDir.resolve("SKILL.md").toUri().toString())
                .skillFilePath("pdf-processing/SKILL.md")
                .format(SkillFormat.AGENT_SKILLS)
                .resources(resources)
                .contentHash(SkillFiles.sha256(ENTRY))
                .aliases(Arrays.asList("legacy/pdf"))
                .build();
    }

    private static SkillDto remoteSkill(String origin) {
        return SkillDto.builder()
                .id("api/remote-skill").slug("remote-skill").name("remote-skill").description("d")
                .source("api").sourceType("api").origin(origin)
                .skillFilePath("https://skills.example.invalid/v1/skills")
                .format(SkillFormat.SKILLS_SH_API)
                .resources(Collections.singletonList(new SkillResourceDto("SKILL.md", "other", 10L)))
                .build();
    }

    private static List<String> pathsOf(List<Map<String, Object>> tree) {
        List<String> out = new ArrayList<String>();
        for (Map<String, Object> node : tree) out.add(String.valueOf(node.get("path")));
        return out;
    }

    // ---------- 正常读 ----------

    @Test
    public void readMain_returnsEntryText_andIsAddressableByIdAndAlias() throws Exception {
        SkillDto dto = localSkill();
        SkillRegistry registry = new SkillRegistry();
        registry.register(dto);

        SkillContentReader.Content main = reader.readMain(registry.require("alpha/pdf-processing"));
        assertTrue(main.isAvailable());
        assertTrue(main.getText().contains("# PDF Processing"));
        assertTrue(main.getText().startsWith("---"), "frontmatter 也是正文的一部分, 不做二次加工");
        assertTrue(main.getPath().endsWith("SKILL.md"));
        org.junit.jupiter.api.Assertions.assertNull(main.getReason(), "读成功时不该带原因");
        assertEquals(main.getText(), reader.readMain(registry.require("legacy/pdf")).getText(),
                "按 alias 与按 id 必须读到同一份文件");
        assertEquals(main.getText(), reader.readMain(registry.require("pdf-processing")).getText(),
                "按 slug 同理");
        assertEquals(main.getText(), reader.readMain(registry.require("ALPHA/PDF-PROCESSING")).getText(),
                "寻址大小写不敏感");
        assertTrue(reader.localEntry(dto).isPresent());
        assertEquals(skillDir.resolve("SKILL.md").toAbsolutePath().normalize(),
                reader.localEntry(dto).get().toAbsolutePath().normalize());
    }

    @Test
    public void readResource_previewsScriptsAndReferences() throws Exception {
        SkillDto dto = localSkill();
        SkillContentReader.Content script = reader.readResource(dto, "scripts/extract.py");
        assertTrue(script.isAvailable());
        assertEquals("print('rows')\n", script.getText());
        assertTrue(script.getPath().endsWith("scripts/extract.py"), script.getPath());

        SkillContentReader.Content notes = reader.readResource(dto, "references/notes.md");
        assertTrue(notes.isAvailable());
        assertTrue(notes.getText().contains("notes body"));

        assertEquals(notes.getText(), reader.readResource(dto, "references\\notes.md").getText(),
                "反斜杠写法要先归一成 '/'");
    }

    @Test
    public void readResource_missingFileFailsCleanly_andUndeclaredPathIsRejected() throws Exception {
        SkillDto dto = localSkill();
        // 清单里声明但本机没有: 干净地不可读
        SkillContentReader.Content gone = reader.readResource(localSkill("references/gone.md"), "references/gone.md");
        assertFalse(gone.isAvailable());
        assertTrue(gone.getPath().endsWith("references/gone.md"), gone.getPath());
        assertNullReason(gone);
        assertNotNull(gone.getReason());

        // 文件在盘上, 但没被清单声明 —— 详情页不能顺手指到邻居家的文件
        Files.write(skillDir.resolve("secret.env"), "TOKEN=abc\n".getBytes(StandardCharsets.UTF_8));
        SkillException e = assertThrows(SkillException.class, () -> reader.readResource(dto, "secret.env"));
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("secret.env"), e.getMessage());
    }

    private static void assertNullReason(SkillContentReader.Content content) {
        org.junit.jupiter.api.Assertions.assertNull(content.getText(), "不可读时不该带正文");
    }

    // ---------- 沙箱: 路径穿越 ----------

    @Test
    public void pathTraversalAttempts_areRejected() throws Exception {
        SkillDto dto = localSkill();
        List<String> hostile = Arrays.asList(
                "../outside.md",
                "../../outside.md",
                "scripts/../../outside.md",
                "/etc/passwd",
                "/Users/Shared/outside.md",
                "..\\outside.md",
                "..%2f..%2foutside.md",
                "%2e%2e/outside.md",
                "%2E%2E%2Foutside.md",
                "",
                null);
        for (String path : hostile) {
            SkillException e = assertThrows(SkillException.class,
                    () -> reader.readResource(dto, path), "越界路径必须被拒: " + path);
            assertEquals(400, e.getCode(), "越界要按 400 回, 不能 500: " + path);
        }
    }

    @Test
    public void declaredButEscapingResource_isStillRefused() throws Exception {
        // 恶意注册表索引可以把 "../outside.md" 写进 files[], 读取侧不能因为"清单里有"就放行
        SkillDto dto = localSkill("../outside.md");
        Files.write(tmp.resolve("outside.md"), "OUTSIDE\n".getBytes(StandardCharsets.UTF_8));
        SkillException e = assertThrows(SkillException.class, () -> reader.readResource(dto, "../outside.md"));
        assertEquals(400, e.getCode());
        assertFalse(e.getMessage().contains("OUTSIDE"));
    }

    @Test
    public void symlinkInsideSkillDir_cannotBeUsedToEscapeTheSandbox() throws Exception {
        materialiseSkillDir();
        Path secret = Files.write(tmp.resolve("cluster-secret.env"), "TOP SECRET\n".getBytes(StandardCharsets.UTF_8));
        Path link = skillDir.resolve("assets");
        Files.createSymbolicLink(link, tmp);
        assertTrue(Files.isRegularFile(skillDir.resolve("assets/cluster-secret.env")),
                "夹具自检: 符号链接确实把 skill 目录之外的文件接了进来");

        SkillDto dto = localSkill("assets/cluster-secret.env");
        SkillException e = assertThrows(SkillException.class,
                () -> reader.readResource(dto, "assets/cluster-secret.env"),
                "清单里声明的符号链接指向 skill 目录之外, 解析真实路径后必须越界被拒");
        assertEquals(400, e.getCode(), "越界要按 400 回, 不能把目录外的内容当成预览给出");
        assertTrue(Files.isRegularFile(secret), "只读检查不该动被指向的文件");
    }

    // ---------- 体积上限 ----------

    @Test
    public void oversizedFile_isRefusedWithAReason_notTruncatedSilently() throws Exception {
        materialiseSkillDir();
        StringBuilder big = new StringBuilder();
        while (big.length() < 600 * 1024) {
            big.append("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n");
        }
        Path huge = skillDir.resolve("huge.md");
        Files.write(huge, big.toString().getBytes(StandardCharsets.UTF_8));

        SkillDto dto = localSkill("huge.md");
        SkillContentReader.Content content = reader.readResource(dto, "huge.md");
        assertFalse(content.isAvailable(), "超过预览上限的内容不能直接给出去");
        assertNullReason(content);
        assertTrue(content.getReason().contains("超过预览上限"), String.valueOf(content.getReason()));
        assertTrue(content.getReason().contains(String.valueOf(big.length())),
                "原因里要带上实际大小: " + content.getReason());
    }

    // ---------- 没有本机落点的条目 ----------

    @Test
    public void registryOrApiSourcedSkill_failsCleanlyInsteadOfNpe() throws Exception {
        SkillContentReader.Content https = reader.readMain(remoteSkill("https://skills.example.invalid/v1/x"));
        assertFalse(https.isAvailable());
        assertNotNull(https.getReason());
        assertTrue(https.getReason().contains("远端"), https.getReason());
        assertEquals("https://skills.example.invalid/v1/skills", https.getPath(), "要回显它声明过的入口");
        assertNullReason(https);

        SkillContentReader.Content none = reader.readMain(remoteSkill(null));
        assertFalse(none.isAvailable());
        assertNotNull(none.getReason());
        assertFalse(reader.localEntry(remoteSkill(null)).isPresent());
        assertFalse(reader.localEntry(null).isPresent());
        assertFalse(reader.readResource(remoteSkill(null), "SKILL.md").isAvailable(),
                "远端条目连清单里的文件都读不到, 但也不能炸");

        // 曾经落地、现在被撤下的条目: 路径还在元数据里, 文件已经没了
        SkillDto stale = localSkill().toBuilder()
                .origin(tmp.resolve("deleted").resolve("SKILL.md").toUri().toString()).build();
        assertFalse(reader.localEntry(stale).isPresent());
        SkillContentReader.Content staleContent = reader.readMain(stale);
        assertFalse(staleContent.isAvailable());
        assertNotNull(staleContent.getReason());
    }

    // ---------- 文件树 ----------

    @Test
    public void fileTree_listsEntryFirstThenDeclaredResources() throws Exception {
        SkillDto dto = localSkill();
        List<Map<String, Object>> tree = reader.fileTree(dto);
        assertEquals(Arrays.asList("pdf-processing/SKILL.md", "scripts/extract.py", "references/notes.md"),
                pathsOf(tree));
        assertEquals(Boolean.TRUE, tree.get(0).get("entry"));
        assertEquals("script", tree.get(1).get("kind"));
        assertEquals(15L, tree.get(1).get("sizeBytes"));
        assertEquals("reference", tree.get(2).get("kind"));
        assertEquals(4, reader.fileTree(localSkill("references/gone.md")).size(), "入口 + 清单里声明的 3 条");
        assertEquals(Arrays.asList("v1/skills", "SKILL.md"),
                pathsOf(reader.fileTree(remoteSkill("https://skills.example.invalid/v1/skills"))),
                "远端条目也要列出入口与清单, 但不能假装能读");

        SkillDto noPath = SkillDto.builder().id("x").slug("x").name("x").build();
        assertEquals(Collections.singletonList("SKILL.md"), pathsOf(reader.fileTree(noPath)),
                "skillFilePath 缺失时回落成 SKILL.md");
    }
}
