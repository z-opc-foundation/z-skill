package com.zifang.z.skill.admin.controller;

import com.zifang.z.meta.api.annotation.BizApi;
import com.zifang.z.meta.api.annotation.BizField;
import com.zifang.z.meta.api.annotation.BizParam;
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
    @BizApi(summary = "overview", description = "", tags = { "z-skill" })
    public Map<String, Object> overview() {
        return service.overview();
    }

    @GetMapping("/skills")
    @BizApi(summary = "skills", description = "", tags = { "z-skill" })
    public List<SkillDto> skills(@RequestParam(required = false) @BizParam(name = "category", type = BizField.FieldType.STRING, description = "", position = BizParam.Position.QUERY) String category) {
        return service.listSkills(category);
    }

    @GetMapping("/installed")
    @BizApi(summary = "installed", description = "", tags = { "z-skill" })
    public List<SkillDto> installed() {
        return service.listInstalled();
    }

    @GetMapping("/categories")
    @BizApi(summary = "categories", description = "", tags = { "z-skill" })
    public List<String> categories() {
        return service.listCategories();
    }

    @GetMapping("/sources")
    @BizApi(summary = "sources", description = "", tags = { "z-skill" })
    public List<SkillSourceDto> sources() {
        return service.listSources();
    }

    @GetMapping("/issues")
    @BizApi(summary = "issues", description = "", tags = { "z-skill" })
    public List<SkillIssueDto> issues(@RequestParam(required = false) @BizParam(name = "severity", type = BizField.FieldType.STRING, description = "", position = BizParam.Position.QUERY) String severity) {
        return service.listIssues(severity);
    }

    @GetMapping("/health")
    @BizApi(summary = "health", description = "", tags = { "z-skill" })
    public Map<String, Object> health() {
        return service.health();
    }
}



