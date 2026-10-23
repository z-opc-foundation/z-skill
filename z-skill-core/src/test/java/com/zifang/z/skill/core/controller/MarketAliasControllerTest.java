package com.zifang.z.skill.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.spec.SkillFormat;
import com.zifang.z.skill.core.aggregate.SkillAggregator;
import com.zifang.z.skill.core.content.SkillContentReader;
import com.zifang.z.skill.core.discover.SkillSource;
import com.zifang.z.skill.core.registry.SkillRegistry;
import com.zifang.z.skill.core.search.SkillSearchEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /api/skill/*} 别名面的形状测试 —— 2026-10-22 智能板块技能市场改造。
 *
 * <p>别名面存在的唯一理由是 fleet 惯例: 前端 axios baseURL=/api, 后端统一 {@code Result}
 * 信封 {@code {code, data, message, success}}, 前端 {@code createRequest} 只在
 * <b>{@code code === 200}</b> 时才解包 {@code data} —— 别的值一律当失败 reject。
 *
 * <p>所以这里最要紧的一条断言是 {@code code == 200}: 写成 0 / "0" / 200 之外的任何值,
 * 线上表现是「接口 200 但页面永远空」, 而且日志里看不到任何错误。
 *
 * <p>与 {@link SkillControllerTest} 同样不用 MockMvc (classpath 上没有 javax.servlet),
 * 直接调控制器方法 + Jackson 序列化 + JSONPath 断言。
 */
public class MarketAliasControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private SkillRegistry registry;
    private MarketAliasController alias;

    @BeforeEach
    public void setUp() {
        registry = new SkillRegistry();
        registry.register(pdf());
        registry.register(weather());
        SkillSearchEngine search = new SkillSearchEngine(registry);
        SkillContentReader reader = new SkillContentReader();
        SkillAggregator aggregator = new SkillAggregator(registry);
        aggregator.addSource(new SkillSource("mkt-local", SkillFormat.AGENT_SKILLS, marketplaceRoot(), 10));
        alias = new MarketAliasController(new SkillController(registry, search, aggregator, reader));
    }

    @Test
    public void envelope_codeMustBe200() {
        Map<String, Object> body = alias.search(null, null, null, null, null, null, null, "relevance", 0, 20);
        // 前端 unwrapResponse 的判据就是这一个数：不是 200 就整包 reject，页面表现为"空"
        assertEquals(200, ((Number) body.get("code")).intValue());
        assertEquals(Boolean.TRUE, body.get("success"));
        assertTrue(body.containsKey("data"));
        assertTrue(body.containsKey("message"));
    }

    @Test
    public void search_returnsSkillsUnderData() {
        Resp resp = new Resp(alias.search("pdf", null, null, null, null, null, null, "relevance", 0, 20));
        assertEquals("pdf", resp.str("$.data.query"));
        assertEquals(1, resp.num("$.data.total"));
        assertEquals("pdf-processing", resp.str("$.data.skills[0].id"));
    }

    @Test
    public void categories_sources_stats_installed_allWrapped() {
        Resp cats = new Resp(alias.categories());
        assertTrue(cats.map("$.data.categories").containsKey("documents"));
        assertTrue(cats.map("$.data.tags").containsKey("pdf"));

        // 手搓进注册中心的技能没有来源行 —— 来源是"聚合扫出来的"，只有 refresh 之后才有
        Resp sources = new Resp(alias.sources());
        assertTrue(sources.list("$.data").isEmpty());

        Resp stats = new Resp(alias.stats());
        assertEquals(2, stats.num("$.data.skillCount"));

        Resp installed = new Resp(alias.installed());
        assertTrue(installed.map("$.data").containsKey("skills"));
    }

    @Test
    public void refresh_populatesSources() {
        // refresh 会以来源为准换掉整张目录：扫完只剩 fixture 里的那一条
        alias.refresh();
        Resp sources = new Resp(alias.sources());
        assertEquals(1, sources.list("$.data").size());
        assertEquals("mkt-local", sources.str("$.data[0].id"));
    }

    @Test
    public void detailAndContent_areWrapped() {
        Resp detail = new Resp(alias.detail("pdf-processing"));
        assertEquals("pdf-processing", detail.str("$.data.skill.id"));
        assertTrue(detail.list("$.data.files").size() > 0);

        // 远端来源的 skill 没落地到本机，正文读不到 —— 但"读不到"也必须是一个**说清楚了原因**的包络
        Resp content = new Resp(alias.content("pdf-processing", null));
        if (content.flag("$.data.available")) {
            assertTrue(content.str("$.data.path") != null);
        } else {
            assertTrue(content.str("$.data.reason") != null);
        }
    }

    @Test
    public void installAndUninstall_goThroughAlias() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("id", "pdf-processing");
        Resp installed = new Resp(alias.install(body));
        assertEquals("pdf-processing", installed.str("$.data.skillId"));

        Resp afterInstall = new Resp(alias.installed());
        assertEquals(1, afterInstall.num("$.data.total"));

        // 查询串形态的卸载: id 带斜杠时路径形态会被切段，前端走的是查询串
        Resp removed = new Resp(alias.uninstallById("pdf-processing"));
        assertEquals("pdf-processing", removed.str("$.data.uninstalled"));

        Resp afterRemove = new Resp(alias.installed());
        assertEquals(0, afterRemove.num("$.data.total"));
    }

    @Test
    public void installRecord_doesNotLeakHostPath() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("id", "pdf-processing");
        Resp installed = new Resp(alias.install(body));
        Object ref = installed.raw("$.data.installRef");
        // 本机来源的 installRef 是 file: 路径 —— 公共面必须抹掉（PublicSurface 的约定）
        assertTrue(ref == null || !String.valueOf(ref).startsWith("file:"),
                "installRef 泄露了本机路径: " + ref);
    }

    private static SkillDto pdf() {
        return SkillDto.builder()
                .id("pdf-processing").slug("pdf-processing").name("pdf-processing")
                .description("Extract text from PDF files").version("1.0.0")
                .category("documents").tags(Arrays.asList("pdf"))
                .author("anthropic").source("anthropic").sourceType("local")
                .origin("file:///nonexistent/mkt/pdf-processing/SKILL.md")
                .skillFilePath("pdf-processing/SKILL.md")
                .format(SkillFormat.AGENT_SKILLS)
                .contentHash("hash-pdf").installCount(12000).riskLevel(SkillDto.RISK_LOW)
                .discoveredAt(1000L).updatedAt(500L)
                .metadata(Collections.singletonMap("compatibility", "claude"))
                .build();
    }

    private static SkillDto weather() {
        return SkillDto.builder()
                .id("acme-registry/weather-lookup").slug("weather-lookup").name("weather-lookup")
                .description("Look up current weather and forecasts").version("1.0.0")
                .category("weather").tags(Arrays.asList("http"))
                .author("acme").source("acme-registry").sourceType("well-known")
                .origin("https://acme.example/.well-known/agent-skills/weather-lookup/SKILL.md")
                .skillFilePath(".well-known/agent-skills/index.json")
                .format(SkillFormat.REGISTRY_INDEX)
                .contentHash("hash-weather").installCount(4210).riskLevel(SkillDto.RISK_UNSCANNED)
                .discoveredAt(1200L).updatedAt(600L)
                .build();
    }

    private static String marketplaceRoot() {
        return localSkillMd().getParent().getParent().toString();
    }

    private static Path localSkillMd() {
        try {
            return Paths.get(MarketAliasControllerTest.class.getResource(
                    "/fixtures/A4/marketplace/pdf-processing/SKILL.md").toURI());
        } catch (Exception e) {
            throw new IllegalStateException("A4 marketplace fixture missing", e);
        }
    }

    /** 控制器返回值 -> JSON 文档; 断言按 JSONPath 打在键名上. */
    private final class Resp {
        private final DocumentContext dc;

        Resp(Object controllerReturn) {
            try {
                dc = JsonPath.parse(mapper.writeValueAsString(controllerReturn));
            } catch (Exception e) {
                throw new IllegalStateException("控制器返回值无法序列化成 JSON", e);
            }
        }

        String str(String path) {
            Object v = dc.read(path);
            assertTrue(v instanceof String, path + " 必须是字符串, 实际是 " + v);
            return (String) v;
        }

        Object raw(String path) {
            return dc.read(path);
        }

        int num(String path) {
            Object v = dc.read(path);
            assertTrue(v instanceof Number, path + " 必须是数字, 实际是 " + v);
            return ((Number) v).intValue();
        }

        boolean flag(String path) {
            Object v = dc.read(path);
            assertTrue(v instanceof Boolean, path + " 必须是布尔, 实际是 " + v);
            return (Boolean) v;
        }

        java.util.List<Object> list(String path) {
            Object v = dc.read(path);
            assertTrue(v instanceof java.util.List, path + " 必须是数组, 实际是 " + v);
            return (java.util.List<Object>) v;
        }

        Map<String, Object> map(String path) {
            Object v = dc.read(path);
            assertTrue(v instanceof Map, path + " 必须是对象, 实际是 " + v);
            return (Map<String, Object>) v;
        }
    }
}
