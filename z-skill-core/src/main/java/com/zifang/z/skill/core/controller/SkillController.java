package com.zifang.z.skill.core.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.zifang.z.skill.api.dto.AggregateReportDto;
import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.dto.SkillInstallDto;
import com.zifang.z.skill.api.dto.SkillSearchResultDto;
import com.zifang.z.skill.api.dto.SkillSourceDto;
import com.zifang.z.skill.api.exception.SkillException;
import com.zifang.z.skill.core.aggregate.SkillAggregator;
import com.zifang.z.skill.core.content.SkillContentReader;
import com.zifang.z.skill.core.registry.SkillRegistry;
import com.zifang.z.skill.core.search.SkillSearchEngine;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill Marketplace REST — 面向 z-opc 前端/自家客户端的那套接口.
 *
 * <p>端点:
 * <ul>
 *   <li>{@code GET /skill/search} — 关键词检索 + 分类/标签/来源/格式/风险分面 + 分页排序</li>
 *   <li>{@code GET /skill/list} / {@code /skill/installed} — 目录与"我的安装"</li>
 *   <li>{@code GET /skill/categories|tags|sources|stats|updates} — 目录侧信息</li>
 *   <li>{@code GET /skill/{id}} 与 {@code GET /skill/detail?id=} — 详情(后者支持带 / 的限定 id)</li>
 *   <li>{@code GET /skill/{id}/content} — SKILL.md 正文与随包文件预览</li>
 *   <li>{@code POST /skill/install} / {@code DELETE /skill/uninstall/{id}} / {@code POST /skill/refresh}</li>
 * </ul>
 */
@RestController
@RequestMapping("/skill")
public class SkillController {

    private final SkillRegistry registry;
    private final SkillSearchEngine search;
    private final SkillAggregator aggregator;
    private final SkillContentReader contentReader;

    public SkillController(SkillRegistry registry, SkillSearchEngine search, SkillAggregator aggregator,
                           SkillContentReader contentReader) {
        this.registry = registry;
        this.search = search;
        this.aggregator = aggregator;
        this.contentReader = contentReader;
    }

    @GetMapping("/search")
    public SkillSearchResultDto search(@RequestParam(required = false) String q,
                                       @RequestParam(required = false) String category,
                                       @RequestParam(required = false) String tag,
                                       @RequestParam(required = false) String source,
                                       @RequestParam(required = false) String format,
                                       @RequestParam(required = false) String risk,
                                       @RequestParam(required = false) Boolean installed,
                                       @RequestParam(required = false, defaultValue = "relevance") String sort,
                                       @RequestParam(required = false, defaultValue = "0") int page,
                                       @RequestParam(required = false, defaultValue = "20") int perPage) {
        SkillSearchEngine.Query query = new SkillSearchEngine.Query()
                .q(q).category(category).tag(tag).source(source).format(format).sort(sort).page(page).perPage(perPage);
        query.riskLevel = risk;
        query.installedOnly = installed;
        return PublicSurface.search(search.search(query));
    }

    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam(required = false) String category,
                                    @RequestParam(required = false) String tag,
                                    @RequestParam(required = false) String source) {
        SkillSearchEngine.Query query = new SkillSearchEngine.Query();
        query.category = category;
        query.tag = tag;
        query.source = source;
        query.sort = "name";
        List<SkillDto> skills = PublicSurface.skills(search.filter(query));
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("skills", skills);
        resp.put("total", skills.size());
        return resp;
    }

    @GetMapping("/installed")
    public Map<String, Object> installed() {
        List<SkillDto> skills = PublicSurface.skills(registry.installedSkills());
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("skills", skills);
        resp.put("total", skills.size());
        resp.put("records", PublicSurface.installs(registry.installRecords()));
        resp.put("dangling", registry.danglingInstalls());
        return resp;
    }

    @GetMapping("/categories")
    public Map<String, Object> categories() {
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("categories", search.categoryCounts());
        resp.put("tags", search.tagCounts());
        return resp;
    }

    @GetMapping("/sources")
    public List<SkillSourceDto> sources() {
        return PublicSurface.sources(registry.getSources());
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        AggregateReportDto report = registry.getLastReport();
        Map<String, Integer> riskDist = new LinkedHashMap<String, Integer>();
        Map<String, Integer> sourceDist = new LinkedHashMap<String, Integer>();
        for (SkillDto dto : registry.snapshot().values()) {
            bump(riskDist, dto.getRiskLevel());
            bump(sourceDist, dto.getSource());
        }
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("platform", "z-skill");
        resp.put("skillCount", registry.skillCount());
        resp.put("installedCount", registry.installedCount());
        resp.put("categoryCount", search.listCategories().size());
        resp.put("sourceCount", registry.getSources().size());
        resp.put("riskDistribution", riskDist);
        resp.put("sourceDistribution", sourceDist);
        resp.put("recentlyAdded", registry.getAdded());
        resp.put("upstreamChanged", registry.getChanged());
        resp.put("upstreamRemoved", registry.getRemoved());
        resp.put("danglingInstalls", registry.danglingInstalls());
        if (report != null) {
            resp.put("lastAggregate", PublicSurface.report(report));
        }
        return resp;
    }

    /** 上游漂移: 同名 skill 内容 hash 变了的条目. */
    @GetMapping("/updates")
    public Map<String, Object> updates() {
        List<SkillDto> changed = new ArrayList<SkillDto>();
        for (String id : registry.getChanged()) {
            registry.get(id).ifPresent(changed::add);
        }
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("changed", PublicSurface.skills(changed));
        resp.put("added", registry.getAdded());
        resp.put("removed", registry.getRemoved());
        return resp;
    }

    @GetMapping("/detail")
    public Map<String, Object> detailById(@RequestParam("id") String id) {
        return detail(id);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable("id") String id) {
        SkillDto dto = registry.require(id);
        SkillContentReader.Content content = contentReader.readMain(dto);
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        // 沙箱读取要真路径, 所以先按全量 dto 取内容, 再把 dto 里的本机来源地址抹掉发出去
        resp.put("skill", PublicSurface.skill(dto));
        resp.put("files", contentReader.fileTree(dto));
        resp.put("contentAvailable", content.available);
        resp.put("frontmatter", dto.getMetadata());
        if (!content.available) resp.put("contentReason", content.reason);
        registry.getInstallRecord(dto.getId())
                .ifPresent(rec -> resp.put("install", PublicSurface.install(rec)));
        return resp;
    }

    /**
     * 正文预览的查询串形. 与上面 {@code /skill/detail} 同一个理由: 聚合来的 id 长成 source/slug,
     * 而 Tomcat 默认拒收路径段里的 %2F(实测 400, 到不了 Spring), 不编码又会被切成多段(404),
     * 所以路径变量那条形同虚设 —— 内容预览是详情页的主功能, 不能只对一半的条目可用.
     */
    @GetMapping("/content")
    public Map<String, Object> contentById(@RequestParam("id") String id,
                                          @RequestParam(value = "file", required = false) String file) {
        return content(id, file);
    }

    @GetMapping("/{id}/content")
    public Map<String, Object> content(@PathVariable("id") String id,
                                      @RequestParam(value = "file", required = false) String file) {
        SkillDto dto = registry.require(id);
        SkillContentReader.Content c = file == null ? contentReader.readMain(dto) : contentReader.readResource(dto, file);
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("id", dto.getId());
        resp.put("path", c.path);
        resp.put("available", c.available);
        resp.put("content", c.text);
        if (c.reason != null) resp.put("reason", c.reason);
        return resp;
    }

    @PostMapping("/install")
    public SkillInstallDto install(@RequestBody(required = false) JsonNode body) {
        if (body == null || !body.hasNonNull("id") && !body.hasNonNull("name")) {
            throw SkillException.badRequest("需要 id 或 name 字段");
        }
        String id = body.hasNonNull("id") ? body.get("id").asText() : body.get("name").asText();
        String installedBy = body.hasNonNull("installedBy") ? body.get("installedBy").asText() : "system";
        String channel = body.hasNonNull("source") ? body.get("source").asText()
                : (body.hasNonNull("channel") ? body.get("channel").asText() : "marketplace");
        // 安装记录原样回显 = 把 origin 那条 file:///Users/... 发给没鉴权的端口.
        // 实测(2026-09-25): 公共面每个出口都过了 PublicSurface, 只有这一个"写出口"漏了 ——
        // 它不在 GET 清单里, 也不在页面 fetch 的路径清单里.
        return PublicSurface.install(registry.install(id, installedBy, channel));
    }

    @DeleteMapping("/uninstall/{id}")
    public Map<String, Object> uninstall(@PathVariable("id") String id) {
        SkillInstallDto removed = registry.uninstall(id);
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("uninstalled", removed.getSkillId());
        return resp;
    }

    @DeleteMapping("/uninstall")
    public Map<String, Object> uninstallById(@RequestParam("id") String id) {
        return uninstall(id);
    }

    /** 让聚合器重扫全部来源(接了新平台或上游发了新版时用). */
    @PostMapping("/refresh")
    public AggregateReportDto refresh() {
        return PublicSurface.report(aggregator.refresh());
    }

    private static void bump(Map<String, Integer> map, String key) {
        if (key == null) return;
        Integer n = map.get(key);
        map.put(key, n == null ? 1 : n + 1);
    }
}
