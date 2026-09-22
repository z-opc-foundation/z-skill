package com.zifang.z.skill.admin.controller;

import com.zifang.z.skill.admin.service.AdminQueryService;
import com.zifang.z.skill.api.dto.SkillDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * z-skill-admin 控制面 REST 端点 (挂载在 /skill/admin/*, 通过 z.skill.expose-admin=true 开启).
 */
@RestController
@RequestMapping("/skill/admin")
public class AdminController {

    private final AdminQueryService service;

    public AdminController(AdminQueryService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return service.overview();
    }

    @GetMapping("/skills")
    public List<SkillDto> skills(@RequestParam(required = false) String category) {
        return service.listSkills(category);
    }

    @GetMapping("/installed")
    public List<SkillDto> installed() {
        return service.listInstalled();
    }

    @GetMapping("/categories")
    public List<String> categories() {
        return service.listCategories();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return service.health();
    }
}