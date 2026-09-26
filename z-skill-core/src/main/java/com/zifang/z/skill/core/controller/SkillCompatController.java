package com.zifang.z.skill.core.controller;

import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.exception.SkillException;
import com.zifang.z.skill.core.content.SkillContentReader;
import com.zifang.z.skill.core.registry.SkillRegistry;
import com.zifang.z.skill.core.search.SkillSearchEngine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.HandlerMapping;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 兼容输出层 — "聚合"的另一半: 让已经长成某种形状的客户端, 不改代码就能把 base 指到 z-skill.
 *
 * <p>已收录的平台目录/注册表按它们各自的 API 形状再吐一遍:
 * <ul>
 *   <li>skills.sh: {@code /skill/compat/skills-sh/api/search}, {@code /api/v1/skills},
 *       {@code /api/v1/skills/{id}}, {@code /api/v1/skills/audit/{id}}</li>
 *   <li>Agent Skills 注册表发现: {@code /.well-known/agent-skills/index.json}</li>
 *   <li>Qoder 插件目录: {@code /skill/compat/qoder/skills}(名字带 {@code plugin:} 前缀)</li>
 * </ul>
 *
 * <p>形状照抄, 字段不臆造: 拿不到的一律给规范允许的空值, 而不是编一个.
 */
@RestController
public class SkillCompatController {

    private static final String SH_PREFIX = "/skill/compat/skills-sh";
    private static final String AUDIT_PROVIDER = "z-skill-static";

    private final SkillRegistry registry;
    private final SkillSearchEngine search;
    private final SkillContentReader contentReader;

    public SkillCompatController(SkillRegistry registry, SkillSearchEngine search, SkillContentReader contentReader) {
        this.registry = registry;
        this.search = search;
        this.contentReader = contentReader;
    }

    @GetMapping(SH_PREFIX + "/api/search")
    public Map<String, Object> shSearch(@RequestParam(value = "q", required = false) String q,
                                        @RequestParam(value = "limit", required = false, defaultValue = "50") int limit,
                                        @RequestParam(value = "owner", required = false) String owner) {
        SkillSearchEngine.Query query = new SkillSearchEngine.Query().q(q).perPage(clamp(limit, 1, 200)).sort("installs");
        query.source = owner;
        com.zifang.z.skill.api.dto.SkillSearchResultDto result = search.search(query);
        List<Map<String, Object>> skills = new ArrayList<Map<String, Object>>();
        for (SkillDto dto : result.getSkills()) {
            Map<String, Object> node = new LinkedHashMap<String, Object>();
            node.put("id", externalId(dto));
            node.put("source", dto.getSource());
            node.put("skillId", dto.getSlug());
            node.put("name", dto.getName());
            node.put("installs", dto.getInstallCount());
            skills.add(node);
        }
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("query", q == null ? "" : q);
        resp.put("searchType", result.getSearchType());
        resp.put("searchVersion", "z-skill");
        resp.put("skills", skills);
        resp.put("count", skills.size());
        resp.put("duration_ms", result.getDurationMs());
        return resp;
    }

    @GetMapping(SH_PREFIX + "/api/v1/skills")
    public Map<String, Object> shList(@RequestParam(value = "view", required = false, defaultValue = "all-time") String view,
                                      @RequestParam(value = "page", required = false, defaultValue = "0") int page,
                                      @RequestParam(value = "per_page", required = false, defaultValue = "100") int perPage) {
        String sort = "hot".equals(view) ? "updated" : "installs";
        SkillSearchEngine.Query query = new SkillSearchEngine.Query().page(Math.max(0, page))
                .perPage(clamp(perPage, 1, 200)).sort(sort);
        com.zifang.z.skill.api.dto.SkillSearchResultDto result = search.search(query);
        List<Map<String, Object>> data = new ArrayList<Map<String, Object>>();
        for (SkillDto dto : result.getSkills()) data.add(v1Skill(dto));
        Map<String, Object> pagination = new LinkedHashMap<String, Object>();
        pagination.put("page", Math.max(0, page));
        pagination.put("perPage", query.perPage);
        pagination.put("total", result.getTotal());
        pagination.put("hasMore", result.isHasMore());
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("data", data);
        resp.put("pagination", pagination);
        return resp;
    }

    /**
     * skills.sh 的外部标识本身就是 {@code anthropics/skills/pdf} 这种多段形状, 而路径变量在
     * 两种匹配器下都跨不过 {@code /}({@code {id:.+}} 只在 AntPathMatcher 的单段里生效,
     * {@code {*id}} 只有 PathPatternParser 认) — 所以直接吃 {@code /**} 的剩余路径,
     * 客户端把 base 指到老 Boot(AntPathMatcher) 装配上也能路由.
     */
    @GetMapping(SH_PREFIX + "/api/v1/skills/**")
    public Map<String, Object> shDetail(
            @RequestParam(value = "files", required = false, defaultValue = "false") boolean withFiles) {
        return shDetail(pathTail(SH_PREFIX + "/api/v1/skills/"), withFiles);
    }

    /** 详情主体: 路由与直调共用一份逻辑, 所以按 id 字符串再开一个入口. */
    public Map<String, Object> shDetail(String id, boolean withFiles) {
        SkillDto dto = resolve(id);
        Map<String, Object> node = v1Skill(dto);
        node.put("hash", dto.getContentHash());
        node.put("description", dto.getDescription());
        node.put("riskLevel", dto.getRiskLevel());
        node.put("files", shFiles(dto, withFiles));
        return node;
    }

    /**
     * skills.sh 详情里的 {@code files[]}: 走 {@link #advertisedFiles} 那份清单 — 客户端拿
     * {@code files[0]} 当正文位置, 所以第一条必须是这条 skill 自己的主文件.
     */
    private List<Map<String, Object>> shFiles(SkillDto dto, boolean withContents) {
        List<String> paths = advertisedFiles(dto);
        String mainPath = paths.get(0);
        List<Map<String, Object>> files = new ArrayList<Map<String, Object>>();
        for (String path : paths) {
            Map<String, Object> f = new LinkedHashMap<String, Object>();
            f.put("path", path);
            if (withContents && path.equals(mainPath)) {
                f.put("contents", contentReader.readMain(dto).text);
            }
            files.add(f);
        }
        return files;
    }

    @GetMapping(SH_PREFIX + "/api/v1/skills/audit/**")
    public Map<String, Object> shAudit() {
        return shAudit(pathTail(SH_PREFIX + "/api/v1/skills/audit/"));
    }

    public Map<String, Object> shAudit(String id) {
        SkillDto dto = resolve(id);
        Map<String, Object> audit = new LinkedHashMap<String, Object>();
        audit.put("provider", AUDIT_PROVIDER);
        // 审计条目里的 slug 是"被审的那条 skill", 不是扫描器自己的标识
        audit.put("slug", dto.getSlug());
        audit.put("status", auditStatus(dto.getRiskLevel()));
        audit.put("summary", dto.getIssues().isEmpty() ? "未发现高风险写法"
                : String.join("; ", dto.getIssues().subList(0, Math.min(3, dto.getIssues().size()))));
        audit.put("auditedAt", java.time.Instant.ofEpochMilli(dto.getDiscoveredAt()).toString());
        String externalRisk = externalRisk(dto.getRiskLevel());
        if (externalRisk != null) audit.put("riskLevel", externalRisk);
        List<String> categories = riskCategories(dto.getIssues());
        if (!categories.isEmpty()) audit.put("categories", categories);
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("id", externalId(dto));
        resp.put("source", dto.getSource());
        resp.put("slug", dto.getSlug());
        resp.put("audits", Arrays.asList(audit));
        return resp;
    }

    /**
     * Agent Skills 注册表发现接口: 任何客户端拿这一个 URL 就能把我们当成一个 skill 平台来装.
     */
    @GetMapping({"/.well-known/agent-skills/index.json", "/.well-known/skills/index.json",
            SH_PREFIX + "/.well-known/agent-skills/index.json"})
    public Map<String, Object> wellKnownIndex() {
        List<Map<String, Object>> skills = new ArrayList<Map<String, Object>>();
        List<SkillDto> all = registry.listAll();
        for (SkillDto dto : all) {
            Map<String, Object> node = new LinkedHashMap<String, Object>();
            node.put("name", dto.getSlug());
            node.put("description", dto.getDescription());
            node.put("files", advertisedFiles(dto));
            if (dto.getVersion() != null && !dto.getVersion().isEmpty()) {
                Map<String, String> metadata = new LinkedHashMap<String, String>();
                metadata.put("version", dto.getVersion());
                metadata.put("source", String.valueOf(dto.getSource()));
                node.put("metadata", metadata);
            }
            skills.add(node);
        }
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("skills", skills);
        resp.put("total", skills.size());
        resp.put("generatedAt", System.currentTimeMillis());
        return resp;
    }

    @GetMapping("/skill/compat/qoder/skills")
    public List<Map<String, Object>> qoderSkills() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (SkillDto dto : registry.listAll()) {
            Map<String, Object> node = new LinkedHashMap<String, Object>();
            node.put("name", qoderName(dto));
            node.put("description", dto.getDescription());
            node.put("location", dto.getSkillFilePath());
            node.put("source", dto.getSource());
            out.add(node);
        }
        return out;
    }

    private Map<String, Object> v1Skill(SkillDto dto) {
        Map<String, Object> node = new LinkedHashMap<String, Object>();
        node.put("id", externalId(dto));
        node.put("slug", dto.getSlug());
        node.put("name", dto.getName());
        node.put("source", dto.getSource());
        node.put("installs", dto.getInstallCount());
        node.put("sourceType", dto.getSourceType());
        // 本机目录来源不能把 file: 路径当安装地址发出去; 按 skills.sh 自己的口径回落到条目 id
        // (见 SkillsShApiNormalizer: 上游没给 installUrl/url 时就用 id), 形状不塌, 地址不外泄.
        String installUrl = PublicSurface.stripIfLocal(dto.getOrigin());
        if (installUrl == null) installUrl = externalId(dto);
        node.put("installUrl", installUrl);
        node.put("url", installUrl);
        return node;
    }

    /**
     * 索引条目对外 advertised 的文件清单: 第一条必须是这条 skill 自己的主文件, 而不是
     * 承载它的索引文档或上游 API 的 URL —— 客户端拿 {@code files[0]} 当正文位置.
     */
    static List<String> advertisedFiles(SkillDto dto) {
        java.util.LinkedHashSet<String> files = new java.util.LinkedHashSet<String>();
        String own = null;
        String main = shortPath(dto);
        if (isSkillMdPath(main)) own = main;
        if (own == null) {
            for (com.zifang.z.skill.api.dto.SkillResourceDto res : dto.getResources()) {
                if (isSkillMdPath(res.getPath())) {
                    own = res.getPath();
                    break;
                }
            }
        }
        if (own == null) {
            // API 类来源没有落地文件, 只能按 Agent Skills 的规范布局给出该 skill 的主文件位
            own = dto.getSlug() + "/SKILL.md";
        }
        files.add(own);
        if (main != null && !isSkillMdPath(main)) files.add(main);
        for (com.zifang.z.skill.api.dto.SkillResourceDto res : dto.getResources()) {
            files.add(res.getPath());
        }
        return new ArrayList<String>(files);
    }

    private static boolean isSkillMdPath(String path) {
        return path != null && com.zifang.z.skill.core.spec.SkillFiles.isSkillMd(lastSegmentOf(path));
    }

    private static String lastSegmentOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    /**
     * Qoder 的插件内 skill 标识是 {@code <extensionId>:<skillName>}.
     *
     * <p>我们的 source id 各家写法不一({@code anthropics/skills}、{@code acme-registry@1.2}), 直接拼进去
     * 会得到一个带 {@code /} 的"标识" —— 那不是合法的 extensionId. 所以只留 {@code [A-Za-z0-9._-]} 并把
     * 其余压成 {@code -}.
     */
    static String qoderName(SkillDto dto) {
        String src = dto.getSource() == null ? "" : dto.getSource();
        int at = src.indexOf('@');
        String ns = src.substring(0, at < 0 ? src.length() : at).replaceAll("[^A-Za-z0-9._-]+", "-");
        while (ns.endsWith("-")) ns = ns.substring(0, ns.length() - 1);
        return (ns.isEmpty() ? "local" : ns) + ":" + dto.getSlug();
    }

    /**
     * 从 {@code /**} 路由的剩余部分取出 id, 不依赖具体路径匹配器.
     *
     * <p>取值来自 Spring MVC 在匹配阶段就写好的 {@code pathWithinHandlerMapping} 请求属性 —— 两种
     * 匹配器(AntPathMatcher / PathPatternParser)下都实测是已百分号解码的完整路径(见
     * {@code SkillCompatRoutingTest}), 所以既不用再碰 servlet API, 也不用二次 URLDecode
     * (那会把 id 里的 {@code +} 变成空格).
     *
     * <p>方法签名刻意只出现 {@link String}: core 其余控制器都不引用 servlet, 兼容层一旦引用,
     * 非 web 宿主(批处理/CLI 里只想用聚合能力)在创建这个 bean 时就 {@code Failed to introspect
     * Class} —— servlet-api 在 core 里是 {@code provided}, 不会传递到 z-skill-starter 的类路径上.
     */
    static String pathTail(String prefix) {
        return tailOf(currentPathWithinMapping(), prefix);
    }

    /** 纯字符串的一半, 单独拆出来是为了能在没有 HTTP 上下文时把"切前缀"这个动作本身测掉. */
    static String tailOf(String path, String prefix) {
        if (path == null) return "";
        int at = path.indexOf(prefix);
        return at < 0 ? "" : path.substring(at + prefix.length());
    }

    private static String currentPathWithinMapping() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) return null;
        Object path = attrs.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE,
                RequestAttributes.SCOPE_REQUEST);
        return path == null ? null : path.toString();
    }

    /** 外部形状里的 id 统一成 {@code source/slug}(与 skills.sh 的 {@code {source}/{slug}} 一致). */
    static String externalId(SkillDto dto) {
        return dto.getSource() + "/" + dto.getSlug();
    }

    /** 详情页要的是 {@code <skill>/SKILL.md} 这种可读相对路径, 不是本机绝对路径. */
    static String shortPath(SkillDto dto) {
        String p = dto.getSkillFilePath();
        if (p == null || p.isEmpty()) return "SKILL.md";
        String[] parts = p.split("/");
        if (parts.length == 1) return parts[0];
        return parts[parts.length - 2] + "/" + parts[parts.length - 1];
    }

    private SkillDto resolve(String id) {
        SkillDto dto = registry.get(id).orElse(null);
        if (dto != null) return dto;
        for (SkillDto candidate : registry.snapshot().values()) {
            if (id.equals(externalId(candidate)) || id.equals(candidate.getSlug()) || id.equals(candidate.getId())) {
                return candidate;
            }
        }
        throw SkillException.notFound(id);
    }

    /** 状态字典对齐外部审计方(实测 skills.sh 用 pass/warn); 未扫描给 pending, 这是我们的扩展值. */
    static String auditStatus(String risk) {
        if (risk == null || SkillDto.RISK_UNSCANNED.equals(risk)) return "pending";
        if (SkillDto.RISK_CRITICAL.equals(risk)) return "fail";
        if (SkillDto.RISK_HIGH.equals(risk) || SkillDto.RISK_MEDIUM.equals(risk)) return "warn";
        return "pass";
    }

    /** 外部形状用大写档位(SAFE/LOW/MEDIUM/...); 没扫过就干脆不给这个字段, 而不是编一个等级. */
    static String externalRisk(String risk) {
        if (risk == null || SkillDto.RISK_UNSCANNED.equals(risk)) return null;
        return risk.toUpperCase(java.util.Locale.ROOT);
    }

    /** 扫描结论形如 {@code critical:pipe-to-shell -> rm ...}, 取规则号做成 categories 的大写蛇形标识. */
    static List<String> riskCategories(List<String> findings) {
        List<String> out = new ArrayList<String>();
        if (findings == null) return out;
        for (String finding : findings) {
            if (finding == null) continue;
            int colon = finding.indexOf(':');
            int arrow = finding.indexOf(" -> ");
            String rule = colon < 0 ? finding
                    : (arrow < 0 ? finding.substring(colon + 1) : finding.substring(colon + 1, arrow));
            rule = rule.trim().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
            if (rule.length() > 0 && !out.contains(rule)) out.add(rule);
        }
        return out;
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : Math.min(v, max);
    }

}
