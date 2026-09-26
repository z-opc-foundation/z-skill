package com.zifang.z.skill.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.core.content.SkillContentReader;
import com.zifang.z.skill.core.registry.SkillRegistry;
import com.zifang.z.skill.core.search.SkillSearchEngine;
import com.zifang.z.skill.api.spec.SkillFormat;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.pattern.PathPatternParser;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 兼容层"多段 id 能不能路由到"这件事的证据 — 形状对了但 URL 打不开, 对客户端等于没有兼容层.
 *
 * <p>skills.sh 的 id 是 {@code anthropics/skills/pdf} 这种跨斜杠的形状, 而 {@code /**} 剩余路径
 * 要在 Boot 2.6 前后两套路径匹配器下都成立, 所以每一种主机形态都得实测, 而不是"我们用了 {@code /**}"就当通过.
 */
public class SkillCompatRoutingTest {

    private static final String DETAIL = "/skill/compat/skills-sh/api/v1/skills";

    private final ObjectMapper mapper = new ObjectMapper();

    private SkillRegistry registry() {
        SkillRegistry registry = new SkillRegistry();
        registry.register(SkillDto.builder()
                .id("pdf").slug("pdf").name("pdf").description("处理 PDF")
                .source("anthropics/skills").sourceType("api").format(SkillFormat.AGENT_SKILLS)
                .riskLevel(SkillDto.RISK_SAFE).contentHash("abc123").skillFilePath("pdf/SKILL.md")
                .build());
        // source id 里带 "@" 和 "." 是真实形态(acme-registry@1.2), 也是 Spring MVC 老式
        // 后缀截断最容易咬掉一段的地方
        registry.register(SkillDto.builder()
                .id("demo").slug("demo").name("demo").description("带版本号的来源")
                .source("acme@1.2").sourceType("api").format(SkillFormat.AGENT_SKILLS)
                .riskLevel(SkillDto.RISK_LOW).contentHash("def456").skillFilePath("demo/SKILL.md")
                .build());
        return registry;
    }

    private MockMvc mvc(PathPatternParser parser) {
        SkillRegistry registry = registry();
        StandaloneMockMvcBuilder builder = MockMvcBuilders.standaloneSetup(new SkillCompatController(registry,
                new SkillSearchEngine(registry), new SkillContentReader()));
        builder.setControllerAdvice(new SkillErrorAdvice());
        builder.setPatternParser(parser);
        return builder.build();
    }

    private Map<String, Object> body(MvcResult result) throws Exception {
        return mapper.readValue(result.getResponse().getContentAsString(StandardCharsets.UTF_8), Map.class);
    }

    /** 两种匹配器都要能接住 3 段 id: 详情和审计各自的前缀都要切对. */
    @Test
    void multiSegmentIds_routeUnderBothMatchers() throws Exception {
        for (String host : new String[]{"ant-path-matcher", "path-pattern-parser"}) {
            MockMvc mvc = "path-pattern-parser".equals(host)
                    ? mvc(new PathPatternParser()) : mvc(null);

            MvcResult detail = mvc.perform(MockMvcRequestBuilders.get(DETAIL + "/anthropics/skills/pdf")).andReturn();
            assertEquals(200, detail.getResponse().getStatus(), host + ": 3 段 id 的详情路由打不开");
            assertEquals("anthropics/skills/pdf", body(detail).get("id"), host);

            MvcResult audit = mvc.perform(MockMvcRequestBuilders.get(DETAIL + "/audit/anthropics/skills/pdf")).andReturn();
            assertEquals(200, audit.getResponse().getStatus(), host + ": 审计路由被详情的 /** 抢走了还是没匹配上?");
            assertEquals("anthropics/skills/pdf", body(audit).get("id"), host + ": 审计切前缀切错了");
        }
    }

    /** 版本号形状的 id 必须整段送达: 一旦被截成 {@code acme@1}, 详情就 404 了. */
    @Test
    void dottedSourceId_arrivesIntact() throws Exception {
        for (MockMvc each : new MockMvc[]{mvc(new PathPatternParser()), mvc(null)}) {
            MvcResult result = each.perform(MockMvcRequestBuilders.get(DETAIL + "/acme@1.2/demo")).andReturn();
            assertEquals(200, result.getResponse().getStatus(), "带点的 id 被截断了: "
                    + result.getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertEquals("acme@1.2/demo", body(result).get("id"));
        }
    }

    /** 前缀切不到就按"没这条 skill"处理, 而不是把半截路径当 id 去查. */
    @Test
    void tailOf_isPureAndBounded() {
        assertEquals("a/b", SkillCompatController.tailOf("/skill/compat/skills-sh/api/v1/skills/a/b",
                DETAIL + "/"));
        assertEquals("", SkillCompatController.tailOf(DETAIL + "/", DETAIL + "/"));
        assertEquals("", SkillCompatController.tailOf("/other/path", DETAIL + "/"), "前缀对不上不该返回半截路径");
        assertEquals("", SkillCompatController.tailOf(null, DETAIL + "/"));
    }

    /**
     * 证伪"用路径变量就行了": {@code {id:.+}} 的正则只在单个路径段内生效, 跨不过 {@code /},
     * 这正是兼容层必须吃 {@code /**} 的原因.
     */
    @Test
    void pathVariableCannotSpanASlash() throws Exception {
        MockMvc pp = standalone(new PathVariableProbe(), new PathPatternParser());
        MockMvc ant = standalone(new PathVariableProbe(), null);

        assertEquals("x", only(pp, "/pv/x").get("id"), "单段本来就该能匹配, 否则这条断言测不到东西");
        assertEquals(404, pp.perform(MockMvcRequestBuilders.get("/pv/x/y")).andReturn().getResponse().getStatus(),
                "PathPatternParser 下 {id:.+} 居然能跨斜杠, 说明这条量具失效了");
        assertEquals(404, ant.perform(MockMvcRequestBuilders.get("/pv/x/y")).andReturn().getResponse().getStatus(),
                "AntPathMatcher 下 {id:.+} 居然能跨斜杠, 说明这条量具失效了");
    }

    @RestController
    static class PathVariableProbe {
        @GetMapping("/pv/{id:.+}")
        public Map<String, Object> one(@PathVariable("id") String id) {
            Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
            out.put("id", id);
            return out;
        }
    }

    private MockMvc standalone(Object controller, PathPatternParser parser) {
        StandaloneMockMvcBuilder builder = MockMvcBuilders.standaloneSetup(controller);
        builder.setPatternParser(parser);
        return builder.build();
    }

    private Map<String, Object> only(MockMvc mvc, String uri) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.get(uri)).andReturn();
        assertEquals(200, result.getResponse().getStatus(), uri);
        assertTrue(body(result).containsKey("id"), uri);
        return body(result);
    }
}
