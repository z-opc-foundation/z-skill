package com.zifang.z.skill.core.discover;

import com.zifang.z.skill.api.dto.SkillResourceDto;
import com.zifang.z.skill.api.spec.SkillFormat;
import com.zifang.z.skill.api.spec.SkillSpec;
import com.zifang.z.skill.core.spec.SkillFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 文件系统侧的来源分类扫描器 — "这个目录属于哪个平台、每个文件该按哪种格式收".
 *
 * <p>识别的落点覆盖了目前各家共同收敛的那几条路径: {@code <name>/SKILL.md}(Agent Skills 规范)、
 * {@code .cursor/rules/*.mdc}(Cursor Rules)、{@code AGENTS.md}(Codex)、
 * {@code .github/(instructions/*.instructions.md|copilot-instructions.md)}(Copilot)、
 * {@code .well-known/&#42;/index.json}(注册表索引)、插件清单, 以及 z-skill 0.1.x 的平铺 {@code *.md}.
 *
 * <p>顺序按路径排序, 保证聚合结果可复现.
 */
public class FilesystemScanner {

    private static final Set<String> SKIP_DIRS = new TreeSet<String>(Arrays.asList(
            ".git", "node_modules", "target", "build", "dist", ".idea", ".mvn", "__pycache__", ".venv", "venv"));

    /** 这些目录下的 .md 是正文/素材, 不是独立 skill. */
    private static final Set<String> NON_SKILL_DIRS = new TreeSet<String>(Arrays.asList(
            "references", "reference", "scripts", "assets", "examples", "example", "templates", "template",
            "docs", "doc", "_doc", "fixtures", "tests", "test", "images", "media", "output", "outputs"));

    /**
     * 扫描一个目录来源.
     *
     * @param source 本地目录来源; format 为 UNKNOWN 时自动识别
     * @param issues 分类过程中发现的问题(目录不存在等)就地追加
     */
    public List<RawSkill> scan(SkillSource source, List<com.zifang.z.skill.api.dto.SkillIssueDto> issues) throws IOException {
        Path root = java.nio.file.Paths.get(source.getPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            issues.add(com.zifang.z.skill.api.dto.SkillIssueDto.error("source-missing", source.getId(),
                    source.getId(), source.getPath(), "目录不存在或不是目录"));
            return Collections.emptyList();
        }
        int depth = source.getMaxDepth() >= 0 ? source.getMaxDepth() : SkillSpec.MAX_SCAN_DEPTH;
        List<Path> files = collect(root, depth);
        Map<Path, List<Path>> byDir = new LinkedHashMap<Path, List<Path>>();
        for (Path f : files) {
            List<Path> bucket = byDir.get(f.getParent());
            if (bucket == null) {
                bucket = new ArrayList<Path>();
                byDir.put(f.getParent(), bucket);
            }
            bucket.add(f);
        }

        boolean auto = source.getFormat() == null || source.getFormat() == SkillFormat.UNKNOWN;
        List<RawSkill> out = new ArrayList<RawSkill>();
        Set<Path> consumed = new java.util.HashSet<Path>();
        List<Path> skillDirRoots = new ArrayList<Path>();

        List<Path> dirs = new ArrayList<Path>(byDir.keySet());
        Collections.sort(dirs);
        Set<Path> overrideDirs = new java.util.HashSet<Path>();
        for (Path f : files) {
            if (f.getFileName().toString().equalsIgnoreCase("AGENTS.override.md")) {
                overrideDirs.add(f.getParent());
            }
        }
        for (Path dir : dirs) {
            if (!auto && source.getFormat() != SkillFormat.AGENT_SKILLS) continue;
            Path skillMd = findSkillMd(byDir.get(dir));
            if (skillMd == null) continue;
            out.add(toAgentSkill(source, root, dir, skillMd));
            consumed.add(skillMd);
            skillDirRoots.add(dir);
        }
        Collections.sort(skillDirRoots, new Comparator<Path>() {
            @Override
            public int compare(Path a, Path b) {
                return a.toString().compareTo(b.toString());
            }
        });

        for (Path file : files) {
            if (consumed.contains(file) || underSkillDir(file, skillDirRoots)) continue;
            if (overrideDirs.contains(file.getParent())
                    && file.getFileName().toString().equalsIgnoreCase("AGENTS.md")) {
                continue; // SkillFormat#AGENTS_MD 承诺的"就近覆盖": 同目录有 override 时普通 AGENTS.md 让位
            }
            RawSkill raw = classify(source, root, file, auto);
            if (raw != null) out.add(raw);
        }
        if (out.size() > SkillSpec.MAX_SKILLS_PER_SOURCE) {
            issues.add(com.zifang.z.skill.api.dto.SkillIssueDto.error("source-too-large", source.getId(),
                    source.getId(), source.getPath(), "候选条目 " + out.size() + " 超过上限 " + SkillSpec.MAX_SKILLS_PER_SOURCE));
            return new ArrayList<RawSkill>(out.subList(0, SkillSpec.MAX_SKILLS_PER_SOURCE));
        }
        return out;
    }

    private List<Path> collect(Path root, int maxDepth) throws IOException {
        final Path base = root;
        List<Path> out = new ArrayList<Path>();
        visit(root, base, 0, maxDepth, out);
        Collections.sort(out);
        return out;
    }

    private void visit(Path dir, Path root, int depth, int maxDepth, List<Path> out) throws IOException {
        if (depth > maxDepth || out.size() > SkillSpec.MAX_SKILLS_PER_SOURCE * 20L) return;
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            List<Path> children = new ArrayList<Path>();
            s.forEach(children::add);
            Collections.sort(children);
            for (Path c : children) {
                String name = c.getFileName().toString();
                // 符号链接一律不"跟": 一个指向家目录的软链就能把来源外的文件伪装成 realPath 在根内的
                // 条目, 绕开后面的词法越界检查, 目录软链还会让递归绕圈.
                // 但"不跟"不等于"不看": 真实仓库里 <root>/AGENTS.md -> _doc/.../AGENTS.md 是 Codex/Claude
                // 官方推荐的写法, 一律跳过等于把整个 AGENTS_MD 形状从真实语料里抹掉(条目静默消失).
                // 折中: 只有"真实落点仍在本次遍历根之内"的<文件>软链才收 —— 越界的那部分照样进不来,
                // 目录软链(递归入口)仍然一律跳过.
                if (Files.isSymbolicLink(c) && !isFileLinkInsideBase(c, root)) continue;
                if (Files.isDirectory(c)) {
                    if (SKIP_DIRS.contains(name)) continue;
                    visit(c, root, depth + 1, maxDepth, out);
                } else if (Files.isRegularFile(c)) {
                    out.add(c);
                }
            }
        }
    }

    /**
     * 该符号链接是否指向"本次遍历根之内"的一个普通文件.
     *
     * <p>目录软链一律 false: 它是递归入口, 跟进去既可能绕圈, 也能把根外的整棵子树伪装成根内内容.
     * 文件软链只有在真实落点(realPath)仍然落在遍历根内时才放行 —— 越界读取在
     * {@code SkillContentReader} 那侧本来就会被 realPath 校验拒掉, 这里放行不会放松沙箱.
     */
    private boolean isFileLinkInsideBase(Path link, Path root) {
        try {
            if (Files.isDirectory(link)) return false;
            Path real = link.toRealPath();
            return Files.isRegularFile(real) && real.startsWith(root.toRealPath());
        } catch (IOException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Path findSkillMd(List<Path> siblings) {
        if (siblings == null) return null;
        for (Path p : siblings) {
            if (SkillFiles.isSkillMd(p.getFileName().toString())) return p;
        }
        return null;
    }

    private boolean underSkillDir(Path file, List<Path> skillDirRoots) {
        for (Path d : skillDirRoots) {
            if (file.startsWith(d)) return true;
        }
        return false;
    }

    private RawSkill toAgentSkill(SkillSource source, Path root, Path dir, Path skillMd) {
        List<SkillResourceDto> resources = new ArrayList<SkillResourceDto>();
        try {
            for (Path p : collect(dir, SkillSpec.MAX_SCAN_DEPTH)) {
                if (p.equals(skillMd)) continue;
                String rel = SkillFiles.relativize(dir, p);
                resources.add(new SkillResourceDto(rel, kindOf(rel), sizeOf(p)));
            }
        } catch (IOException ignored) {
            // 资源清单缺失不影响 skill 本体收录
        }
        String dirName = dir.getFileName() == null ? root.getFileName().toString() : dir.getFileName().toString();
        return new RawSkill()
                .format(SkillFormat.AGENT_SKILLS)
                .origin(uriOf(skillMd))
                .root(root)
                .entry(skillMd)
                .entryRelative(SkillFiles.slashify(SkillFiles.relativize(root, skillMd)))
                .dirName(dirName)
                .fallbackName(dirName)
                .resources(resources);
    }

    private RawSkill classify(SkillSource source, Path root, Path file, boolean auto) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        SkillFormat declared = source.getFormat();
        RawSkill raw = base(source, root, file);
        if (name.endsWith(".mdc")) {
            if (!auto && declared != SkillFormat.CURSOR_RULES) return null;
            return raw.format(SkillFormat.CURSOR_RULES);
        }
        if (name.equals("agents.md") || name.equals("agents.override.md") || name.equals("claude.md")) {
            if (!auto && declared != SkillFormat.AGENTS_MD) return null;
            return raw.format(SkillFormat.AGENTS_MD);
        }
        // Copilot 的两个落点都在 .github 下, 但来源根本身就可能就是 .github, 所以按文件名判定
        if (name.equals("copilot-instructions.md") || name.endsWith(".instructions.md")) {
            if (!auto && declared != SkillFormat.COPILOT_INSTRUCTIONS) return null;
            return raw.format(SkillFormat.COPILOT_INSTRUCTIONS);
        }
        String relPath = SkillFiles.slashify(SkillFiles.relativize(root, file));
        if (name.endsWith(".json")) {
            if (!auto && declared != SkillFormat.REGISTRY_INDEX && declared != SkillFormat.PLUGIN_MANIFEST
                    && declared != SkillFormat.SKILLS_SH_API) return null;
            if (relPath != null && relPath.contains(".well-known/")) {
                return raw.format(SkillFormat.REGISTRY_INDEX);
            }
            if (name.equals("installed_plugins_v2.json") || name.equals("plugin.json")
                    || name.equals("marketplace.json")) {
                return raw.format(SkillFormat.PLUGIN_MANIFEST);
            }
            if (declared == SkillFormat.SKILLS_SH_API) return raw.format(SkillFormat.SKILLS_SH_API);
            return null;
        }
        if (name.endsWith(".md")) {
            if (!auto && declared != SkillFormat.FLAT_MARKDOWN) return null;
            if (!SkillFiles.isLooseSkillMd(name)) return null;
            Path parent = file.getParent();
            if (parent != null && NON_SKILL_DIRS.contains(parent.getFileName().toString().toLowerCase(Locale.ROOT))
                    && declared != SkillFormat.FLAT_MARKDOWN) {
                return null;
            }
            return raw.format(SkillFormat.FLAT_MARKDOWN);
        }
        return null;
    }

    private RawSkill base(SkillSource source, Path root, Path file) {
        String rel = SkillFiles.relativize(root, file);
        String name = file.getFileName().toString();
        String stem = name.indexOf('.') > 0 ? name.substring(0, name.lastIndexOf('.')) : name;
        return new RawSkill()
                .origin(uriOf(file))
                .root(root)
                .entry(file)
                .entryRelative(SkillFiles.slashify(rel))
                .dirName(file.getParent() == null ? "" : file.getParent().getFileName().toString())
                .fallbackName(stem);
    }

    private static String uriOf(Path p) {
        try {
            return p.toUri().toString();
        } catch (Exception e) {
            return p.toString();
        }
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String kindOf(String relPath) {
        String first = relPath.contains("/") ? relPath.substring(0, relPath.indexOf('/')).toLowerCase(Locale.ROOT) : "";
        if (first.equals("scripts") || first.equals("bin")) return "script";
        if (first.equals("references") || first.equals("reference")) return "reference";
        if (first.equals("assets") || first.equals("images")) return "asset";
        return "other";
    }
}
