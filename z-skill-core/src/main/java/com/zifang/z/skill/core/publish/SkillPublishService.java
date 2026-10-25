package com.zifang.z.skill.core.publish;

import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.exception.SkillException;
import com.zifang.z.skill.api.spec.SkillFormat;
import com.zifang.z.skill.core.registry.SkillRegistry;
import com.zifang.z.skill.core.scan.SkillSecurityScanner;
import com.zifang.z.skill.core.spec.SkillFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 技能「小册子平台」的写出/发布/拿下来三段.
 *
 * <p>为什么要有这一层: 市场面原本只有「读 + 装」两个动作（search / detail / content / install /
 * uninstall / refresh）⇒ <b>没有任何办法把一个自己写的 skill 放进市场</b> ⇒ 所谓"技能市场"实际上是个
 * 只读目录, 谁都只能消费别人的东西。前端曾经对着 {@code /skill/upload}、{@code /skill/download} 写过,
 * 但那两个端点在 z-skill 后端<b>从未存在过</b>（前端对着想象中的契约写, 失败后回退 mock 假数据）。
 *
 * <p>这里补的就是那三段:
 * <ol>
 *   <li><b>写出并发布</b>（{@link #publish}）: 把一份 SKILL.md (+ 随包文件) 落到发布目录,
 *       发布目录本身是市场的一个来源 ⇒ 发布完刷新即可被检索/详情/预览看到, 不需要额外注册动作;</li>
 *   <li><b>拿下来</b>（{@link #download}）: 取回一个 skill 的完整文件包（主文件 + 随包文件）;</li>
 *   <li><b>装到本机</b>（{@link #installToDisk}）: 把文件复制到安装目录, 目录布局对齐
 *       {@code <root>/<category>/<name>/SKILL.md} ⇒ agent 运行时的 skill 扫描器能直接吃到.</li>
 * </ol>
 *
 * <p><b>安全口径</b>: 发布是「把内容写进市场」这个写操作, 所以走 fail-closed ——
 * 静态风险体检判到 high/critical 的正文<b>直接拒收</b>（critical 的典型是
 * {@code curl ... | sh} 这种把远端内容交给 shell 的写法, 等于远程代码执行入口）;
 * medium 允许发布但把风险档位与命中片段一起带在响应里, 由人决定要不要留.
 *
 * <p><b>路径口径</b>: slug 必须是规范里的 {@code ^[a-z0-9]+(-[a-z0-9]+)*$}（否则市场里的 id 不可预测,
 * 而 id 是安装/下载/开关的句柄）; 随包文件必须是安全相对路径; 写入前<b>先全量校验再落盘</b>,
 * 避免"校验到一半失败 ⇒ 市场里留下半个 skill"。
 */
public class SkillPublishService {

    /** 发布目录在市场里的来源 id —— 发布出去的 skill 在市场里就挂在这个来源下. */
    public static final String PUBLISHED_SOURCE_ID = "z-opc-published";

    /** 未指定分类时的默认分类. */
    public static final String DEFAULT_CATEGORY = "custom";

    /** slug 必须能原样当 id 用（市场里的 id = slugify(frontmatter.name)）⇒ 只能是规范里的形状. */
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    /** 分类是目录的一层 ⇒ 单段安全名. */
    private static final Pattern CATEGORY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$");

    private static final int MAX_EXTRA_FILES = 32;
    private static final long MAX_FILE_BYTES = 512L * 1024L;
    private static final long MAX_TOTAL_BYTES = 4L * 1024L * 1024L;
    private static final int MAX_SLUG_LEN = 64;
    private static final int MAX_DESCRIPTION_LEN = 1024;
    private static final int MAX_TITLE_LEN = 200;
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private final Path publishDir;
    private final Path installDir;
    private final SkillRegistry registry;
    private final SkillSecurityScanner securityScanner;

    public SkillPublishService(Path publishDir, Path installDir, SkillRegistry registry,
                                SkillSecurityScanner securityScanner) {
        this.publishDir = publishDir == null ? null : publishDir.toAbsolutePath().normalize();
        this.installDir = installDir == null ? null : installDir.toAbsolutePath().normalize();
        this.registry = registry;
        this.securityScanner = securityScanner;
    }

    public Path getPublishDir() {
        return publishDir;
    }

    public Path getInstallDir() {
        return installDir;
    }

    // ------------------------------------------------------------------ 发布

    /**
     * 写出并发布一个 skill.
     *
     * @param body {@code slug}（或 {@code name}）必填; 可选 {@code title} {@code description}
     *             {@code category} {@code tags} {@code version} {@code content}（SKILL.md 正文）
     *             {@code files}（随包文件 map: 相对路径 → 文本）
     * @return 发布结果摘要（含最终 id / 落盘目录 / 文件清单 / 风险档位）
     */
    public Map<String, Object> publish(Map<String, Object> body) {
        if (body == null) {
            throw SkillException.badRequest("发布请求体为空");
        }
        if (publishDir == null) {
            throw SkillException.sourceUnavailable(PUBLISHED_SOURCE_ID, "未配置发布目录(z.skill.publish-dir)");
        }
        String slug = readSlug(body);
        String category = readCategory(body);
        String title = readText(body, "title", MAX_TITLE_LEN);
        String description = readText(body, "description", MAX_DESCRIPTION_LEN);
        String version = readText(body, "version", 64);
        List<String> tags = readTags(body);

        // ---- 主文件正文: 自己写 frontmatter, 保证 name 与目录名一致 ⇒ 市场里的 id 可预测 ----
        String rawContent = readText(body, "content", MAX_BODY_BYTES);
        if (rawContent == null) {
            rawContent = readText(body, "markdown", MAX_BODY_BYTES);
        }
        if (rawContent == null) {
            rawContent = readText(body, "body", MAX_BODY_BYTES);
        }
        String skillMd = buildSkillMd(slug, category, title, description, version, tags, rawContent);

        // ---- 随包文件: 先全量校验, 一个不合法就整单拒收（不留半个 skill 在市场里）----
        Map<String, String> extraFiles = readExtraFiles(body);
        long totalBytes = skillMd.getBytes(StandardCharsets.UTF_8).length;
        for (Map.Entry<String, String> e : extraFiles.entrySet()) {
            totalBytes += e.getValue().getBytes(StandardCharsets.UTF_8).length;
        }
        if (totalBytes > MAX_TOTAL_BYTES) {
            throw SkillException.badRequest("发布内容合计 " + totalBytes + " bytes, 超过上限 " + MAX_TOTAL_BYTES);
        }

        // ---- 风险体检: fail-closed, high/critical 拒收 ----
        SkillSecurityScanner.Verdict verdict = scanPublishContent(slug, category, skillMd, extraFiles);
        if (verdict != null && !"safe".equals(verdict.getRiskLevel()) && !"medium".equals(verdict.getRiskLevel())) {
            throw SkillException.badRequest("发布被拒: 风险体检判到 " + verdict.getRiskLevel()
                    + " —— " + joinFindings(verdict.getFindings()));
        }

        // ---- 落盘: <publishDir>/<category>/<slug>/ ----
        Path target = publishDir.resolve(category).resolve(slug).normalize();
        if (!target.startsWith(publishDir)) {
            throw SkillException.badRequest("发布路径越界: " + category + "/" + slug);
        }
        List<String> written = new ArrayList<String>();
        try {
            Files.createDirectories(target);
            writeUtf8(target.resolve("SKILL.md"), skillMd);
            written.add("SKILL.md");
            for (Map.Entry<String, String> e : extraFiles.entrySet()) {
                Path f = target.resolve(e.getKey()).normalize();
                if (!f.startsWith(target)) {
                    throw SkillException.badRequest("随包文件路径越界: " + e.getKey());
                }
                if (f.getParent() != null) {
                    Files.createDirectories(f.getParent());
                }
                writeUtf8(f, e.getValue());
                written.add(e.getKey());
            }
        } catch (IOException e) {
            throw SkillException.badRequest("发布落盘失败: " + e.getClass().getSimpleName());
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("slug", slug);
        out.put("category", category);
        out.put("title", title);
        out.put("description", description);
        out.put("version", version);
        out.put("tags", tags);
        out.put("source", PUBLISHED_SOURCE_ID);
        // 相对路径: 绝对路径不出现在公共面（与 SkillContentReader 的既有口径一致）
        out.put("path", category + "/" + slug);
        out.put("files", written);
        out.put("contentHash", SkillFiles.sha256(skillMd));
        out.put("riskLevel", verdict == null ? "unscanned" : verdict.getRiskLevel());
        out.put("findings", verdict == null ? Collections.<String>emptyList() : verdict.getFindings());
        out.put("note", "发布目录是市场的一个来源, 刷新聚合后即可检索到; 响应里的 id 以刷新后的注册中心为准");
        return out;
    }

    // ------------------------------------------------------------------ 拿下来

    /**
     * 取一个 skill 的完整文件包.
     *
     * @param id 市场里的 id / slug / 别名
     */
    public Map<String, Object> download(String id) {
        SkillDto dto = requireSkill(id);
        Path dir = skillDir(dto);
        // ⚠ 相对路径必须以**真实落点**为基准: macOS 上 /var 是 /private/var 的软链,
        //   拿未解析的 root 去 relativize 真实路径 ⇒ 每个文件都判"越界" ⇒ 文件包恒空.
        Path base = realDir(dir);
        Map<String, String> files = new LinkedHashMap<String, String>();
        long total = 0L;
        try {
            List<Path> list = SkillFiles.walkMaxDepth(dir, 6, MAX_EXTRA_FILES + 8);
            for (Path p : list) {
                Path real = p.toRealPath();
                if (!real.startsWith(base)) {
                    continue; // 符号链接越界 ⇒ 跳过（与内容预览同一口径）
                }
                long size = Files.size(real);
                if (size > MAX_FILE_BYTES) {
                    continue;
                }
                String rel = SkillFiles.slashify(SkillFiles.relativize(base, real));
                if (rel == null) {
                    continue;
                }
                files.put(rel, new String(Files.readAllBytes(real), StandardCharsets.UTF_8));
                total += size;
            }
        } catch (IOException e) {
            throw SkillException.badRequest("读取 skill 文件包失败: " + e.getClass().getSimpleName());
        }
        if (files.isEmpty()) {
            throw SkillException.notFound(id);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", dto.getId());
        out.put("slug", dto.getSlug());
        out.put("name", dto.getName());
        out.put("description", dto.getDescription());
        out.put("category", dto.getCategory());
        out.put("source", dto.getSource());
        out.put("format", dto.getFormatId());
        out.put("files", files);
        out.put("fileCount", files.size());
        out.put("totalBytes", total);
        return out;
    }

    // ------------------------------------------------------------ 装到本机

    /**
     * 把一个 skill 的文件复制到安装目录（agent 运行时的 skill 根）.
     *
     * <p>目录布局 {@code <installDir>/<category>/<slug>/SKILL.md} —— 与 agent 运行时扫描器
     * （{@code <root>/<category>/<name>/SKILL.md}）的约定一致 ⇒ 装完即被吃到.
     */
    public Map<String, Object> installToDisk(String id) {
        if (installDir == null) {
            throw SkillException.sourceUnavailable("install", "未配置安装目录(z.skill.install-dir)");
        }
        SkillDto dto = requireSkill(id);
        Path dir = skillDir(dto);
        Path base = realDir(dir);
        String category = dto.getCategory() == null || dto.getCategory().isEmpty()
                ? DEFAULT_CATEGORY : dto.getCategory();
        if (!CATEGORY.matcher(category).matches()) {
            category = DEFAULT_CATEGORY;
        }
        String slug = dto.getSlug() == null || dto.getSlug().isEmpty() ? dto.getId() : dto.getSlug();
        Path target = installDir.resolve(category).resolve(slug).normalize();
        if (!target.startsWith(installDir)) {
            throw SkillException.badRequest("安装路径越界: " + category + "/" + slug);
        }
        List<String> written = new ArrayList<String>();
        try {
            if (Files.exists(target)) {
                deleteTree(target);
            }
            Files.createDirectories(target);
            List<Path> list = SkillFiles.walkMaxDepth(dir, 6, MAX_EXTRA_FILES + 8);
            for (Path p : list) {
                Path real = p.toRealPath();
                if (!real.startsWith(base)) {
                    continue;
                }
                String rel = SkillFiles.slashify(SkillFiles.relativize(base, real));
                if (rel == null) {
                    continue;
                }
                Path dest = target.resolve(rel).normalize();
                if (!dest.startsWith(target)) {
                    continue;
                }
                if (dest.getParent() != null) {
                    Files.createDirectories(dest.getParent());
                }
                Files.copy(real, dest, StandardCopyOption.REPLACE_EXISTING);
                written.add(rel);
            }
        } catch (IOException e) {
            throw SkillException.badRequest("安装落盘失败: " + e.getClass().getSimpleName());
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", dto.getId());
        out.put("slug", slug);
        out.put("category", category);
        out.put("installedTo", category + "/" + slug);
        out.put("files", written);
        out.put("note", "安装目录布局 <root>/<category>/<name>/SKILL.md, 与 agent 运行时的 skill 扫描约定一致");
        return out;
    }

    // ------------------------------------------------------------------ 内部

    private SkillDto requireSkill(String id) {
        if (id == null || id.trim().isEmpty()) {
            throw SkillException.badRequest("需要 id 参数");
        }
        return registry.require(id.trim());
    }

    /** skill 本体文件所在目录（远端来源的 skill 没落地 ⇒ 不可读）. */
    private Path skillDir(SkillDto dto) {
        String origin = dto.getOrigin();
        if (origin == null || !origin.startsWith("file:")) {
            throw SkillException.badRequest("该 skill 来自远端来源, 未落地到本机, 无法取文件包");
        }
        Path entry;
        try {
            entry = Paths.get(new java.net.URI(origin)).normalize();
        } catch (Exception e) {
            throw SkillException.badRequest("该 skill 的落点无法解析");
        }
        if (!Files.isRegularFile(entry)) {
            throw SkillException.notFound(dto.getId());
        }
        Path dir = entry.getParent();
        if (dir == null) {
            throw SkillException.badRequest("该 skill 的落点没有目录");
        }
        return dir.toAbsolutePath().normalize();
    }

    /** 用最小 SkillDto 让扫描器判级（扫描器只看正文, 不依赖目录里的其它字段）. */
    private SkillSecurityScanner.Verdict scanPublishContent(String slug, String category, String skillMd,
                                                            Map<String, String> extraFiles) {
        if (securityScanner == null) {
            return null;
        }
        StringBuilder all = new StringBuilder(skillMd);
        for (String v : extraFiles.values()) {
            all.append('\n').append(v);
        }
        SkillDto probe = SkillDto.builder()
                .slug(slug)
                .name(slug)
                .category(category)
                .format(SkillFormat.AGENT_SKILLS)
                .build();
        return securityScanner.scan(probe, all.toString());
    }

    private static String joinFindings(List<String> findings) {
        if (findings == null || findings.isEmpty()) {
            return "(无)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < findings.size() && i < 5; i++) {
            if (i > 0) sb.append("; ");
            sb.append(findings.get(i));
        }
        return sb.toString();
    }

    private static String buildSkillMd(String slug, String category, String title, String description,
                                       String version, List<String> tags, String rawContent) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(slug).append('\n');
        sb.append("description: ").append(oneLine(description == null || description.isEmpty()
                ? ("自发布技能 " + slug) : description)).append('\n');
        sb.append("category: ").append(category).append('\n');
        if (version != null && !version.isEmpty()) {
            sb.append("version: ").append(version).append('\n');
        }
        if (!tags.isEmpty()) {
            sb.append("tags: ").append(joinComma(tags)).append('\n');
        }
        if (title != null && !title.isEmpty()) {
            // title 不在规范的已知键里 ⇒ 归一化时会进 metadata, 不会影响 slug/id
            sb.append("title: ").append(oneLine(title)).append('\n');
        }
        sb.append("---\n\n");
        String body = rawContent == null ? "" : rawContent.trim();
        if (!body.isEmpty()) {
            sb.append(body).append('\n');
        }
        return sb.toString();
    }

    private static String oneLine(String s) {
        return s.replace("\r", " ").replace("\n", " ").trim();
    }

    private static String joinComma(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static String readSlug(Map<String, Object> body) {
        String raw = readText(body, "slug", MAX_SLUG_LEN + 8);
        if (raw == null) {
            raw = readText(body, "name", MAX_SLUG_LEN + 8);
        }
        if (raw == null) {
            throw SkillException.badRequest("发布需要 slug 字段");
        }
        String slug = raw.trim().toLowerCase();
        if (slug.isEmpty() || slug.length() > MAX_SLUG_LEN || !SLUG.matcher(slug).matches()) {
            throw SkillException.badRequest("slug 必须是 " + MAX_SLUG_LEN + " 字符内的小写字母/数字/连字组合"
                    + "(^[a-z0-9]+(-[a-z0-9]+)*$), 收到: " + raw);
        }
        return slug;
    }

    private static String readCategory(Map<String, Object> body) {
        String raw = readText(body, "category", 64);
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_CATEGORY;
        }
        String category = raw.trim();
        if (!CATEGORY.matcher(category).matches()) {
            throw SkillException.badRequest("category 必须是单段安全名(字母/数字/下划线/连字), 收到: " + raw);
        }
        return category;
    }

    private static List<String> readTags(Map<String, Object> body) {
        Object raw = body.get("tags");
        Set<String> out = new LinkedHashSet<String>();
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                String t = o == null ? "" : String.valueOf(o).trim();
                if (!t.isEmpty() && t.length() <= 64) {
                    out.add(t);
                }
            }
        } else if (raw instanceof String) {
            for (String t : ((String) raw).split("[,;]")) {
                String s = t.trim();
                if (!s.isEmpty() && s.length() <= 64) {
                    out.add(s);
                }
            }
        }
        return new ArrayList<String>(out);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> readExtraFiles(Map<String, Object> body) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        Object raw = body.get("files");
        if (raw == null) {
            return out;
        }
        if (!(raw instanceof Map)) {
            throw SkillException.badRequest("files 必须是 {相对路径: 文本} 的对象");
        }
        Map<String, Object> src = (Map<String, Object>) raw;
        if (src.size() > MAX_EXTRA_FILES) {
            throw SkillException.badRequest("随包文件超过上限 " + MAX_EXTRA_FILES + " 个");
        }
        for (Map.Entry<String, Object> e : src.entrySet()) {
            String rel = SkillFiles.slashify(e.getKey());
            if (!SkillFiles.isSafeRelativePath(rel)) {
                throw SkillException.badRequest("随包文件路径不合法: " + e.getKey());
            }
            if ("SKILL.md".equalsIgnoreCase(rel)) {
                throw SkillException.badRequest("随包文件不能覆盖 SKILL.md（主文件由 slug/frontmatter 生成）");
            }
            if (rel.split("/").length > 4) {
                throw SkillException.badRequest("随包文件目录层级过深: " + rel);
            }
            String content = e.getValue() == null ? "" : String.valueOf(e.getValue());
            if (content.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
                throw SkillException.badRequest("随包文件超过上限 " + MAX_FILE_BYTES + " bytes: " + rel);
            }
            out.put(rel, content);
        }
        return out;
    }

    private static String readText(Map<String, Object> body, String key, int maxLen) {
        Object raw = body.get(key);
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw);
        if (s.length() > maxLen) {
            throw SkillException.badRequest(key + " 超过长度上限 " + maxLen);
        }
        return s;
    }

    private static void writeUtf8(Path p, String content) throws IOException {
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    }

    /** 目录的真实落点（解析软链）—— 比对与相对路径都以它为基准. */
    private static Path realDir(Path dir) {
        try {
            return dir.toRealPath();
        } catch (IOException e) {
            return dir.toAbsolutePath().normalize();
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> list = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> s = Files.walk(root)) {
            s.forEach(list::add);
        }
        Collections.reverse(list);
        for (Path p : list) {
            Files.deleteIfExists(p);
        }
    }
}
