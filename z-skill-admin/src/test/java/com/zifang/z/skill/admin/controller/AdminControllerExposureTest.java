package com.zifang.z.skill.admin.controller;

import com.zifang.z.skill.admin.autoconfig.ZSkillAdminAutoConfiguration;
import com.zifang.z.skill.admin.service.AdminQueryService;
import com.zifang.z.skill.adminharness.HostConfigs;
import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.spec.SkillFormat;
import com.zifang.z.skill.core.registry.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制面的装配门: {@code z.skill.expose-admin} 到底挡住了什么.
 *
 * <p>两条装配路径都要钉住: 宿主自己 {@code @ComponentScan} 扫 admin 包(下面的 runner()),
 * 以及 z-skill-admin 自带的那份双钥匙自动装配(见 {@link #autoConfigurationStaysInertUntilBothSwitchesAreOn()}).
 * 无论哪条, 只加 {@code z-skill-starter} 的宿主都拿不到 {@code /skill/admin/*} — starter 不依赖本模块,
 * 这一点由 {@link #onlyTheAdminModulesOwnAutoConfigurationRegistersTheSurface()} 钉住,
 * 否则 README §6 的"两个条件"会被当成安全边界而实际上没人验过.
 */
class AdminControllerExposureTest {

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(HostConfigs.ScansAdmin.class);
    }

    /** 默认(不写 {@code z.skill.expose-admin}) → {@code AdminController} 不进容器; 查询服务本身照旧进. */
    @Test
    void adminControllerIsAbsentUnlessExposeAdminIsTrue() {
        runner().run(ctx -> {
            assertEquals(0, ctx.getBeanNamesForType(AdminController.class).length,
                    "expose-admin 没配却有控制器: " + Arrays.toString(ctx.getBeanNamesForType(AdminController.class)));
            assertFalse(ctx.containsBean("adminController"), "默认状态下 adminController 被注册了");
            assertEquals(1, ctx.getBeanNamesForType(AdminQueryService.class).length,
                    "AdminQueryService 应当随 component-scan 注册(它自己没有任何开关)");
            assertEquals(0, ctx.getBeanNamesForAnnotation(org.springframework.web.bind.annotation.RestController.class).length,
                    "默认状态下仍有 REST 控制器: "
                            + Arrays.toString(ctx.getBeanNamesForAnnotation(org.springframework.web.bind.annotation.RestController.class)));
        });
    }

    /** {@code z.skill.expose-admin=true} → 控制器注册, 且它读的就是容器里那一份注册中心. */
    @Test
    void exposeAdminTrueBringsTheControllerUpOnTheSameRegistry() {
        runner().withPropertyValues("z.skill.expose-admin=true").run(ctx -> {
            AdminController controller = ctx.getBean(AdminController.class);
            AdminQueryService service = ctx.getBean(AdminQueryService.class);
            SkillRegistry registry = ctx.getBean(SkillRegistry.class);

            registry.register(SkillDtoFixtures.audit());
            Map<String, Object> overview = controller.overview();
            assertEquals(1, overview.get("skillCount"), "控制面 REST 没看到注册中心里的条目: " + overview);
            assertEquals(service.overview().get("skillCount"), overview.get("skillCount"),
                    "控制器与服务看到的计数不一致(注进了两份注册中心)");
            assertEquals(1, controller.skills(null).size(), "REST 列表行不对");
            assertEquals("audit-report", controller.skills("审计").get(0).getId(), "分类过滤没走通");
            assertTrue(controller.categories().contains("审计"), "分类聚合缺项: " + controller.categories());
            assertEquals(1, controller.health().get("skills"), "health 的 skills 计数不对: " + controller.health());
            assertEquals("UP", controller.health().get("status"));
            assertNotNull(controller.issues(null), "issues 不能为 null");
        });
    }

    /** 非 {@code true} 串一律挡住: 门是 {@code havingValue="true"} 的字符串比较, 不是布尔解析. */
    @Test
    void nonTrueValuesKeepTheControllerOut() {
        for (String value : new String[]{"false", "no", "0", "yes", "on", "true-ish"}) {
            final String v = value;
            runner().withPropertyValues("z.skill.expose-admin=" + v).run(ctx ->
                    assertEquals(0, ctx.getBeanNamesForType(AdminController.class).length,
                            "expose-admin=" + v + " 竟然打开了控制面"));
        }
    }

    /**
     * 实测: {@code @ConditionalOnProperty} 的匹配是 {@code equalsIgnoreCase}, 所以 {@code True}/{@code TRUE}
     * 同样打开控制面. 记在这里免得有人以为"大小写写错就会安全地不装配".
     */
    @Test
    void havingValueMatchingIsCaseInsensitiveSoUppercaseTrueAlsoOpensTheSurface() {
        for (String value : new String[]{"True", "TRUE"}) {
            final String v = value;
            runner().withPropertyValues("z.skill.expose-admin=" + v).run(ctx ->
                    assertEquals(1, ctx.getBeanNamesForType(AdminController.class).length,
                            "expose-admin=" + v + " 应当仍然打开控制面(Boot 的 equalsIgnoreCase 语义)"));
        }
    }

    /**
     * README §6 写的是"总开关 {@code z.skill.enabled=true} **且** {@code z.skill.expose-admin=true},
     * 两个条件写在同一个 {@code @ConditionalOnProperty} 里". 实测: 注解上只有 {@code expose-admin} 一个 key,
     * 于是宿主扫了 admin 包时, {@code z.skill.enabled=false} 也挡不住 {@code /skill/admin/*}.
     * 这条把"当前真实行为"钉成断言, 修文档或修代码之后必须同步.
     */
    @Test
    void theOnlyGateIsExposeAdminTheMasterSwitchHasNoEffectHere() {
        ConditionalOnProperty gate = AdminController.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(gate, "AdminController 上的 @ConditionalOnProperty 被摘掉了");
        assertEquals(Collections.singletonList("z.skill.expose-admin"), Arrays.asList(gate.name()),
                "控制面的开关 key 变了(README §6 的说法要一起改)");
        assertFalse(Arrays.asList(gate.name()).contains("z.skill.enabled"),
                "已经补上总开关了? 那就把下面这条行为断言改成 enabled=false 也进不来");

        runner().withPropertyValues("z.skill.enabled=false", "z.skill.expose-admin=true").run(ctx -> {
            assertEquals(1, ctx.getBeanNamesForType(AdminController.class).length,
                    "行为已变: z.skill.enabled=false 现在能挡住控制面 — 请同步本断言与 README");
            assertTrue(ctx.containsBean("adminController"),
                    "行为已变: 总开关关闭时 adminController 不再注册 — 请同步本断言");
        });
    }

    /** 能把控制面带进容器的自动装配, 只允许是 admin 模块自己那份双钥匙装配. */
    @Test
    void onlyTheAdminModulesOwnAutoConfigurationRegistersTheSurface() throws IOException {
        ClassLoader loader = getClass().getClassLoader();
        List<Resource> descriptors = new ArrayList<Resource>();
        for (java.net.URL url : Collections.list(loader.getResources("META-INF/spring.factories"))) {
            descriptors.add(new org.springframework.core.io.UrlResource(url));
        }
        Collections.addAll(descriptors, new PathMatchingResourcePatternResolver(loader)
                .getResources("classpath*:META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"));
        assertFalse(descriptors.isEmpty(), "测试类路径上连一个自动装配描述文件都没有, 这条测试失去意义");
        Set<String> adminEntries = new LinkedHashSet<String>();
        List<String> fromStarter = new ArrayList<String>();
        for (Resource resource : descriptors) {
            String text = new String(StreamUtils.copyToByteArray(resource.getInputStream()), StandardCharsets.UTF_8);
            for (String token : text.split("[=,\\s\\\\]+")) {
                if (token.contains("com.zifang.z.skill.")) {
                    if (token.contains("com.zifang.z.skill.admin")) adminEntries.add(token);
                    fromStarter.add(resource.getDescription() + " -> " + token);
                }
            }
        }
        assertEquals(Collections.singleton("com.zifang.z.skill.admin.autoconfig.ZSkillAdminAutoConfiguration"),
                adminEntries, "注册控制面的自动装配只能有那一把双钥匙: " + fromStarter);
        assertEquals(ZSkillAdminAutoConfiguration.class.getName(),
                adminEntries.iterator().next(), "描述文件里登记的类和实际类名不一致");
    }

    /**
     * 装配级的双钥匙: 只开一把不算开. README §6 把它写成安全边界, 所以必须按行为钉,
     * 不能只盯着注解上的 key 数.
     */
    @Test
    void autoConfigurationStaysInertUntilBothSwitchesAreOn() {
        assertInert(autoRunner(), "两把钥匙都没开");
        assertInert(autoRunner("z.skill.enabled=true"), "只开总开关");
        assertInert(autoRunner("z.skill.expose-admin=true"), "只开控制面开关");
        assertInert(autoRunner("z.skill.enabled=false", "z.skill.expose-admin=true"), "总开关明确关着");
        // Boot 的 havingValue 比较是 equalsIgnoreCase, TRUE 也算开(与上面 exposeAdminAccepts 那条同口径)
        autoRunner("z.skill.enabled=TRUE", "z.skill.expose-admin=TRUE").run(ctx ->
                assertEquals(1, ctx.getBeanNamesForType(AdminController.class).length,
                        "两把钥匙都开(大写 TRUE)却没有控制面: " + Arrays.toString(ctx.getBeanNamesForType(AdminController.class))));
        autoRunner("z.skill.enabled=true", "z.skill.expose-admin=true").run(ctx ->
                assertEquals(1, ctx.getBeanNamesForType(AdminController.class).length,
                        "两把钥匙都开却没有控制面: " + Arrays.toString(ctx.getBeanNamesForType(AdminController.class))));
    }

    /**
     * 双钥匙装配的宿主: 只提供 core 侧 bean, 不扫 admin 包.
     *
     * <p>配置类一律放 {@code com.zifang.z.skill.adminharness} — 写在 admin 包里会被产品侧的
     * {@code @ComponentScan} 扫进来, 替身的 {@code @Bean} 就和 runner 注册的同一份定义撞名,
     * Boot 默认禁覆盖 ⇒ 抛 {@code BeanDefinitionStoreException: "@Bean definition illegally
     * overridden by existing bean definition"}, 看着像产品缺陷(见 {@link HostConfigs} 的说明).
     */
    private ApplicationContextRunner autoRunner(String... properties) {
        return new ApplicationContextRunner()
                .withUserConfiguration(HostConfigs.CoreBeansOnly.class)
                .withConfiguration(AutoConfigurations.of(ZSkillAdminAutoConfiguration.class))
                .withPropertyValues(properties);
    }

    private static void assertInert(ApplicationContextRunner runner, final String why) {
        runner.run(ctx -> {
            assertEquals(0, ctx.getBeanNamesForType(AdminController.class).length,
                    why + " 竟然注册了控制面: " + Arrays.toString(ctx.getBeanNamesForType(AdminController.class)));
            assertEquals(0, ctx.getBeanNamesForType(AdminQueryService.class).length,
                    why + " 竟然注册了控制面查询服务");
        });
    }

    /**
     * 控制台页必须躺在 Boot 默认就会映射的 {@code classpath:/static/skill-admin/} 下 —— README 里
     * {@code /skill-admin/index.html} 是用户唯一会去点的入口, 而 {@code templates/} 没有模板引擎时没人映射.
     *
     * <p>顺带把这页当成接线测试用: 它是手写 HTML + fetch, 后端改了路径而页面上还写着旧地址,
     * 没有任何编译期的东西会拦下来. 所以页面上出现的每个 {@code /skill/*} 字面量都必须真有人接 ——
     * 注意范围是整个 {@code /skill/} 前缀, 不只是控制面: 这页的市场表格打的是公共面
     * ({@code /skill/search}、{@code /skill/install}、{@code /skill/refresh}), 而公共面的路径比
     * 控制面更容易动(它跟着平台兼容层一起长).
     */
    @Test
    void consolePageIsWhereTheReadmePointsAndCallsOnlyEndpointsThatExist() throws IOException {
        assertNull(getClass().getResource("/templates/z-skill-admin/index.html"),
                "控制台页又在 templates/ 下留了一份: 那里没有映射, 改错的文件会让页面悄悄失效"
                        + "(若是 target/ 里的陈货, 先 mvn clean)");

        java.net.URL page = getClass().getResource("/static/skill-admin/index.html");
        assertNotNull(page, "控制台页不在 classpath:/static/skill-admin/index.html → README 的那个 URL 是死链");
        String html = new String(StreamUtils.copyToByteArray(page.openStream()), StandardCharsets.UTF_8);

        Set<String> called = skillPathsReferenced(html);
        assertFalse(called.isEmpty(), "页面里一个接口路径都没写死, 这条断言什么也测不到");
        Set<String> mapped = mappedSkillPaths();
        // 量具自查: 反射推导出来的映射集要是空了(或者少了某个面的头一条), "全绿"只是没东西可比.
        assertTrue(mapped.contains("/skill/search") && mapped.contains("/skill/refresh")
                        && mapped.contains("/skill/admin/health")
                        && mapped.contains("/skill/compat/skills-sh/api/v1/skills"),
                "映射集合推导跑偏(公共面/控制面/兼容面各该有一条), 实得 " + mapped.size() + " 条: " + mapped);
        // 页面的 id 一律走查询串, 不许拼进路径段: 聚合来的 id 长得就是 source/slug, 而 Tomcat 默认拒收
        // 路径里的 %2F(实测 400, 请求连 Spring 都没到), 不编码又会被切成两段(404)。这条替代了原先
        // isWired 里那段"尾斜杠前缀匹配"分支 —— 页面不再拼路径之后它一个猎物都没有, 留着就是死代码.
        // 放在 dangling 之前: 有人改回路径形时"打不存在的接口"只是症状, 这句才是原因(顺序即诊断质量).
        List<String> concatenated = new ArrayList<String>();
        for (String path : called) if (path.endsWith("/")) concatenated.add(path);
        assertTrue(concatenated.isEmpty(),
                "页面把 id 拼进了路径段(带尾斜杠的字面量): " + concatenated
                        + " —— 带斜杠的第三方 id 会被 Tomcat 拒成 400, 改走 ?id= 形式");
        List<String> dangling = new ArrayList<String>();
        for (String path : called) if (!isWired(path, mapped)) dangling.add(path);
        assertTrue(dangling.isEmpty(),
                "控制台页在打不存在的接口: " + dangling + "; 控制器实际提供 " + mapped);
        // 反向引线: 上面只钉"页面写的都有人接", 但"页面不再写某条"同样没人拦 —— 装/卸是这页的
        // 核心功能, 哪天它不接了, 控制台退回成只能看的列表页, README 那句话就成了假广告.
        List<String> missing = new ArrayList<String>();
        for (String feature : new String[]{"/skill/search", "/skill/install", "/skill/uninstall", "/skill/refresh"}) {
            if (!called.contains(feature)) missing.add(feature);
        }
        assertTrue(missing.isEmpty(),
                "控制台的这几条接线不见了(它们是功能, 不是偶然调用): " + missing + "; 页面实有 " + called);
    }

    /**
     * 页面上的一个路径字面量是否有人接: 必须逐字命中一条映射.
     *
     * <p>这条收窄是故意的: 那条按 id 取详情的映射会接住 {@code /skill/serch} 这样的拼错(它只是返回
     * 一条查不到的 skill), 逐字比较能把它报成死链, 而前缀匹配会放行 —— 页面届时表现为"没有结果",
     * 而不是"接口写错了".
     *
     * <p>原先这里还有一段"字面量以 / 结尾就拿映射模板砍到参数段之前来比"的分支, 服务于
     * 页面拼接 {@code '/skill/uninstall/' + encodeURIComponent(id)} 那种写法. 那种写法对带斜杠的
     * 第三方 id 根本不通(见上面 concatenated 那条), 已经改成查询串了, 这段分支也就没有猎物 ——
     * 一段没有猎物的匹配规则只会替下一个写错路径的人背书, 所以连同那条断言一起删了.
     */
    private static boolean isWired(String literal, Set<String> mapped) {
        return mapped.contains(literal);
    }

    /**
     * 控制台页那几个 DOM 辅助函数是这页唯一的渲染层, 写错了既编译不过关也 HTTP 层无痕 —— 2026-09-25
     * 真在浏览器里看到三处: 每张表首列渲染成 {@code [object HTMLElement]}、状态块永远停在"加载中…"
     * ({@code removeChild(node)} 抛 NotFoundError)、"已装"的 {@code <span>} 直接挂成 {@code <tr>} 的亲儿子.
     *
     * <p>这三条只有开着浏览器才会发现, 所以这里留文字级引线: 只钉"Node 不许被 String()、clear 要摘
     * firstChild、表格行只准塞 td", 不钉页面长什么样.
     */
    @Test
    void consoleRenderHelpersKeepNodesAsNodesAndOnlyTdsInRows() throws IOException {
        String script = consoleScript();

        String el = functionBody(script, "el");
        assertTrue(el.contains("instanceof Node"),
                "el() 不再区分 Node 与字符串: 表格里 el('td', el('code', id)) 会被 String() 成 [object HTMLElement]");
        assertTrue(el.contains("textContent"), "el() 丢了 textContent: 第三方 description/path 将回到 innerHTML 那条路");

        String clear = functionBody(script, "clear");
        assertTrue(clear.contains("removeChild(node.firstChild)"),
                "clear() 要摘掉 firstChild; removeChild(node) 会抛 NotFoundError, 状态块从此停在'加载中…'");

        List<String> nonTd = new ArrayList<String>();
        for (String cells : rowCellLists(script)) {
            for (String item : splitTopLevel(cells)) {
                // 只看单元格最外层的那几个 el(): el('td', el('span', ..)) 是合法的包装, 而
                // "s.installed ? el('span', ..) : ''" 会让 <span> 直接挂进 <tr> —— 那是非法表格结构
                for (String tag : outerElTags(item)) {
                    if (!"td".equals(tag)) nonTd.add(tag + " <- " + item.trim());
                }
            }
        }
        assertTrue(nonTd.isEmpty(), "row() 的单元格里有非 <td> 元素直接进 <tr>: " + nonTd);
    }

    /**
     * 装/卸按钮与 facet 下拉是这页上的两块"真功能", 它们的失败面全在编译期与状态码之外:
     * id 没过 {@code encodeURIComponent}(第三方平台的 id 里带 {@code /} 就会打到别的接口上)、
     * 请求体不再 JSON.stringify、动词写反(405 只会显示成一句错误文本)、下拉选了却不进查询串、
     * 或者每次刷新都把别人的选项清掉 —— 每一种都表现为"页面看着能用, 结果不对".
     *
     * <p>所以这里只钉结构(用什么 API、接的是哪根线), 不钉视觉.
     */
    @Test
    void consoleInstallButtonAndFacetSelectsStayWired() throws IOException {
        String script = consoleScript();

        String button = functionBody(script, "installButton");
        assertTrue(button.contains("'/skill/uninstall?id=' + encodeURIComponent(s.id)"),
                "卸载不再是'?id= + encodeURIComponent(id)'这个形状了: 带斜杠的第三方 id 拼进路径段会被"
                        + " Tomcat 拒成 400(实测), 没过 encodeURIComponent 则会打到另一个接口, "
                        + "两种都只表现为页面上一句错误文本");
        assertTrue(button.contains("JSON.stringify"),
                "请求体不再 JSON.stringify: 第三方 id 里带引号时会拼出非法 JSON");
        assertTrue(button.contains("'POST'") && button.contains("'DELETE'"),
                "装/卸的 HTTP 动词不见了: 动词用错是 405, 页面把它显示成一句错误文本而不像接口写反了");
        assertTrue(button.contains("setStatus") && button.contains("loadSkills()"),
                "按钮做完不刷新状态与列表: 点了装但表格仍显示没装, 用户只会再点一次");
        assertFalse(button.contains("innerHTML"), "installButton 里出现了 innerHTML: 第三方 id 将直接进 HTML");

        String facets = functionBody(script, "addFacetOptions");
        assertTrue(facets.contains("document.createElement('option')") && facets.contains("textContent"),
                "addFacetOptions 不再走 createElement + textContent: 第三方来源名/分类名会经 innerHTML 进 DOM");
        assertTrue(facets.contains("select.appendChild(opt)"), "addFacetOptions 造了选项却没挂进下拉框");
        for (String wiped : new String[]{"innerHTML", "options.length = 0", "clear(select"}) {
            assertFalse(facets.contains(wiped),
                    "addFacetOptions 里有清空既有选项的写法(" + wiped + "): 选定一个筛选条件后结果集会变小, "
                            + "选项被抹掉就换不回别的了");
        }

        Set<String> facetSelects = new LinkedHashSet<String>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("addFacetOptions\\('([A-Za-z_]+)',([^\\n]*)").matcher(script);
        while (m.find()) {
            assertTrue(m.group(2).contains("allFacets"),
                    "addFacetOptions('" + m.group(1) + "') 的候选项不来自服务端的 allFacets: " + m.group(2).trim()
                            + " — 页面自己数出来的口径会和筛选结果不一致");
            facetSelects.add(m.group(1));
        }
        assertEquals(new LinkedHashSet<String>(Arrays.asList("source", "category")), facetSelects,
                "控制台上接了 facet 的下拉框变了: 请与 loadSkills 的查询串、change 监听一起同步");
        String load = functionBody(script, "loadSkills");
        for (String id : facetSelects) {
            assertTrue(load.contains("params.set('" + id + "'"),
                    "下拉 #" + id + " 填了候选项, 但 loadSkills 没把它的值放进查询串: 筛选是摆设");
            assertTrue(script.contains("document.getElementById('" + id + "').addEventListener('change'"),
                    "下拉 #" + id + " 没人监听 change: 选了要等下一次别的动作才生效");
        }

        // 操作列必须还是 <td> 的亲儿子: installButton 返回的是 button, 直接交给 row() 就是非法表格结构
        boolean actionCell = false;
        for (String cells : rowCellLists(script)) {
            if (cells.contains("el('td', installButton(")) actionCell = true;
        }
        assertTrue(actionCell, "skills 表的操作列不再是 el('td', installButton(s)): button 会挂成 <tr> 的亲儿子");
    }

    /** 一段表达式里处在最外层(括号深度 0)的 {@code el('tag', ...)} 的 tag 列表. */
    private static List<String> outerElTags(String item) {
        List<String> out = new ArrayList<String>();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < item.length(); i++) {
            char c = item.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && c == 'e' && item.startsWith("el('", i)) {
                int end = item.indexOf('\'', i + 4);
                if (end > i + 4) out.add(item.substring(i + 4, end));
            }
        }
        return out;
    }

    /** 按顶层逗号切参数列表(括号与引号内不切). */
    private static List<String> splitTopLevel(String list) {
        List<String> out = new ArrayList<String>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < list.length(); i++) {
            char c = list.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') quote = c;
            else if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                out.add(list.substring(start, i));
                start = i + 1;
            }
        }
        out.add(list.substring(start));
        return out;
    }

    /** 控制台页里 {@code <script>} 那一段. */
    private static String consoleScript() throws IOException {
        java.net.URL page = AdminControllerExposureTest.class.getResource("/static/skill-admin/index.html");
        assertNotNull(page, "控制台页不在 classpath:/static/skill-admin/index.html");
        String html = new String(StreamUtils.copyToByteArray(page.openStream()), StandardCharsets.UTF_8);
        int from = html.indexOf("<script>");
        int to = html.indexOf("</script>", from);
        assertTrue(from >= 0 && to > from, "页面里没有 <script> 段, 这条引线测不到东西");
        return html.substring(from, to);
    }

    /** 取 {@code function name(...)} 的函数体(按花括号配对, 页内代码没有字符串里的花括号). */
    private static String functionBody(String script, String name) {
        int at = script.indexOf("function " + name + "(");
        assertTrue(at >= 0, "页面上没有 function " + name + "( 了, 请同步更新这条断言");
        int open = script.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < script.length(); i++) {
            char c = script.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return script.substring(open + 1, i);
            }
        }
        throw new AssertionError("function " + name + " 的花括号没配对");
    }

    /** 每个 {@code row(tbody, [ ... ])} 调用的单元格列表原文. */
    private static List<String> rowCellLists(String script) {
        List<String> out = new ArrayList<String>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("row\\(tbody,\\s*\\[").matcher(script);
        while (m.find()) {
            int open = m.end() - 1;
            int depth = 0;
            for (int i = open; i < script.length(); i++) {
                char c = script.charAt(i);
                if (c == '[') depth++;
                else if (c == ']') {
                    depth--;
                    if (depth == 0) {
                        out.add(script.substring(open + 1, i));
                        break;
                    }
                }
            }
        }
        assertFalse(out.isEmpty(), "页面里一个 row(tbody, [..]) 都没有, 这条断言什么也测不到");
        return out;
    }

    /**
     * 页面上写死的接口路径字面量: 只认紧跟在引号后面的那一段(注释里散文提到的路径不算接线),
     * 查询串与拼接的后半段自然被字符类切掉.
     *
     * <p>{@code (?![-A-Za-z0-9])} 那道负向前瞻是必需的: 这页自己就在 {@code /skill-admin/} 下,
     * 少了它 {@code '/skill-admin/index.html'} 会被截成一条"调用", 然后被当成死链.
     */
    private static Set<String> skillPathsReferenced(String html) {
        Set<String> out = new LinkedHashSet<String>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("['\"](/skill(?![-A-Za-z0-9])(?:/[A-Za-z0-9._/-]*)?)").matcher(html);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /**
     * 三个控制器真实注册的全部 {@code /skill} 前缀路径: 类级 @RequestMapping 前缀 + 各方法映射.
     *
     * <p>从注解反射推导, 而不是抄一份清单 —— 抄的那份会跟着后端一起过期, 那这条门就只测得见
     * "两边都忘了改"的情形. 页面用得上的兼容面路径也一并进来, 免得日后把兼容搜索接到控制台上时
     * 先得改这条测试.
     */
    private static Set<String> mappedSkillPaths() {
        Set<String> out = new LinkedHashSet<String>();
        collect(out, com.zifang.z.skill.core.controller.SkillController.class);
        collect(out, com.zifang.z.skill.core.controller.SkillCompatController.class);
        collect(out, AdminController.class);
        return out;
    }

    private static void collect(Set<String> out, Class<?> controller) {
        org.springframework.web.bind.annotation.RequestMapping root =
                controller.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class);
        String base = root == null ? "" : root.value().length == 0 ? "" : root.value()[0];
        for (java.lang.reflect.Method method : controller.getDeclaredMethods()) {
            add(out, base, method, org.springframework.web.bind.annotation.GetMapping.class);
            add(out, base, method, org.springframework.web.bind.annotation.PostMapping.class);
            add(out, base, method, org.springframework.web.bind.annotation.DeleteMapping.class);
        }
    }

    private static void add(Set<String> out, String base, java.lang.reflect.Method method,
                            Class<? extends java.lang.annotation.Annotation> mapping) {
        java.lang.annotation.Annotation present = method.getAnnotation(mapping);
        if (present == null) return;
        String[] paths;
        try {
            paths = (String[]) mapping.getMethod("value").invoke(present);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("读不到 " + mapping.getSimpleName() + " 的 value(): " + e, e);
        }
        if (paths.length == 0) {
            out.add(base);
            return;
        }
        for (String path : paths) out.add(base + path);
    }

    // ---------------------------------------------------------------- helpers

    /** 手搓一条 DTO, 只用于验证 REST 层把容器里那份注册中心读到了. */
    private static final class SkillDtoFixtures {
        static com.zifang.z.skill.api.dto.SkillDto audit() {
            return com.zifang.z.skill.api.dto.SkillDto.builder()
                    .id("audit-report")
                    .slug("audit-report")
                    .name("Audit report")
                    .description("跨组织仓合规审计")
                    .category("审计")
                    .format(SkillFormat.AGENT_SKILLS)
                    .riskLevel(SkillDto.RISK_LOW)
                    .contentHash("deadbeefdeadbeef")
                    .build();
        }
    }
}
