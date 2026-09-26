package com.zifang.z.skill.api.dto;

import java.util.List;
import java.util.Map;

/**
 * 搜索结果: 命中的 Skill + 分面(facet) + 打分口径, 对齐 skills.sh 的 search 响应字段命名.
 */
public final class SkillSearchResultDto {

    private final List<SkillDto> skills;
    private final String query;
    private final String searchType;
    private final int total;
    private final int count;
    private final int page;
    private final int perPage;
    private final boolean hasMore;
    private final long durationMs;
    private final Map<String, Integer> facets;
    private final Map<String, Map<String, Integer>> allFacets;

    public SkillSearchResultDto(List<SkillDto> skills, String query, String searchType, int total,
                                int page, int perPage, long durationMs,
                                Map<String, Integer> categoryFacets,
                                Map<String, Map<String, Integer>> allFacets) {
        this.skills = skills;
        this.query = query;
        this.searchType = searchType;
        this.total = total;
        this.count = skills == null ? 0 : skills.size();
        this.page = page;
        this.perPage = perPage;
        this.hasMore = (long) (page + 1) * perPage < total;
        this.durationMs = durationMs;
        this.facets = categoryFacets;
        this.allFacets = allFacets;
    }

    public List<SkillDto> getSkills() { return skills; }
    public String getQuery() { return query; }
    public String getSearchType() { return searchType; }
    public int getTotal() { return total; }
    public int getCount() { return count; }
    public int getPage() { return page; }
    public int getPerPage() { return perPage; }
    public boolean isHasMore() { return hasMore; }
    public long getDurationMs() { return durationMs; }
    /** 兼容老消费方: 历史上只暴露 category 一组分面. */
    public Map<String, Integer> getFacets() { return facets; }
    public Map<String, Map<String, Integer>> getAllFacets() { return allFacets; }
}
