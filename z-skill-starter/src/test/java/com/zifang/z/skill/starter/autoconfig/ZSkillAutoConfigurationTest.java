package com.zifang.z.skill.starter.autoconfig;

import com.zifang.z.skill.api.dto.AggregateReportDto;
import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.dto.SkillSourceDto;
import com.zifang.z.skill.api.spec.SkillFormat;
import com.zifang.z.skill.core.aggregate.SkillAggregator;
import com.zifang.z.skill.core.content.SkillContentReader;
import com.zifang.z.skill.core.controller.SkillCompatController;
import com.zifang.z.skill.core.controller.SkillController;
import com.zifang.z.skill.core.controller.SkillErrorAdvice;
import com.zifang.z.skill.core.discover.SkillSource;
import com.zifang.z.skill.core.properties.SkillProperties;
import com.zifang.z.skill.core.registry.SkillRegistry;
import com.zifang.z.skill.core.scan.SkillSecurityScanner;
import com.zifang.z.skill.core.search.SkillSearchEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * z-skill-starter 的自动装配端到端测试 — 覆盖"开关 + bean 全在 + 属性真绑得上 +
 * 宿主自己 {@code @ComponentScan} 扫到我们的包时不会爆重复 bean".
 *
 * <p>三条装配路径都测, 因为它们的失败模式不同:
 * <ul>
 *   <li>{@link ApplicationContextRunner}: 只走 Spring 默认(允许覆盖), 用来钉 bean 名与属性绑定;</li>
 *   <li>手工 {@code AnnotationConfigApplicationContext} + {@code allowBeanDefinitionOverriding=false}:
 *       复现"宿主扫 {@code com.zifang.z.skill.core}"这一条;
 *       {@link ZSkillAutoConfiguration} 的 javadoc 明确声称这条路径安全.</li>
 *   <li>真 {@link SpringApplicationBuilder}: 复现 README 暗示的"宿主 {@code scanBasePackages=com.zifang.z.skill}
 *       并把 z-skill-admin 加进 classpath"这一条 — 此时自动装配类会被"扫描注册一次 +
 *       {@code @EnableAutoConfiguration} 再注册一次".</li>
 * </ul>
 *
 * <p>断言全部来自生产代码实测, 不来自 README 措辞: 属性名以 {@link SkillProperties} 的字段为准.
 */
class ZSkillAutoConfigurationTest {

    private static final String ENABLED = "z.skill.enabled=true";
    /** 关掉本机平台探测, 否则结果取决于这台机器装过什么 claude/cursor/qoder. */
    private static final String NO_PLATFORM_DISCOVERY = "z.skill.discover-installed-platforms=false";
    /** 上下文启动即聚合会让本测试去扫真实磁盘; 显式关掉, 聚合由测试自己触发. */
    private static final String NO_STARTUP_REFRESH = "z.skill.refresh-on-startup=false";

    private static final AutoConfigurations AUTOCONFIG =
            AutoConfigurations.of(ZSkillAutoConfiguration.class);

    @TempDir
    Path tmp;

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AUTOCONFIG);
    }

    // ---------------------------------------------------------------- 开关

    /** 没写 {@code z.skill.enabled} 时什么都不装配 — 这是 §6 "总开关默认关" 的全部承诺. */
    @Test
    void nothingIsWiredWhenTheFlagIsAbsent() {
        runner().run(ctx -> {
            assertTrue(ctx.getBeanNamesForType(SkillRegistry.class).length == 0,
                    "缺省配置下竟然注册了 SkillRegistry: " + Arrays.toString(ctx.getBeanNamesForType(SkillRegistry.class)));
            assertTrue(ctx.getBeanNamesForType(SkillAggregator.class).length == 0,
                    "缺省配置下竟然注册了 SkillAggregator");
            assertTrue(ctx.getBeanNamesForType(SkillProperties.class).length == 0,
                    "缺省配置下竟然注册了 SkillProperties");
            assertTrue(ctx.getBeanNamesForType(ZSkillAutoConfiguration.class).length == 0,
                    "缺省配置下 ZSkillAutoConfiguration 本体不该进容器");
            assertNoHttpEndpointOwned(ctx.getBeanNamesForAnnotation(Controller.class), "缺省");
            assertNoHttpEndpointOwned(ctx.getBeanNamesForAnnotation(RestControllerAdvice.class), "缺省");
            assertTrue(ctx.getBeansOfType(Object.class).keySet().stream()
                            .filter(n -> n.startsWith("skill")).count() == 0,
                    "仍有 skill* 命名的 bean: " + ctx.getBeansOfType(Object.class).keySet());
        });
    }

    /** {@code z.skill.enabled=false} 与"不写"必须同义, 且不能因为开关关闭而启动失败. */
    @Test
    void explicitFalseIsTreatedExactlyLikeAbsent() {
        runner().withPropertyValues("z.skill.enabled=false", NO_PLATFORM_DISCOVERY)
                .run(ctx -> {
                    assertFalse(ctx.containsBean("skillRegistry"), "z.skill.enabled=false 仍然装配了 skillRegistry");
                    assertTrue(ctx.getBeanNamesForAnnotation(Controller.class).length == 0,
                            "z.skill.enabled=false 仍有控制器: "
                                    + Arrays.toString(ctx.getBeanNamesForAnnotation(Controller.class)));
                });
    }

    /** {@code havingValue="true"} 是严格匹配: 写 {@code yes}/{@code on} 这类非法布尔串时必须什么都不装. */
    @Test
    void nonTrueValuesDoNotEnableTheLibrary() {
        for (String value : new String[]{"yes", "on", "1", "TRUE-ish"}) {
            final String v = value;
            runner().withPropertyValues("z.skill.enabled=" + v).run(ctx ->
                    assertTrue(ctx.getBeanNamesForType(SkillRegistry.class).length == 0,
                            "z.skill.enabled=" + v + " 竟然把库打开了"));
        }
    }

    // ---------------------------------------------------------------- bean 全在

    /** 开关打开后, 注册中心/聚合器/检索/扫描/读取器/两个控制器/异常 advice 都要在, 且 bean 名与 javadoc 一致. */
    @Test
    void enabledSwitchWiresEveryDocumentedBeanWithItsDocumentedName() {
        runner().withPropertyValues(ENABLED, NO_PLATFORM_DISCOVERY, NO_STARTUP_REFRESH)
                .run(ctx -> {
                    String[] expected = {"skillRegistry", "skillSecurityScanner", "skillContentReader",
                            "skillSearchEngine", "skillAggregator", "skillStartupRunner",
                            "skillController", "skillCompatController", "skillErrorAdvice"};
                    List<String> missing = new ArrayList<String>();
                    for (String name : expected) {
                        if (!ctx.containsBean(name)) missing.add(name);
                    }
                    assertTrue(missing.isEmpty(), "缺少应有 bean: " + missing + "; 实得 "
                            + new TreeSet<>(ctx.getBeansOfType(Object.class).keySet()));

                    Class<?>[] types = {SkillRegistry.class, SkillAggregator.class, SkillSearchEngine.class,
                            SkillSecurityScanner.class, SkillContentReader.class, SkillProperties.class,
                            SkillController.class, SkillCompatController.class, SkillErrorAdvice.class,
                            ZSkillAutoConfiguration.class};
                    List<String> notUnique = new ArrayList<String>();
                    for (Class<?> type : types) {
                        if (ctx.getBeanNamesForType(type).length != 1) {
                            notUnique.add(type.getSimpleName() + "=" + Arrays.toString(ctx.getBeanNamesForType(type)));
                        }
                    }
                    assertTrue(notUnique.isEmpty(), "bean 数量不是恰好 1 个: " + notUnique);

                    // 控制器/advice 是 @ComponentScan 带进来的, 不是 @Bean: 名字必须是默认扫描名
                    assertEquals("skillController", ctx.getBeanNamesForType(SkillController.class)[0]);
                    assertEquals("skillCompatController", ctx.getBeanNamesForType(SkillCompatController.class)[0]);
                    assertEquals("skillErrorAdvice", ctx.getBeanNamesForType(SkillErrorAdvice.class)[0]);

                    // 控制器确实被 MVC 认成端点持有者(类上有 @Controller 元注解)
                    Set<String> annotated = new LinkedHashSet<String>(
                            Arrays.asList(ctx.getBeanNamesForAnnotation(Controller.class)));
                    assertTrue(annotated.contains("skillController") && annotated.contains("skillCompatController"),
                            "@ComponentScan 后控制器没被认成控制器: " + annotated);
                    assertTrue(Arrays.asList(ctx.getBeanNamesForAnnotation(RestControllerAdvice.class))
                                    .contains("skillErrorAdvice"),
                            "SkillErrorAdvice 没进 advice 视野: "
                                    + Arrays.toString(ctx.getBeanNamesForAnnotation(RestControllerAdvice.class)));
                });
    }

    /**
     * {@code SkillStartupRunner} 是 {@code CommandLineRunner}: {@code ApplicationContextRunner}
     * 不会执行它. 这条断言钉住"bean 在 ≠ 启动即聚合", 免得将来有人误以为上下文一起来目录就有内容.
     */
    @Test
    void contextBootAloneDoesNotAggregateUntilRefreshIsCalled() throws IOException {
        Path flat = writeSkillAtRootOf("zskill-autostart-proof", "Deploy helper", "部署辅助");
        runner().withPropertyValues(ENABLED, NO_PLATFORM_DISCOVERY, NO_STARTUP_REFRESH,
                        "z.skill.sources[0].id=autostart", "z.skill.sources[0].format=agent-skills",
                        "z.skill.sources[0].path=" + flat)
                .run(ctx -> {
                    SkillRegistry registry = ctx.getBean(SkillRegistry.class);
                    assertEquals(0, registry.skillCount(),
                            "上下文刚起来就聚合了(应由 CommandLineRunner 触发, 而它没被执行)");
                    assertNull(registry.getLastReport(), "刚启动不该有聚合报告");

                    AggregateReportDto report = ctx.getBean(SkillAggregator.class).refresh();
                    assertEquals(1, report.getSkillCount(), "手动 refresh 后收录数不对: " + describe(report));
                    assertEquals(1, registry.skillCount(), "refresh 后注册中心没有内容");
                    assertNotNull(registry.getLastReport(), "refresh 后仍无报告");
                });
    }

    /** 容器里那套 bean 是真能跑通的 DI, 不是一堆空壳: refresh -> registry -> search -> controller. */
    @Test
    void springWiredPipelineActuallyServesContentEndToEnd() throws IOException {
        Path flat = writeSkillAtRootOf("zskill-di-runs", "Schedule planner", "任务排期与调度");
        runner().withPropertyValues(ENABLED, NO_PLATFORM_DISCOVERY, NO_STARTUP_REFRESH,
                        "z.skill.sources[0].id=di", "z.skill.sources[0].format=agent-skills",
                        "z.skill.sources[0].path=" + flat)
                .run(ctx -> {
                    SkillAggregator aggregator = ctx.getBean(SkillAggregator.class);
                    SkillRegistry registry = ctx.getBean(SkillRegistry.class);
                    SkillSearchEngine search = ctx.getBean(SkillSearchEngine.class);
                    SkillController controller = ctx.getBean(SkillController.class);
                    SkillCompatController compat = ctx.getBean(SkillCompatController.class);

                    AggregateReportDto report = aggregator.refresh();
                    assertEquals(1, report.getSkillCount(), "聚合没产出: " + describe(report));
                    assertSame(report, registry.getLastReport(),
                            "聚合器写进的不是容器里那一个注册中心(DI 注了两份)");

                    SkillDto dto = registry.get("schedule-planner").orElse(null);
                    assertNotNull(dto, "按 slug 找不到收录结果, 实际 id=" + ids(registry));
                    assertEquals("Schedule planner", dto.getName(), "原始 name 没保留");
                    assertEquals("任务排期与调度", dto.getDescription(), "中文描述没按 UTF-8 存下来");

                    assertEquals(1, search.search(new SkillSearchEngine.Query().q("排期").perPage(10))
                                    .getSkills().size(),
                            "中文检索在 Spring 装配的引擎上不通");

                    assertEquals(1, ((Number) controller.stats().get("skillCount")).intValue(),
                            "REST 控制器看到的是空目录 → DI 注进去的不是同一个注册中心");
                    List<SkillSourceDto> sources = controller.sources();
                    assertEquals(1, sources.size(), "控制器没看到来源: " + sources);
                    assertEquals("di", sources.get(0).getId());
                    assertEquals("ok", sources.get(0).getStatus(), "来源状态不是 ok: " + sources);
                    Map<String, Object> index = compat.wellKnownIndex();
                    assertEquals(1, ((Number) index.get("total")).intValue(),
                            "兼容层看不到注册中心里的内容: " + index);
                    List<?> emitted = (List<?>) index.get("skills");
                    assertEquals("schedule-planner",
                            ((Map<?, ?>) emitted.get(0)).get("name"), "index.json 的 name 应是 slug: " + emitted);
                });
    }

    // ---------------------------------------------------------------- 属性绑定

    /** sources[] 列表 + 标量必须逐项绑定成功; 声明顺序与 priority 缺省值按生产实现钉住. */
    @Test
    void declaredSourcesAndScalarsBindFromProperties() throws IOException {
        Path exists = writeSkillAtRootOf("zskill-bound", "Bound skill", "被绑定的来源");
        Path gone = tmp.resolve("no-such-dir-at-all");
        runner().withPropertyValues(
                        ENABLED, NO_PLATFORM_DISCOVERY,
                        "z.skill.refresh-on-startup=false",
                        "z.skill.expose-admin=true",
                        "z.skill.scan-remote-on-startup=true",
                        "z.skill.max-depth=4",
                        "z.skill.project-root=" + tmp,
                        "z.skill.user-home=" + tmp,
                        "z.skill.sources[0].id=explicit",
                        "z.skill.sources[0].format=agent-skills",
                        "z.skill.sources[0].path=" + exists,
                        "z.skill.sources[0].priority=7",
                        "z.skill.sources[0].category=运维",
                        "z.skill.sources[0].max-depth=0",
                        "z.skill.sources[1].id=remote-registry",
                        "z.skill.sources[1].format=registry-index",
                        "z.skill.sources[1].url=https://skills.example.invalid/.well-known/agent-skills/index.json",
                        "z.skill.sources[2].id=switched-off",
                        "z.skill.sources[2].format=agent-skills",
                        "z.skill.sources[2].path=" + exists,
                        "z.skill.sources[2].enabled=false",
                        "z.skill.sources[3].id=missing-dir",
                        "z.skill.sources[3].format=agent-skills",
                        "z.skill.sources[3].path=" + gone)
                .run(ctx -> {
                    SkillProperties props = ctx.getBean(SkillProperties.class);
                    assertTrue(props.isEnabled(), "enabled 没绑上");
                    assertFalse(props.isRefreshOnStartup(), "refresh-on-startup 没绑上");
                    assertTrue(props.isExposeAdmin(), "expose-admin 没绑上");
                    assertTrue(props.isScanRemoteOnStartup(), "scan-remote-on-startup 没绑上");
                    assertFalse(props.isDiscoverInstalledPlatforms(), "discover-installed-platforms 没绑上");
                    assertEquals(4, props.getMaxDepth(), "max-depth 没绑上");
                    assertEquals(tmp.toString(), props.getProjectRoot(), "project-root 没绑上");
                    assertEquals(tmp.toString(), props.getUserHome(), "user-home 没绑上");
                    assertEquals(4, props.getSources().size(), "sources[] 没整个绑上: " + props.getSources().size());
                    assertEquals("运维", props.getSources().get(0).getCategory(), "sources[0].category 没绑上");
                    assertEquals("https://skills.example.invalid/.well-known/agent-skills/index.json",
                            props.getSources().get(1).getUrl(), "sources[1].url 没绑上");
                    assertFalse(props.getSources().get(2).isEnabled(), "sources[2].enabled=false 没绑上");

                    List<SkillSource> declared = ctx.getBean(SkillAggregator.class).getDeclaredSources();
                    assertEquals(Arrays.asList("explicit", "remote-registry"), idsOf(declared),
                            "聚合器实际拿到的来源集合不对(不存在目录/disabled 的应被跳过)");

                    SkillSource first = declared.get(0);
                    assertEquals(SkillFormat.AGENT_SKILLS, first.getFormat(), "format 字符串没解析成枚举");
                    assertEquals(exists.toString(), first.getPath(), "path 没落到 SkillSource");
                    assertEquals(7, first.getPriority(), "显式 priority 没生效");
                    assertEquals("运维", first.getCategoryHint(), "category 没变成 categoryHint");
                    assertEquals(0, first.getMaxDepth(), "sources[0].max-depth 没透传");
                    assertEquals("local", first.getSourceType());
                    assertFalse(first.isRemote());

                    SkillSource second = declared.get(1);
                    assertEquals(SkillFormat.REGISTRY_INDEX, second.getFormat());
                    assertTrue(second.isRemote(), "url 型来源没被认成 remote");
                    assertEquals("api", second.getSourceType());
                    assertNull(second.getPath(), "远端来源不该有 path");
                    // SkillProperties.Source 的 priority 默认是 100, >0 于是压过了 toSource() 的递增缺省值
                    assertEquals(100, second.getPriority(),
                            "未显式给 priority 时用的是 Source 字段默认值 100, 而不是位置递增的 20");

                    // 被静默跳过的两条: 只有一条 log.info, 报告里没有任何痕迹
                    assertFalse(idsOf(declared).contains("missing-dir"),
                            "不存在的目录被静默跳过是既定行为, 一旦改成记账请同步本断言");
                });
    }

    /**
     * 规范落点是 {@code <skills-dir>/<name>/SKILL.md}; 但 {@code z.skill.sources[].max-depth} 的字段默认值是
     * {@code 0}(= 只看来源目录本层文件), 而 {@code toSource()} 会把它无条件透传, 于是"按 README 写的配置"
     * 扫不到任何 skill, 也不记任何 issue. 这条测试钉住这个真实行为, 并给出可用的写法(显式 max-depth).
     */
    @Test
    void canonicalNestedLayoutNeedsExplicitMaxDepthOtherwiseAggregationIsSilentlyEmpty() throws IOException {
        Path nestedRoot = Files.createDirectories(tmp.resolve("nested/scheduler-doc"));
        writeSkill(nestedRoot, "zskill-nested", "Nested skill", "嵌套目录里的 skill");

        String[] common = {ENABLED, NO_PLATFORM_DISCOVERY, NO_STARTUP_REFRESH,
                "z.skill.sources[0].id=nested-default", "z.skill.sources[0].format=agent-skills",
                "z.skill.sources[0].path=" + tmp.resolve("nested")};

        // (a) README 那种"只写 path"的配置
        runner().withPropertyValues(common).run(ctx -> {
            AggregateReportDto report = ctx.getBean(SkillAggregator.class).refresh();
            assertEquals(0, report.getRawCount(),
                    "max-depth 未显式给时, 候选数实测应为 0(现在是 " + describe(report) + ") — 若已修复请同步此断言");
            assertEquals(0, report.getSkillCount(), "按 README 只写 path 竟收到了 skill");
            assertTrue(report.getIssues().isEmpty(),
                    "静默空目录现在居然记了 issue: " + describe(report));
            assertEquals(0, ctx.getBean(SkillRegistry.class).skillCount(), "注册中心被填了");
        });

        // (b) 同样内容, 显式给 max-depth 就有内容 → 差异只来自那个 0 默认值
        List<String> withDepth = new ArrayList<String>(Arrays.asList(common));
        withDepth.add("z.skill.sources[0].max-depth=2");
        runner().withPropertyValues(withDepth.toArray(new String[0])).run(ctx -> {
            AggregateReportDto report = ctx.getBean(SkillAggregator.class).refresh();
            assertEquals(1, report.getSkillCount(), "显式 max-depth=2 仍扫不到: " + describe(report));
            SkillDto dto = ctx.getBean(SkillRegistry.class).get("nested-skill").orElse(null);
            assertNotNull(dto, "显式 max-depth 后仍找不到条目: " + describe(report));
            assertEquals("scheduler-doc/SKILL.md", entryRelative(dto), "命中路径不对");
        });
    }

    /**
     * 未知 key 一律被 {@code @ConfigurationProperties} 吞掉: 写成 {@code z.skill.scan-dir}(README/口头
     * 需求里出现过的那个"扫描目录"开关)不会报错, 只会得到一个空目录 — 所以这里同时钉住
     * "SkillProperties 的真实可绑定属性集合", 任何新增/改名都会被这条断言拦下.
     */
    @Test
    void unknownKeysSuchAsScanDirAreSwallowedSilentlyAndTheBindableKeySetIsPinned() throws IOException {
        Path nested = Files.createDirectories(tmp.resolve("swallowed/zskill-lost"));
        writeSkill(nested, "zskill-lost", "Swallowed skill", "被吞掉的配置");

        Set<String> bindable = setterNames(SkillProperties.class);
        assertEquals(new TreeSet<String>(Arrays.asList("enabled", "refreshOnStartup", "exposeAdmin",
                        "discoverInstalledPlatforms", "projectRoot", "userHome", "maxDepth",
                        "scanRemoteOnStartup", "sources")), bindable,
                "z.skill.* 的可绑定属性集合变了 — README 与宿主配置要一起改");
        assertFalse(bindable.contains("scanDir"), "SkillProperties 并不存在 scan-dir/scanDir 这个开关");

        runner().withPropertyValues(ENABLED, NO_PLATFORM_DISCOVERY, NO_STARTUP_REFRESH,
                        "z.skill.scan-dir=" + tmp.resolve("swallowed"),
                        "z.skill.sources-dir=" + tmp.resolve("swallowed"),
                        "z.skill.refreshOnStartup=nope-not-a-boolean-but-unknown-anyway")
                .run(ctx -> {
                    assertTrue(ctx.getBeanNamesForType(SkillAggregator.class).length == 1,
                            "未知 key 竟然把上下文弄挂了");
                    assertEquals(0, ctx.getBean(SkillAggregator.class).getDeclaredSources().size(),
                            "scan-dir 竟然真的被当成了来源目录");
                    assertEquals(0, ctx.getBean(SkillAggregator.class).refresh().getRawCount(),
                            "配错 key 的结果必须是'空目录', 而不是'意外有内容'");
                    assertEquals(0, ctx.getBean(SkillRegistry.class).skillCount());
                });
    }

    // ------------------------------------------------- 宿主自己扫我们的包

    /** 宿主 {@code @ComponentScan("com.zifang.z.skill.core")} + 自动装配: bean 不能变成两份(Spring 默认允许覆盖, 数量看不出来 → 见下两条). */
    @Test
    void hostComponentScanOfCorePackageKeepsEveryBeanSingle() {
        runner().withUserConfiguration(ScannedCoreHost.class)
                .withConfiguration(AUTOCONFIG)
                .withPropertyValues(ENABLED, NO_PLATFORM_DISCOVERY, NO_STARTUP_REFRESH)
                .run(ctx -> assertLibraryBeansAreSingle(ctx, "ApplicationContextRunner + 宿主扫 core"));
    }

    /**
     * Spring Boot 2.1+ 把 {@code allow-bean-definition-overriding} 默认关了, 所以"重复注册"在真实应用里
     * 不是"少一个 bean"而是"启动失败". 这条用手工上下文显式复现该默认值, 钉住 javadoc 的承诺:
     * 宿主扫 {@code com.zifang.z.skill.core} + 本自动装配, 不允许覆盖, 也得起得来.
     */
    @Test
    void hostComponentScanOfCoreSurvivesDisabledBeanOverriding() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.setAllowBeanDefinitionOverriding(false);
        Map<String, Object> props = new HashMap<String, Object>();
        props.put("z.skill.enabled", "true");
        props.put("z.skill.discover-installed-platforms", "false");
        props.put("z.skill.refresh-on-startup", "false");
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("zskill-test", props));
        try {
            ctx.register(ScannedCoreHost.class, ZSkillAutoConfiguration.class);
            ctx.refresh();
            assertTrue(ctx.getBeanFactory() instanceof DefaultListableBeanFactory
                            && !((DefaultListableBeanFactory) ctx.getBeanFactory()).isAllowBeanDefinitionOverriding(),
                    "这条测试没起作用: 覆盖开关必须是关的");
            assertLibraryBeansAreSingle(ctx, "宿主扫 core + allowBeanDefinitionOverriding=false");
        } catch (RuntimeException e) {
            fail("宿主 component-scan com.zifang.z.skill.core 时装配崩了: " + rootCauseChain(e)
                    + " — 与 ZSkillAutoConfiguration javadoc 的承诺相反");
        } finally {
            ctx.close();
        }
    }

    /**
     * 最真实的一种宿主: {@code @SpringBootApplication(scanBasePackages = "com.zifang.z.skill")}
     * (README 要求把 z-skill-admin 加进 classpath 才能拿到控制面, 那就必须这么扫).
     * 此时自动装配类自身既被 component-scan 注册一次, 又被 {@code @EnableAutoConfiguration} 注册一次.
     */
    @Test
    void wholeLibraryPackageScanBootsUnderARealSpringApplication() {
        ConfigurableApplicationContext app = null;
        try {
            app = new SpringApplicationBuilder(WholeLibraryScanHost.class)
                    .web(WebApplicationType.NONE)
                    .properties(ENABLED, NO_PLATFORM_DISCOVERY, NO_STARTUP_REFRESH,
                            "spring.main.banner-mode=off")
                    .run();
            ConfigurableListableBeanFactory bf = app.getBeanFactory();
            assertTrue(bf instanceof DefaultListableBeanFactory
                            && !((DefaultListableBeanFactory) bf).isAllowBeanDefinitionOverriding(),
                    "SpringApplication 默认应当不允许覆盖 bean 定义; 若哪天变成允许, 这条测试失去意义请改写");
            assertLibraryBeansAreSingle(app, "真 SpringApplication + 宿主扫 com.zifang.z.skill");
            assertEquals(1, app.getBeanNamesForType(ZSkillAutoConfiguration.class).length,
                    "自动装配类被注册了不止一次: " + Arrays.toString(app.getBeanNamesForType(ZSkillAutoConfiguration.class)));
        } catch (RuntimeException e) {
            fail("宿主 scanBasePackages=com.zifang.z.skill 时启动失败: " + rootCauseChain(e));
        } finally {
            if (app != null) app.close();
        }
    }

    // ---------------------------------------------------------------- helpers

    private void assertLibraryBeansAreSingle(ConfigurableApplicationContext ctx, String scenario) {
        List<String> wrong = new ArrayList<String>();
        Class<?>[] types = {SkillRegistry.class, SkillAggregator.class, SkillSearchEngine.class,
                SkillSecurityScanner.class, SkillContentReader.class, SkillProperties.class,
                SkillController.class, SkillCompatController.class, SkillErrorAdvice.class,
                // SkillStartupRunner 那个 @Bean 没有 @ConditionalOnMissingBean 兜底, 是最容易被注册成两份的一个
                ZSkillAutoConfiguration.SkillStartupRunner.class, ZSkillAutoConfiguration.class};
        for (Class<?> type : types) {
            String[] names = ctx.getBeanNamesForType(type);
            if (names.length != 1) wrong.add(type.getSimpleName() + " -> " + Arrays.toString(names));
        }
        assertTrue(wrong.isEmpty(), scenario + ": bean 不是恰好一份 " + wrong);
        // 上面按类型数过一遍, 这里按 bean 名再钉一次: 重复注册只会改名字, 不会改类型计数
        List<String> missing = new ArrayList<String>();
        for (String name : new String[]{"skillRegistry", "skillAggregator", "skillSearchEngine",
                "skillSecurityScanner", "skillContentReader", "skillStartupRunner",
                "skillController", "skillCompatController", "skillErrorAdvice"}) {
            if (!ctx.containsBean(name)) missing.add(name);
        }
        assertTrue(missing.isEmpty(), scenario + ": 少了应有 bean " + missing);
    }

    private static void assertNoHttpEndpointOwned(String[] names, String scenario) {
        assertEquals(0, names.length, scenario + "配置下不应有任何控制器被注册: " + Arrays.toString(names));
    }

    /** 直接把 SKILL.md 放在来源目录本层 — 这是 {@code sources[].max-depth} 默认值 0 唯一能看见的摆法. */
    private Path writeSkillAtRootOf(String slug, String name, String description) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve(slug));
        writeSkill(dir, slug, name, description);
        return dir;
    }

    private static void writeSkill(Path dir, String slug, String name, String description) throws IOException {
        String md = "---\n"
                + "name: " + name + "\n"
                + "description: " + description + "\n"
                + "metadata:\n"
                + "  version: 1.2.3\n"
                + "---\n\n"
                + "# " + slug + "\n\n"
                + "正文: 只做说明, 不含任何可执行片段.\n";
        Files.write(dir.resolve("SKILL.md"), md.getBytes(StandardCharsets.UTF_8));
    }

    private static String entryRelative(SkillDto dto) {
        String path = dto.getSkillFilePath();
        return path == null ? null : path.replace('\\', '/');
    }

    /** 用普通反射取 setter 名, 避免依赖 java.beans(高版本 JDK 上模块化后容易踩坑). */
    private static Set<String> setterNames(Class<?> type) {
        Set<String> out = new TreeSet<String>();
        for (java.lang.reflect.Method m : type.getDeclaredMethods()) {
            String n = m.getName();
            if (m.getParameterTypes().length == 1 && n.startsWith("set") && n.length() > 3) {
                out.add(Character.toLowerCase(n.charAt(3)) + n.substring(4));
            }
        }
        return out;
    }

    private static List<String> idsOf(List<SkillSource> sources) {
        List<String> out = new ArrayList<String>();
        for (SkillSource s : sources) out.add(s.getId());
        return out;
    }

    private static List<String> ids(SkillRegistry registry) {
        List<String> out = new ArrayList<String>();
        for (SkillDto d : registry.listAll()) out.add(d.getId());
        return out;
    }

    private static String describe(AggregateReportDto report) {
        return "raw=" + report.getRawCount() + " skill=" + report.getSkillCount()
                + " deduped=" + report.getDedupedCount() + " conflict=" + report.getConflictCount()
                + " issues=" + report.getIssueCount() + report.getIssues()
                + " sources=" + report.getSources();
    }

    private static String rootCauseChain(Throwable t) {
        List<String> out = new ArrayList<String>();
        for (Throwable cur = t; cur != null && out.size() < 6; cur = cur.getCause()) {
            out.add(cur.getClass().getSimpleName() + ": " + cur.getMessage());
        }
        Collections.reverse(out);
        return String.join(" <- ", out);
    }

    /** 模拟一个"自己把 core 扫进容器"的宿主应用. */
    @Configuration
    @ComponentScan(basePackages = "com.zifang.z.skill.core")
    static class ScannedCoreHost {
    }

    /** 模拟 README 描述的那种宿主: 扫整个库包名(含 starter 自己), 以便拿到 z-skill-admin. */
    @SpringBootApplication(scanBasePackages = "com.zifang.z.skill")
    static class WholeLibraryScanHost {
    }
}
