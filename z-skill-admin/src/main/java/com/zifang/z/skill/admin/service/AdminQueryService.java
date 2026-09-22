package com.zifang.z.skill.admin.service;

import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.core.registry.SkillRegistry;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * z-skill-admin 查询服务.
 */
@Service
public class AdminQueryService {

    private final SkillRegistry registry;

    public AdminQueryService(SkillRegistry registry) {
        this.registry = registry;
    }

    public Map<String, Object> overview() {
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("skillCount", registry.skillCount());
        data.put("installedCount", registry.installedCount());
        data.put("categoryCount", registry.listCategories().size());
        data.put("platform", "z-skill");
        data.put("version", "0.1.0");
        return data;
    }

    public List<SkillDto> listSkills(String category) {
        return category == null ? registry.listAll() : registry.listByCategory(category);
    }

    public List<SkillDto> listInstalled() {
        return registry.listInstalled();
    }

    public List<String> listCategories() {
        return registry.listCategories();
    }

    public Map<String, Object> health() {
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("status", "UP");
        data.put("registry", "OK");
        data.put("skills", registry.skillCount());
        return data;
    }
}