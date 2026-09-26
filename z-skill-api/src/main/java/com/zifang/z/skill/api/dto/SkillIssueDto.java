package com.zifang.z.skill.api.dto;

/**
 * 兼容性/静态扫描发现的一条问题.
 *
 * <p><b>severity 口径 — 整条聚合链路只认这一把尺:</b>
 * <ul>
 *   <li>{@code error} — <b>这条没有进目录</b>(条目被丢弃, 或整个来源失败).</li>
 *   <li>{@code warning} — 进了目录, 但不合规/有瑕疵(缺 frontmatter、名字被清洗、越界文件被剔掉…).</li>
 *   <li>{@code info} — 进了目录, 只是留痕(描述从正文兜出来之类的推导).</li>
 * </ul>
 * 之所以必须这样切: 体检报告要能算账 — {@code rawCount = 有产出的候选 + droppedCount}, 而控制台上的
 * 红色计数就是"我们没能收进来的东西". 一旦把"收了但脏"记成 error, 同一份文件会同时出现在
 * "已收录 29 条"和"error 19 条"里, 两边读数互相打脸, 谁也没法拿这个报告做判断.
 *
 * <p>{@code code} 是有界的 kebab-case 裸码(不带 severity、不带取值), 这样"按码计数"才聚得起来;
 * 具体取值/原因放 {@code message}.
 */
public final class SkillIssueDto {

    private final String severity;
    private final String code;
    private final String skillId;
    private final String source;
    private final String path;
    private final String message;

    public SkillIssueDto(String severity, String code, String skillId, String source, String path, String message) {
        this.severity = severity;
        this.code = code;
        this.skillId = skillId;
        this.source = source;
        this.path = path;
        this.message = message;
    }

    public static SkillIssueDto error(String code, String skillId, String source, String path, String message) {
        return new SkillIssueDto("error", code, skillId, source, path, message);
    }

    public static SkillIssueDto warning(String code, String skillId, String source, String path, String message) {
        return new SkillIssueDto("warning", code, skillId, source, path, message);
    }

    public static SkillIssueDto info(String code, String skillId, String source, String path, String message) {
        return new SkillIssueDto("info", code, skillId, source, path, message);
    }

    public String getSeverity() { return severity; }
    public String getCode() { return code; }
    public String getSkillId() { return skillId; }
    public String getSource() { return source; }
    public String getPath() { return path; }
    public String getMessage() { return message; }
}
