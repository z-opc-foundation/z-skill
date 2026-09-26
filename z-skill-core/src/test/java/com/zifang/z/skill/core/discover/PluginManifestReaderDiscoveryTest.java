package com.zifang.z.skill.core.discover;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.skill.api.dto.SkillIssueDto;
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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 发现层: {@link PluginManifestReader} 的"清单 → 派生来源"测试.
 *
 * <p>插件不是 skill 而是 skill 的容器, 所以这里钉的是: 派生出来的来源必须落在真实可扫的 skill 目录上
 * (不许留占位符、不许指到清单自己的包装目录)、一个安装条目一条来源、清单里的扩展 id 要进 source id
 * 做命名空间、清单坏了只安静返回空不炸聚合.
 *
 * <p>类名带 Discovery 后缀而不是 {@code PluginManifestReaderTest}: 同包里 {@code PluginManifestReaderTest}
 * 已被聚合侧(端到端 aggregate)的测试占用, 两份都要留着, 故按层命名以免互相覆盖.
 */
public class PluginManifestReaderDiscoveryTest {

    private static final Path FIXTURES = fixturesRoot();

    private final PluginManifestReader reader = new PluginManifestReader(new ObjectMapper());
    private final FilesystemScanner scanner = new FilesystemScanner();

    @TempDir
    Path tmp;

    // ---------------------------------------------------------------- .qoder-plugin/plugin.json

    @Test
    public void qoderPluginManifestDerivesOneAgentSkillsSourceNamedByThePluginId() throws IOException {
        Path pluginRoot = FIXTURES.resolve("plugin/qoder-plugin");
        List<SkillSource> derived = reader.derive(pluginRoot.resolve(".qoder-plugin/plugin.json"),
                parent("qoder-installed", 40));

        assertEquals(1, derived.size(), "一份 plugin.json 只派生一条来源");
        SkillSource source = derived.get(0);
        assertEquals("demo-tools@qoder-installed", source.getId(),
                "清单里的扩展 id 要进 source id, 供下游按 plugin:skill 命名空间");
        assertEquals(Arrays.asList("AGENT_SKILLS", "plugin", 41),
                Arrays.asList(source.getFormat().name(), source.getSourceType(), source.getPriority()),
                "派生来源: 插件内是标准 SKILL.md 目录, sourceType=plugin, 优先级继承父来源再降一级");
        assertEquals(pluginRoot.resolve("skills").toString(), source.getPath(),
                "派生来源必须落在插件根的 skills/ 目录: .qoder-plugin 只是清单所在的包装目录");
    }

    @Test
    public void derivedPluginSourceIsActuallyScannableForBundledSkills() throws IOException {
        Path pluginRoot = FIXTURES.resolve("plugin/qoder-plugin");
        List<SkillSource> derived = reader.derive(pluginRoot.resolve(".qoder-plugin/plugin.json"),
                parent("qoder-installed", 40));

        assertEquals(Collections.singletonList("hello/SKILL.md"),
                rels(derived.isEmpty() ? new ArrayList<RawSkill>()
                        : scanner.scan(derived.get(0), new ArrayList<SkillIssueDto>())),
                "插件里打包的 skill 必须能被派生来源扫到, 否则插件对用户等于不存在");
    }

    // ---------------------------------------------------------------- installed_plugins_v2.json

    @Test
    public void registryInstallPathResolvesToTheRealSkillsDirectoryNotAPlaceholder() throws IOException {
        Path pluginRoot = FIXTURES.resolve("plugin/qoder-plugin");
        Path manifest = write(tmp.resolve("installed_plugins_v2.json"),
                registry(Collections.singletonList(
                        entry("demo-tools@demo-market", pluginRoot, "1.4.0"))));

        List<SkillSource> derived = reader.derive(manifest, parent("qoder-registry", 20));

        assertEquals(1, derived.size());
        assertEquals(pluginRoot.resolve("skills").toString(), derived.get(0).getPath(),
                "installPath 指到插件根时要落到真实的 skills/ 子目录, 且不含 __PLUGIN_ROOT__ 占位符");
        assertEquals(Collections.singletonList("hello/SKILL.md"),
                rels(scanner.scan(derived.get(0), new ArrayList<SkillIssueDto>())), "派生来源要能真的扫出插件自带的 skill");
    }

    @Test
    public void unsubstitutedPlaceholderInTheShippedFixtureYieldsNoUsableSource() {
        List<SkillSource> derived = reader.derive(FIXTURES.resolve("plugin/registry/installed_plugins_v2.json"),
                parent("qoder-registry", 20));

        assertEquals(Collections.emptyList(), ids(derived),
                "未展开的 __PLUGIN_ROOT__ 占位 installPath 不是真实目录, 不该产出来源(reader javadoc: 不猜路径)");
    }

    @Test
    public void oneDerivedSourcePerInstalledPluginWithExtensionIdNamespacing() throws IOException {
        Path alpha = tmp.resolve("inst/alpha-ext");
        Path beta = tmp.resolve("inst/beta-ext");
        write(alpha.resolve("skills/one/SKILL.md"), skill("one"));
        write(beta.resolve("skills/two/SKILL.md"), skill("two"));
        String missingInstallPath = "\"gamma-ext@market\": [{ \"scope\": \"user\", \"version\": \"3.0.0\" }]";
        String ghostDir = "\"ghost@market\": [{ \"installPath\": \"" + tmp.resolve("nope") + "\" }]";
        Path manifest = write(tmp.resolve("multi/installed_plugins_v2.json"),
                registry(Arrays.asList(
                        entry("alpha-ext@market", alpha, "1.0.0"),
                        entry("beta-ext@market", beta, "2.0.0"),
                        missingInstallPath,
                        ghostDir,
                        "\"empty@market\": []")));

        List<SkillSource> derived = reader.derive(manifest, parent("qoder-registry", 20));

        assertEquals(Arrays.asList("alpha-ext@qoder-registry", "beta-ext@qoder-registry"), ids(derived),
                "每个安装插件一条来源, 且 source id 用扩展 id 做命名空间; 缺 installPath/目录不存在/空数组的条目只跳过不产出空来源");
        assertEquals(Collections.singletonList("one/SKILL.md"),
                rels(scanner.scan(derived.get(0), new ArrayList<SkillIssueDto>())), "alpha 插件的 skills/ 只含一个 skill");
        assertEquals(Collections.singletonList("two/SKILL.md"),
                rels(scanner.scan(derived.get(1), new ArrayList<SkillIssueDto>())), "beta 插件同理");
    }

    @Test
    public void brokenManifestsAreToleratedWithoutCrashing() throws IOException {
        assertEquals(0, reader.derive(
                write(tmp.resolve("a/installed_plugins_v2.json"), "{ 这不是 JSON"), parent("p", 10)).size(),
                "JSON 坏了只返回空, 不能抛异常打断聚合");
        assertEquals(0, reader.derive(tmp.resolve("never-existed.json"), parent("p", 10)).size(),
                "清单文件不存在同样只返回空");
        assertEquals(0, reader.derive(write(tmp.resolve("b/installed_plugins_v2.json"),
                "{\"plugins\": \"不是对象\"}"), parent("p", 10)).size(), "plugins 形状不对时不猜");
        assertEquals(0, reader.derive(write(tmp.resolve("c/installed_plugins_v2.json"),
                "{\"plugins\": {}}"), parent("p", 10)).size(), "空清单就是零来源");
        assertEquals(0, reader.derive(write(tmp.resolve("d/plugin.json"), "{\"version\":\"1.0.0\"}"),
                parent("p", 10)).size(), "没有 name/id 的 plugin.json 无法命名, 不产出来源");
    }

    @Test
    public void unknownManifestFieldsDoNotBreakDerivation() throws IOException {
        Path pluginRoot = tmp.resolve("unknown-fields");
        write(pluginRoot.resolve("skills/s/SKILL.md"), skill("s"));
        List<SkillSource> derived = reader.derive(write(pluginRoot.resolve(".qoder-plugin/plugin.json"),
                "{\"name\":\"ext-x\",\"version\":\"9.9.9\",\"displayName\":\"Ext X\","
                        + "\"author\":{\"name\":\"n\"},\"components\":[{\"kind\":\"mcp\"}],"
                        + "\"totallyUnknown\":{\"deep\":[1,2,3]}}"), parent("p", 10));

        assertEquals(1, derived.size(), "清单里的规范外字段不能影响派生");
        assertEquals(Arrays.asList("ext-x@p", "AGENT_SKILLS"),
                Arrays.asList(derived.get(0).getId(), derived.get(0).getFormat().name()),
                "未知字段不该改变 id 与识别出的格式");
    }

    @Test
    public void marketplaceManifestResolvesRelativePluginDirs() throws IOException {
        Path pluginDir = tmp.resolve("cursor-plugin/plugins/weather");
        write(pluginDir.resolve("skills/forecast/SKILL.md"), skill("forecast"));
        Path manifest = write(tmp.resolve("cursor-plugin/marketplace.json"),
                "{\"name\":\"acme-mkt\",\"plugins\":["
                        + "{\"name\":\"weather\",\"source\":\"./plugins/weather\",\"version\":\"0.2.0\"},"
                        + "{\"name\":\"no-source\"},"
                        + "{\"name\":\"missing\",\"source\":\"./plugins/ghost\"}]}");

        List<SkillSource> derived = reader.derive(manifest, parent("cursor", 60));

        assertEquals(1, derived.size(), "缺 source / 目录不存在的条目不产出来源");
        assertEquals("acme-mkt/weather@cursor", derived.get(0).getId(), "marketplace 名 + 插件名共同构成命名空间");
        assertEquals(pluginDir.resolve("skills").toAbsolutePath().normalize(),
                Paths.get(derived.get(0).getPath()).toAbsolutePath().normalize(),
                "marketplace 里的相对 source 要按清单所在目录展开成真实可扫的 skill 目录");
    }

    // ---------------------------------------------------------------- 反查 pluginId

    @Test
    public void pluginIdReverseLookupWalksUpToTheManifest() {
        Path pluginRoot = FIXTURES.resolve("plugin/qoder-plugin");
        assertEquals(Optional.of("demo-tools"), reader.pluginIdFor(pluginRoot.resolve("skills/hello")),
                "从 skill 目录反查它属于哪个插件");
        assertFalse(reader.pluginIdFor(tmp).isPresent(), "普通目录不该被认成插件");
        assertFalse(reader.pluginIdFor(null).isPresent(), "null 不能抛异常");
    }

    // ---------------------------------------------------------------- helpers

    private static SkillSource parent(String id, int priority) {
        return new SkillSource(id, SkillFormat.UNKNOWN, "/tmp/never-a-real-source", priority);
    }

    private static String skill(String name) {
        return "---\nname: " + name + "\ndescription: bundled " + name + "\n---\n# " + name + "\n";
    }

    private static String entry(String key, Path installPath, String version) {
        return "\"" + key + "\": [{ \"scope\": \"user\", \"installPath\": \"" + installPath + "\","
                + " \"version\": \"" + version + "\", \"installedAt\": 1790000000000, \"userVisible\": false }]";
    }

    private static String registry(List<String> entries) {
        return "{\n  \"version\": 2,\n  \"plugins\": {\n    " + join(entries) + "\n  }\n}\n";
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(",\n    ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static List<String> ids(List<SkillSource> sources) {
        List<String> out = new ArrayList<String>();
        for (SkillSource s : sources) out.add(s.getId());
        return out;
    }

    private static List<String> rels(List<RawSkill> raws) {
        List<String> out = new ArrayList<String>();
        for (RawSkill r : raws) out.add(r.getEntryRelative());
        return out;
    }

    private static Path write(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    /** 优先 src/test/resources(源码树), 回落 target/test-classes(surefire 复制位); 都找不到就抛. */
    private static Path fixturesRoot() {
        List<Path> candidates = new ArrayList<Path>();
        try {
            Path classes = Paths.get(PluginManifestReaderDiscoveryTest.class.getProtectionDomain()
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
            if (Files.isDirectory(c.resolve("plugin/qoder-plugin/.qoder-plugin"))) return c;
        }
        throw new IllegalStateException("找不到 fixtures 根目录, 试过的路径: " + candidates);
    }
}
