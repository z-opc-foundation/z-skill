package com.zifang.z.skill.api.exception;

/**
 * Skill 平台异常基类.
 */
public class SkillException extends RuntimeException {

    private final int code;

    public SkillException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static SkillException notFound(String name) {
        return new SkillException(404, "skill not found: " + name);
    }

    public static SkillException alreadyInstalled(String name) {
        return new SkillException(409, "skill already installed: " + name);
    }

    public static SkillException invalidFrontmatter(String path, String reason) {
        return new SkillException(400, "invalid frontmatter at " + path + ": " + reason);
    }
}