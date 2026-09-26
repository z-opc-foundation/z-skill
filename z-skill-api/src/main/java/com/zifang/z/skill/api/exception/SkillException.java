package com.zifang.z.skill.api.exception;

/**
 * Skill 平台异常基类.
 *
 * <p>{@code code} 直接复用 HTTP 语义, 控制面按它映射状态码.
 */
public class SkillException extends RuntimeException {

    private final int code;
    private final String errorId;

    public SkillException(int code, String errorId, String message) {
        super(message);
        this.code = code;
        this.errorId = errorId;
    }

    public int getCode() {
        return code;
    }

    /** 稳定的机器可读错误标识, 对齐外部 API 的 {@code {"error","message"}} 包络. */
    public String getErrorId() {
        return errorId;
    }

    public static SkillException notFound(String name) {
        return new SkillException(404, "skill_not_found", "skill not found: " + name);
    }

    public static SkillException alreadyInstalled(String name) {
        return new SkillException(409, "already_installed", "skill already installed: " + name);
    }

    public static SkillException invalidFrontmatter(String path, String reason) {
        return new SkillException(400, "invalid_frontmatter", "invalid frontmatter at " + path + ": " + reason);
    }

    public static SkillException badRequest(String message) {
        return new SkillException(400, "bad_request", message);
    }

    public static SkillException unknownSource(String id) {
        return new SkillException(404, "unknown_source", "skill source not found: " + id);
    }

    public static SkillException sourceUnavailable(String id, String reason) {
        return new SkillException(502, "source_unavailable", "skill source unavailable: " + id + " (" + reason + ")");
    }
}
