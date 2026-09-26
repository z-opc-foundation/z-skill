package com.zifang.z.skill.api.dto;

import java.util.List;
import java.util.Map;

/**
 * 一次聚合的完整体检报告 — "从哪些平台收了多少、丢了多少、为什么丢".
 *
 * <p>四个计数是四个不同环节的口径, 别混用:
 * <pre>
 * 候选(rawCount) --适配器--> 产出(producedCount) --去重--> 目录(skillCount)
 *        \--丢弃(droppedCount)--/
 * </pre>
 * 两条恒等式由 {@code SkillAggregator} 的计数方式保证, 不依赖某个语料的形状:
 * <ul>
 *   <li>{@code rawCount == 有产出的候选数 + droppedCount}</li>
 *   <li>{@code producedCount == skillCount + dedupedCount}</li>
 * </ul>
 * 一份索引能产多条、一条产出可能因跨来源同 hash 被折叠, 所以 rawCount 与 producedCount 之间没有
 * 普适等式 — 只有上面两条.
 */
public final class AggregateReportDto {

    private final long durationMs;
    private final int sourceCount;
    private final int skillCount;
    private final int rawCount;
    private final int producedCount;
    private final int droppedCount;
    private final int dedupedCount;
    private final int conflictCount;
    private final int issueCount;
    private final List<SkillSourceDto> sources;
    private final List<SkillIssueDto> issues;
    private final Map<String, Integer> formatDistribution;
    private final Map<String, Integer> severityDistribution;

    public AggregateReportDto(long durationMs, int sourceCount, int skillCount, int rawCount, int producedCount,
                              int droppedCount, int dedupedCount, int conflictCount, List<SkillSourceDto> sources,
                              List<SkillIssueDto> issues, Map<String, Integer> formatDistribution,
                              Map<String, Integer> severityDistribution) {
        this.durationMs = durationMs;
        this.sourceCount = sourceCount;
        this.skillCount = skillCount;
        this.rawCount = rawCount;
        this.producedCount = producedCount;
        this.droppedCount = droppedCount;
        this.dedupedCount = dedupedCount;
        this.conflictCount = conflictCount;
        this.sources = sources;
        this.issues = issues;
        this.formatDistribution = formatDistribution;
        this.severityDistribution = severityDistribution;
        this.issueCount = issues == null ? 0 : issues.size();
    }

    public long getDurationMs() { return durationMs; }
    public int getSourceCount() { return sourceCount; }
    public int getSkillCount() { return skillCount; }
    /** 发现层交给适配器的候选条目数(一个文件 / 一份远端响应 = 一个候选). */
    public int getRawCount() { return rawCount; }
    /** 适配器真正吐出来的条数(未去重): 一份索引可以产多条, 一个候选也可以产 0 条. */
    public int getProducedCount() { return producedCount; }
    /** 候选里"一条都没产出来"的那些 — 与 issues 里 error 级的口径对齐. */
    public int getDroppedCount() { return droppedCount; }
    public int getDedupedCount() { return dedupedCount; }
    public int getConflictCount() { return conflictCount; }
    public List<SkillSourceDto> getSources() { return sources; }
    public List<SkillIssueDto> getIssues() { return issues; }
    public Map<String, Integer> getFormatDistribution() { return formatDistribution; }
    public Map<String, Integer> getSeverityDistribution() { return severityDistribution; }
    public int getIssueCount() { return issueCount; }
}
