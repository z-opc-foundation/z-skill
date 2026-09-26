package com.zifang.z.skill.admin.service;

import com.zifang.z.skill.api.dto.AggregateReportDto;
import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.dto.SkillIssueDto;
import com.zifang.z.skill.api.dto.SkillSourceDto;
import com.zifang.z.skill.core.registry.SkillRegistry;
import com.zifang.z.skill.core.search.SkillSearchEngine;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 控制面查询: 平台视角的"聚合了谁、收了多少、哪里脏了".
 */
@Service
public class AdminQueryService {

    private final SkillRegistry registry;
    private final SkillSearchEngine search;

    public AdminQueryService(SkillRegistry registry, SkillSearchEngine search) {
        this.registry = registry;
        this.search = search;
    }

    public Map<String, Object> overview() {
        Map<String, Integer> risk = new LinkedHashMap<String, Integer>();
        Map<String, Integer> formats = new LinkedHashMap<String, Integer>();
        for (SkillDto dto : registry.snapshot().values()) {
            bump(risk, dto.getRiskLevel());
            bump(formats, dto.getFormatId());
        }
        AggregateReportDto report = registry.getLastReport();
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("platform", "z-skill");
        data.put("role", "skill-platform-aggregator");
        data.put("skillCount", registry.skillCount());
        data.put("installedCount", registry.installedCount());
        data.put("categoryCount", search.listCategories().size());
        data.put("sourceCount", registry.getSources().size());
        data.put("riskDistribution", risk);
        data.put("formatDistribution", formats);
        data.put("rawCandidates", report == null ? 0 : report.getRawCount());
        data.put("deduped", report == null ? 0 : report.getDedupedCount());
        data.put("conflicts", report == null ? 0 : report.getConflictCount());
        data.put("issues", report == null ? 0 : report.getIssueCount());
        data.put("lastDurationMs", report == null ? 0 : report.getDurationMs());
        data.put("lastRefreshAt", report == null ? 0L : latestRefreshAt());
        // 上游漂移: 控制台要能一眼看出"这次比上次多/少了谁", 否则聚合器变成了黑盒
        data.put("recentAdded", registry.getAdded().size());
        data.put("recentChanged", registry.getChanged().size());
        data.put("recentRemoved", registry.getRemoved().size());
        data.put("danglingInstalls", registry.danglingInstalls().size());
        return data;
    }

    private long latestRefreshAt() {
        long latest = 0L;
        for (SkillSourceDto source : registry.getSources()) {
            if (source.getLastRefreshAt() > latest) latest = source.getLastRefreshAt();
        }
        return latest;
    }

    public List<SkillDto> listSkills(String category) {
        if (category == null) return registry.listAll();
        SkillSearchEngine.Query query = new SkillSearchEngine.Query().category(category).perPage(100000);
        return search.search(query).getSkills();
    }

    public List<SkillDto> listInstalled() {
        return registry.installedSkills();
    }

    public List<String> listCategories() {
        return search.listCategories();
    }

    public List<SkillSourceDto> listSources() {
        return registry.getSources();
    }

    public List<SkillIssueDto> listIssues(String severity) {
        AggregateReportDto report = registry.getLastReport();
        if (report == null) return new ArrayList<SkillIssueDto>();
        List<SkillIssueDto> out = new ArrayList<SkillIssueDto>();
        for (SkillIssueDto issue : report.getIssues()) {
            if (severity == null || severity.equals(issue.getSeverity())) out.add(issue);
        }
        return out;
    }

    public Map<String, Object> health() {
        int errored = 0;
        for (SkillSourceDto source : registry.getSources()) {
            if ("error".equals(source.getStatus())) errored++;
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("status", errored == 0 ? "UP" : "DEGRADED");
        data.put("sources", registry.getSources().size());
        data.put("sourcesFailed", errored);
        data.put("skills", registry.skillCount());
        return data;
    }

    private static void bump(Map<String, Integer> map, String key) {
        if (key == null) return;
        Integer n = map.get(key);
        map.put(key, n == null ? 1 : n + 1);
    }
}
