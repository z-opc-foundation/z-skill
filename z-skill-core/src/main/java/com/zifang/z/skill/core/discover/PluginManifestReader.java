package com.zifang.z.skill.core.discover;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.skill.api.spec.SkillFormat;
import com.zifang.z.skill.core.normalize.JsonSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * 插件清单 → 派生 Skill 来源.
 *
 * <p>插件本身不是 skill, 它是"一批 skill 的容器", 所以清单的产出是 source 而不是 skill:
 * <ul>
 *   <li>Qoder {@code installed_plugins_v2.json}: {@code plugins -> {"name@marketplace":[{installPath,version}]}}</li>
 *   <li>Qoder/Cursor 插件根: {@code .qoder-plugin/plugin.json}, {@code .cursor-plugin/marketplace.json}
 *       给出 pluginId, 于是里面的 skill 以 {@code pluginId:skillName} 出现(Qoder 目录就是这套写法)</li>
 * </ul>
 *
 * <p>只信任清单里写明的 installPath, 不猜路径、不拼 URL.
 */
public class PluginManifestReader {

    private final ObjectMapper mapper;

    public PluginManifestReader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 从一个清单文件派生出可继续扫描的目录来源.
     *
     * @param manifest 清单文件
     * @param parent   派生来源的父 source(继承优先级)
     */
    public List<SkillSource> derive(Path manifest, SkillSource parent) {
        List<SkillSource> out = new ArrayList<SkillSource>();
        JsonNode root;
        try {
            root = mapper.readTree(new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return out;
        }
        String fileName = manifest.getFileName().toString();
        if ("installed_plugins_v2.json".equals(fileName)) {
            JsonNode plugins = root.get("plugins");
            if (plugins == null || !plugins.isObject()) return out;
            for (Iterator<String> it = plugins.fieldNames(); it.hasNext(); ) {
                String key = it.next();
                JsonNode entries = plugins.get(key);
                JsonNode newest = entries != null && entries.isArray() && entries.size() > 0 ? entries.get(0) : null;
                String installPath = JsonSupport.text(newest, "installPath");
                if (installPath == null) continue;
                String pluginId = key.contains("@") ? key.substring(0, key.indexOf('@')) : key;
                addSkillsDir(out, parent, pluginId, java.nio.file.Paths.get(installPath),
                        JsonSupport.text(newest, "version"));
            }
            return out;
        }
        if ("marketplace.json".equals(fileName)) {
            JsonNode entries = root.get("plugins");
            String marketplace = JsonSupport.text(root, "name");
            if (entries != null && entries.isArray()) {
                for (JsonNode node : entries) {
                    String pluginName = JsonSupport.text(node, "name", "id");
                    String src = JsonSupport.text(node, "source", "path", "localPath");
                    if (pluginName == null || src == null) continue;
                    Path base = manifest.getParent();
                    Path resolved = base == null ? java.nio.file.Paths.get(src) : base.resolve(src);
                    addSkillsDir(out, parent, marketplace == null ? pluginName : marketplace + "/" + pluginName,
                            resolved, JsonSupport.text(node, "version"));
                }
            }
            return out;
        }
        if ("plugin.json".equals(fileName)) {
            String pluginId = JsonSupport.text(root, "name", "id");
            if (pluginId == null) return out;
            addSkillsDir(out, parent, pluginId, pluginRootOf(manifest), JsonSupport.text(root, "version"));
        }
        return out;
    }

    /**
     * 清单自己所在的目录未必是插件根: Qoder/Cursor 的写法是 {@code <pluginRoot>/.qoder-plugin/plugin.json},
     * 直接取 parent 会指向点目录本身, 于是 "skills" 永远找不到, 派生出一个 0 条目的静默空来源.
     */
    private static Path pluginRootOf(Path manifest) {
        Path dir = manifest.getParent();
        if (dir == null) return null;
        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
        return name.startsWith(".") ? dir.getParent() : dir;
    }

    private void addSkillsDir(List<SkillSource> out, SkillSource parent, String pluginId, Path pluginRoot, String version) {
        if (pluginRoot == null) return;
        Path skillsDir = pluginRoot.resolve("skills");
        Path chosen = Files.isDirectory(skillsDir) ? skillsDir : pluginRoot;
        if (!Files.isDirectory(chosen)) return;
        out.add(new SkillSource(pluginId + "@" + parent.getId(), SkillFormat.AGENT_SKILLS,
                chosen.toAbsolutePath().toString(), parent.getPriority() + 1)
                .sourceType("plugin")
                .categoryHint("plugin")
                .maxDepth(parent.getMaxDepth()));
    }

    /**
     * 反查一个 skill 目录属于哪个插件(用于 {@code plugin:skill} 命名空间).
     */
    public Optional<String> pluginIdFor(Path skillDir) {
        Path cursor = skillDir == null ? null : skillDir.toAbsolutePath().normalize().getParent();
        int guard = 0;
        while (cursor != null && guard++ < 8) {
            Path qoder = cursor.resolve(".qoder-plugin").resolve("plugin.json");
            if (Files.isRegularFile(qoder)) {
                Optional<String> id = readPluginId(qoder);
                if (id.isPresent()) return id;
            }
            Path legacy = cursor.resolve("plugin.json");
            if (Files.isRegularFile(legacy) && cursor.endsWith("skills")) {
                Optional<String> id = readPluginId(legacy);
                if (id.isPresent()) return id;
            }
            if (Files.isDirectory(cursor.resolve("skills")) && cursor.getFileName() != null) {
                return Optional.of(cursor.getFileName().toString());
            }
            cursor = cursor.getParent();
        }
        return Optional.empty();
    }

    private Optional<String> readPluginId(Path manifest) {
        try {
            JsonNode root = mapper.readTree(new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8));
            String id = JsonSupport.text(root, "name", "id");
            return Optional.ofNullable(id);
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
