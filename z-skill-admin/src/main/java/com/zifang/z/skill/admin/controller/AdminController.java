package com.zifang.z.skill.admin.controller;

import com.zifang.z.skill.admin.service.AdminQueryService;
import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.dto.SkillIssueDto;
import com.zifang.z.skill.api.dto.SkillSourceDto;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * z-skill-admin 控制面 REST (挂在 {@code /skill/admin/*}, 需要 {@code z.skill.expose-admin=true} 才开).
 *
 * <p>默认关: 聚合结果里含第三方仓库的路径与描述, 不该在没鉴权的端口上裸露.
 */
@RestController
@RequestMapping("/skill/admin")
@ConditionalOnProperty(name = "z.skill.expose-admin", havingValue = "true")
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

    @GetMapping("/sources")
    public List<SkillSourceDto> sources() {
        return service.listSources();
    }

    @GetMapping("/issues")
    public List<SkillIssueDto> issues(@RequestParam(required = false) String severity) {
        return service.listIssues(severity);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return service.health();
    }
}
