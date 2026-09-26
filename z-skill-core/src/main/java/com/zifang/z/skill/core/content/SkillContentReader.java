package com.zifang.z.skill.core.content;

import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.exception.SkillException;
import com.zifang.z.skill.core.spec.SkillFiles;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 读取已收录 skill 的正文与随包文件(市场详情页的预览能力).
 *
 * <p>只读注册中心里已经存在的条目, 并且任何相对路径都必须落在该 skill 自己的目录里 —
 * 详情页一旦能读任意路径, 就变成了本机文件读取漏洞.
 */
public class SkillContentReader {

    private static final long MAX_PREVIEW_BYTES = 512L * 1024;

    public static final class Content {
        public final boolean available;
        public final String path;
        public final String text;
        public final String reason;

        Content(boolean available, String path, String text, String reason) {
            this.available = available;
            this.path = path;
            this.text = text;
            this.reason = reason;
        }

        public boolean isAvailable() {
            return available;
        }

        public String getPath() {
            return path;
        }

        public String getText() {
            return text;
        }

        public String getReason() {
            return reason;
        }
    }

    /** skill 主文件(SKILL.md / *.md / .mdc)在本机的绝对路径; 远端来源返回空. */
    public Optional<Path> localEntry(SkillDto dto) {
        String origin = dto == null ? null : dto.getOrigin();
        if (origin == null || !origin.startsWith("file:")) return Optional.empty();
        try {
            Path p = Paths.get(new URI(origin)).normalize();
            return Files.isRegularFile(p) ? Optional.of(p) : Optional.<Path>empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public Content readMain(SkillDto dto) {
        Optional<Path> entry = localEntry(dto);
        if (!entry.isPresent()) {
            return new Content(false, dto.getSkillFilePath(), null, "该 skill 来自远端来源, 未落地到本机");
        }
        // 与上面的 unavailable 分支同一个 display 口径: 主文件按 skillFilePath 报(源根相对), 资源按清单里的相对路径报
        return readAt(entry.get(), dto.getSkillFilePath());
    }

    /**
     * 读取 skill 目录内的一个附属文件.
     *
     * @param relativePath 相对于 skill 目录的路径, 必须出现在该 skill 的 resources 清单里
     */
    public Content readResource(SkillDto dto, String relativePath) {
        String normalized = SkillFiles.slashify(relativePath);
        if (!SkillFiles.isSafeRelativePath(normalized)) {
            throw SkillException.badRequest("非法路径: " + relativePath);
        }
        boolean declared = false;
        for (com.zifang.z.skill.api.dto.SkillResourceDto res : dto.getResources()) {
            if (normalized.equals(res.getPath())) {
                declared = true;
                break;
            }
        }
        if (!declared) {
            throw SkillException.badRequest("该文件不在 skill 的资源清单里: " + normalized);
        }
        Optional<Path> entryOpt = localEntry(dto);
        if (!entryOpt.isPresent()) {
            return new Content(false, normalized, null, "该 skill 来自远端来源, 未落地到本机");
        }
        Path entry = entryOpt.get();
        Path dir = entry.getParent();
        Path target = dir.resolve(normalized).normalize();
        if (!target.startsWith(dir)) {
            throw SkillException.badRequest("路径越出 skill 目录: " + normalized);
        }
        // 词法检查拦不住符号链接: 声明在清单里的 assets/ 若是指向家目录的软链, 详情页就变成任意文件读取
        try {
            Path realDir = dir.toRealPath();
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(realDir)) {
                throw SkillException.badRequest("符号链接越出 skill 目录: " + normalized);
            }
        } catch (java.nio.file.NoSuchFileException e) {
            // 声明过但文件不见了, 是"漂移"不是"非法路径" — 交给 readAt 走 unavailable 分支, 别伪装成 400
        } catch (IOException e) {
            throw SkillException.badRequest("无法解析该文件: " + normalized);
        }
        return readAt(target, normalized);
    }

    /**
     * @param display 给客户端看的路径, 必须是相对路径: 这个字段曾经直接放 {@code target.toString()},
     *                于是 {@code GET /skill/{id}/content} 把宿主机目录整个发出去了 —— 而同一份响应里
     *                {@code files[].path}、{@code resources[].path} 都是相对写法, 只有成功分支是绝对的.
     */
    private Content readAt(Path target, String display) {
        try {
            long size = Files.size(target);
            if (size > MAX_PREVIEW_BYTES) {
                return new Content(false, display, null, size + " bytes 超过预览上限");
            }
            String text = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
            return new Content(true, display, text, null);
        } catch (IOException e) {
            // 别把 e.getMessage() 原样发出去: NoSuchFileException / AccessDeniedException 的 message
            // 就是那个绝对路径(实测 "…/SKILL.md (Operation not permitted)"), 而这条出口在公共面上.
            return new Content(false, display, null, e.getClass().getSimpleName() + ": " + display);
        }
    }

    /** 详情页需要的文件树: 主文件 + resources. */
    public List<Map<String, Object>> fileTree(SkillDto dto) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        Map<String, Object> main = new LinkedHashMap<String, Object>();
        main.put("path", dto.getSkillFilePath() == null ? "SKILL.md" : lastSegments(dto.getSkillFilePath(), 2));
        main.put("entry", true);
        out.add(main);
        for (com.zifang.z.skill.api.dto.SkillResourceDto res : dto.getResources()) {
            Map<String, Object> node = new LinkedHashMap<String, Object>();
            node.put("path", res.getPath());
            node.put("kind", res.getKind());
            node.put("sizeBytes", res.getSizeBytes());
            out.add(node);
        }
        return out;
    }

    private static String lastSegments(String path, int n) {
        String[] parts = path.split("/");
        int from = Math.max(0, parts.length - n);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            if (sb.length() > 0) sb.append('/');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

}
