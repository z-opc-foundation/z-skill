package com.zifang.z.skill.core.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.dto.SkillInstallDto;
import com.zifang.z.skill.core.registry.SkillRegistry;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill REST 入口 — 默认实现.
 *
 * <p>端点:
 * <ul>
 *   <li>{@code GET /skill/list} — 全部 Skill (可按 category 过滤)</li>
 *   <li>{@code GET /skill/installed} — 已安装列表</li>
 *   <li>{@code GET /skill/categories} — 全部分类</li>
 *   <li>{@code POST /skill/install} — 装一个 Skill</li>
 *   <li>{@code DELETE /skill/uninstall/{name}} — 卸一个 Skill</li>
 * </ul>
 *
 * <p>对应 z-opc 老 z-agent-skill-center/z-skill-web/SkillController 蒸馏.
 */
@RestController
public class SkillController {

    private final SkillRegistry registry;

    public SkillController(SkillRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/skill/list")
    public Map<String, Object> list(@RequestParam(required = false) String category) {
        List<SkillDto> skills = category == null ? registry.listAll() : registry.listByCategory(category);
        Map<String, Object> resp = new HashMap<String, Object>();
        resp.put("skills", skills);
        resp.put("total", skills.size());
        return resp;
    }

    @GetMapping("/skill/installed")
    public Map<String, Object> installed() {
        List<SkillDto> skills = registry.listInstalled();
        Map<String, Object> resp = new HashMap<String, Object>();
        resp.put("skills", skills);
        resp.put("total", skills.size());
        return resp;
    }

    @GetMapping("/skill/categories")
    public Map<String, Object> categories() {
        List<String> cats = registry.listCategories();
        Map<String, Object> resp = new HashMap<String, Object>();
        resp.put("categories", cats);
        return resp;
    }

    @PostMapping("/skill/install")
    public SkillInstallDto install(@RequestBody JsonNode body) {
        String name = body.has("name") ? body.get("name").asText() : null;
        String installedBy = body.has("installedBy") ? body.get("installedBy").asText() : "system";
        String source = body.has("source") ? body.get("source").asText() : "marketplace";
        return registry.install(name, installedBy, source);
    }

    @DeleteMapping("/skill/uninstall/{name}")
    public Map<String, Object> uninstall(@PathVariable("name") String name) {
        registry.uninstall(name);
        Map<String, Object> resp = new HashMap<String, Object>();
        resp.put("uninstalled", name);
        return resp;
    }
}