package com.zifang.z.skill.core.discover;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.skill.api.dto.AggregateReportDto;
import com.zifang.z.skill.api.dto.SkillSourceDto;
import com.zifang.z.skill.api.spec.SkillFormat;
import com.zifang.z.skill.core.aggregate.SkillAggregator;
import com.zifang.z.skill.core.registry.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件清单 → 派生来源.
 *
 * <p>回归的是一条静默缺陷: {@code plugin.json} 实际住在 {@code <插件根>/.qoder-plugin/} 里,
 * 老实现把 {@code manifest.getParent()} 当插件根, 于是派生来源指向 {@code .qoder-plugin/skills},
 * 扫描得到 0 条却一声不响 — 装了插件的平台在 Marketplace 上是空的.
 */
public class PluginManifestReaderTest {

    @TempDir
    Path tmp;

    private final PluginManifestReader reader = new PluginManifestReader(new ObjectMapper());
    private final SkillSource parent = new SkillSource("fs-plugin", SkillFormat.PLUGIN_MANIFEST, "/plugins", 60);

    private Path write(Path file, String json) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private void writeSkillMd(Path dir, String name, String body) throws IOException {
        Files.createDirectories(dir);
        Files.write(dir.resolve("SKILL.md"),
                ("---\nname: " + name + "\ndescription: " + body + "\n---\n\n" + body + "\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static SkillSource first(List<SkillSource> out) {
        assertFalse(out.isEmpty(), "清单应当派生出至少一个来源");
        return out.get(0);
    }

    @Test
    void qoderPluginManifest_resolvesSkillsDirFromTheRealPluginRoot() throws IOException {
        Path pluginRoot = tmp.resolve("installed/demo-tools");
        Path manifest = write(pluginRoot.resolve(".qoder-plugin").resolve("plugin.json"),
                "{\"name\":\"demo-tools\",\"version\":\"1.2.3\"}");
        writeSkillMd(pluginRoot.resolve("skills").resolve("pdf"), "pdf", "处理 PDF");

        SkillSource derived = first(reader.derive(manifest, parent));
        assertEquals(pluginRoot.resolve("skills").toAbsolutePath().toString(), derived.getPath(),
                "派生来源必须指向 <插件根>/skills, 而不是 .qoder-plugin/skills");
        assertFalse(derived.getPath().contains(".qoder-plugin"), derived.getPath());
        assertEquals("demo-tools@fs-plugin", derived.getId());
        assertEquals("plugin", derived.getSourceType());
        assertEquals(SkillFormat.AGENT_SKILLS, derived.getFormat());
        assertEquals(parent.getPriority() + 1, derived.getPriority());
    }

    @Test
    void pluginWithoutSkillsDir_fallsBackToPluginRoot() throws IOException {
        Path pluginRoot = tmp.resolve("flat-plugin");
        Path manifest = write(pluginRoot.resolve(".qoder-plugin").resolve("plugin.json"),
                "{\"name\":\"flat-plugin\"}");

        SkillSource derived = first(reader.derive(manifest, parent));
        assertEquals(pluginRoot.toAbsolutePath().toString(), derived.getPath());
        assertTrue(Files.isDirectory(java.nio.file.Paths.get(derived.getPath())), derived.getPath());
    }

    @Test
    void legacyManifestAtPluginRoot_stillWorks() throws IOException {
        Path pluginRoot = tmp.resolve("legacy-plugin");
        Path manifest = write(pluginRoot.resolve("plugin.json"), "{\"name\":\"legacy-plugin\"}");
        writeSkillMd(pluginRoot.resolve("skills").resolve("ocr"), "ocr", "识别发票");

        SkillSource derived = first(reader.derive(manifest, parent));
        assertEquals(pluginRoot.resolve("skills").toAbsolutePath().toString(), derived.getPath());
    }

    @Test
    void installedPluginsV2_trustsOnlyTheDeclaredInstallPath() throws IOException {
        Path elsewhere = tmp.resolve("qoder-state");
        Path realPlugin = tmp.resolve("vscode-ext/demo");
        writeSkillMd(realPlugin.resolve("skills").resolve("commit"), "commit", "生成提交信息");
        Path manifest = write(elsewhere.resolve("installed_plugins_v2.json"),
                "{\"plugins\":{\"demo@market.invalid\":[{\"installPath\":\"" + realPlugin.toString()
                        + "\",\"version\":\"0.9.0\"}]}}");

        SkillSource derived = first(reader.derive(manifest, parent));
        assertEquals(realPlugin.resolve("skills").toAbsolutePath().toString(), derived.getPath());
        assertEquals("demo@fs-plugin", derived.getId());
    }

    @Test
    void installedPluginsV2_skipsEntriesWithoutInstallPath() throws IOException {
        Path manifest = write(tmp.resolve("installed_plugins_v2.json"),
                "{\"plugins\":{\"ghost@market.invalid\":[{\"version\":\"0.0.1\"}]}}");

        assertTrue(reader.derive(manifest, parent).isEmpty(), "清单没写路径就不猜路径");
    }

    @Test
    void marketplaceJson_resolvesRelativeSourceAgainstManifestDir() throws IOException {
        Path cursorDir = tmp.resolve(".cursor-plugin");
        Path plugin = tmp.resolve("plugins/exporter");
        writeSkillMd(plugin.resolve("skills").resolve("csv"), "csv", "导出 CSV");
        Path manifest = write(cursorDir.resolve("marketplace.json"),
                "{\"name\":\"z-cursor\",\"plugins\":[{\"name\":\"exporter\",\"source\":\"../plugins/exporter\"}]}");

        SkillSource derived = first(reader.derive(manifest, parent));
        assertEquals(plugin.resolve("skills").toAbsolutePath().normalize().toString(),
                java.nio.file.Paths.get(derived.getPath()).toAbsolutePath().normalize().toString());
        assertTrue(derived.getId().startsWith("z-cursor/exporter@"), derived.getId());
    }

    @Test
    void brokenManifest_yieldsNothingInsteadOfThrowing() throws IOException {
        Path manifest = write(tmp.resolve("junk-plugin").resolve("plugin.json"), "{not json");

        assertTrue(reader.derive(manifest, parent).isEmpty());
    }

    @Test
    void pluginIdFor_findsTheOwningPluginId() throws IOException {
        Path pluginRoot = tmp.resolve("ns-plugin");
        Path manifest = write(pluginRoot.resolve(".qoder-plugin").resolve("plugin.json"),
                "{\"name\":\"ns-plugin\"}");
        Path skillDir = pluginRoot.resolve("skills").resolve("pdf");
        writeSkillMd(skillDir, "pdf", "处理 PDF");

        Optional<String> id = reader.pluginIdFor(skillDir);
        assertTrue(id.isPresent(), "反查不到插件 id, plugin:skill 命名空间就无从谈起");
        assertEquals("ns-plugin", id.get());
    }

    @Test
    void aggregatorScansDerivedPluginSource_endToEnd() throws IOException {
        Path pluginRoot = tmp.resolve("plugins/e2e-tools");
        write(pluginRoot.resolve(".qoder-plugin").resolve("plugin.json"), "{\"name\":\"e2e-tools\"}");
        writeSkillMd(pluginRoot.resolve("skills").resolve("pdf"), "pdf", "处理 PDF");
        writeSkillMd(pluginRoot.resolve("skills").resolve("ocr"), "ocr", "识别发票");

        SkillRegistry registry = new SkillRegistry();
        SkillAggregator agg = new SkillAggregator(registry, new ObjectMapper());
        agg.addSource(new SkillSource("plugins", SkillFormat.PLUGIN_MANIFEST,
                tmp.resolve("plugins").toString(), 60));
        AggregateReportDto report = agg.refresh();

        assertEquals(2, report.getSkillCount(), "插件里的两个 skill 都要被聚进来: " + registry.snapshot().keySet());
        SkillSourceDto derived = null;
        for (SkillSourceDto s : registry.getSources()) {
            if (s.getId().startsWith("e2e-tools@")) derived = s;
        }
        assertNotNull(derived, "派生来源要出现在来源清单里: " + registry.getSources().size());
        assertEquals(2, derived.getSkillCount(), "派生来源扫到 0 条就是这次缺陷的形状");
        assertFalse(derived.getOrigin().contains(".qoder-plugin"), derived.getOrigin());
        assertTrue(registry.snapshot().containsKey("pdf"));
        assertTrue(registry.snapshot().containsKey("ocr"));
    }
}
