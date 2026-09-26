package com.zifang.z.skill.core.controller;

import com.zifang.z.skill.api.dto.AggregateReportDto;
import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.dto.SkillInstallDto;
import com.zifang.z.skill.api.dto.SkillIssueDto;
import com.zifang.z.skill.api.dto.SkillSearchResultDto;
import com.zifang.z.skill.api.dto.SkillSourceDto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 公共面({@code /skill/*})与控制面({@code /skill/admin/*})的分界线: 宿主机目录结构只走控制面.
 *
 * <p>聚合器必须记住每条 skill 是从磁盘哪里来的(内容读取、上游漂移比对都靠它), 但那是宿主机的布局 —
 * {@code /Users/<name>/<项目>/.claude/skills} 对客户端没用, 对摸了这个端口的人有用.
 * 而 {@code z.skill.enabled=true} 一个开关就把 {@code /skill/*} 全开起来了, 所以"本机绝对路径默认
 * 不裸露"这条承诺不能只靠控制面那道门: 公共面的每个出口都要过这里.
 *
 * <p>口径要窄: 只抹<strong>本机路径</strong>({@code file:} URI、绝对路径). 远端来源的 origin 常常
 * 不是 URL 而是 {@code owner/repo@skill} 这类标识, 那是安装要用的真信息, 留着; 相对路径
 * ({@code skillFilePath}、资源路径、记账 path) 是市场页要展示的"这个 skill 里有什么", 也留着.
 */
public final class PublicSurface {

    private PublicSurface() {}

    /** {@code file:} URI、POSIX 绝对路径、UNC、Windows 盘符 —— 四种写法都是宿主机布局. */
    public static boolean isLocalPath(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("file:")) return true;
        if (value.startsWith("/") || value.startsWith("\\\\")) return true;
        return value.length() > 2 && value.charAt(1) == ':' && value.charAt(2) == '\\'
                && Character.isLetter(value.charAt(0));
    }

    /** 本机路径给 null, 其余原样. */
    public static String stripIfLocal(String value) {
        return isLocalPath(value) ? null : value;
    }

    public static SkillDto skill(SkillDto dto) {
        if (dto == null) return null;
        String kept = stripIfLocal(dto.getOrigin());
        return Objects.equals(kept, dto.getOrigin()) ? dto : dto.toBuilder().origin(kept).build();
    }

    public static List<SkillDto> skills(List<SkillDto> dtos) {
        if (dtos == null || dtos.isEmpty()) return Collections.emptyList();
        List<SkillDto> out = new ArrayList<SkillDto>(dtos.size());
        for (SkillDto d : dtos) out.add(skill(d));
        return out;
    }

    public static SkillSourceDto source(SkillSourceDto s) {
        if (s == null) return null;
        String kept = stripIfLocal(s.getOrigin());
        if (Objects.equals(kept, s.getOrigin())) return s;
        return new SkillSourceDto(s.getId(), s.getFormat(), s.getObservedFormats(), kept, s.getPriority(),
                s.isEnabled(), s.getStatus(), s.getSkillCount(), s.getSkillIds(), s.getIssueCount(),
                s.getIssues(), s.getDurationMs(), s.getLastRefreshAt());
    }

    public static List<SkillSourceDto> sources(List<SkillSourceDto> list) {
        if (list == null || list.isEmpty()) return Collections.emptyList();
        List<SkillSourceDto> out = new ArrayList<SkillSourceDto>(list.size());
        for (SkillSourceDto s : list) out.add(source(s));
        return out;
    }

    /** 记账的 path 有两种: 条目内的相对路径(留), 来源根目录(如 {@code source-missing}, 抹). */
    public static SkillIssueDto issue(SkillIssueDto i) {
        if (i == null) return null;
        String kept = stripIfLocal(i.getPath());
        if (Objects.equals(kept, i.getPath())) return i;
        return new SkillIssueDto(i.getSeverity(), i.getCode(), i.getSkillId(), i.getSource(), kept, i.getMessage());
    }

    /** {@code installRef} 按约定就是"不透明引用", 但本机安装记录里它确实是 file: 路径. */
    public static SkillInstallDto install(SkillInstallDto r) {
        if (r == null) return null;
        String kept = stripIfLocal(r.getInstallRef());
        if (Objects.equals(kept, r.getInstallRef())) return r;
        return new SkillInstallDto(r.getSkillId(), r.getSkillName(), r.getVersion(), r.getInstalledBy(),
                r.getInstalledAt(), r.getSource(), r.getSourceId(), r.getContentHash(), kept);
    }

    public static List<SkillInstallDto> installs(List<SkillInstallDto> records) {
        if (records == null || records.isEmpty()) return Collections.emptyList();
        List<SkillInstallDto> out = new ArrayList<SkillInstallDto>(records.size());
        for (SkillInstallDto r : records) out.add(install(r));
        return out;
    }

    public static SkillSearchResultDto search(SkillSearchResultDto r) {
        if (r == null) return null;
        return new SkillSearchResultDto(skills(r.getSkills()), r.getQuery(), r.getSearchType(), r.getTotal(),
                r.getPage(), r.getPerPage(), r.getDurationMs(), r.getFacets(), r.getAllFacets());
    }

    public static AggregateReportDto report(AggregateReportDto r) {
        if (r == null) return null;
        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
        for (SkillIssueDto i : r.getIssues()) issues.add(issue(i));
        return new AggregateReportDto(r.getDurationMs(), r.getSourceCount(), r.getSkillCount(), r.getRawCount(),
                r.getProducedCount(), r.getDroppedCount(), r.getDedupedCount(), r.getConflictCount(),
                sources(r.getSources()), issues, r.getFormatDistribution(), r.getSeverityDistribution());
    }
}
