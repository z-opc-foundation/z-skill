package com.zifang.z.skill.starter.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.skill.api.spec.SkillFormat;
import com.zifang.z.skill.core.aggregate.SkillAggregator;
import com.zifang.z.skill.core.content.SkillContentReader;
import com.zifang.z.skill.core.discover.PlatformPresets;
import com.zifang.z.skill.core.discover.SkillSource;
import com.zifang.z.skill.core.properties.SkillProperties;
import com.zifang.z.skill.core.registry.SkillRegistry;
import com.zifang.z.skill.core.scan.SkillSecurityScanner;
import com.zifang.z.skill.core.search.SkillSearchEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * z-skill 自动装配 — 注册中心 + 聚合器 + 检索 +  REST 控制器.
 *
 * <p>来源有两处: {@code z.skill.sources[]} 显式声明的(可指向任何平台的目录或注册表 URL),
 * 以及 {@code z.skill.discover-installed-platforms=true} 时自动挂上的本机已装平台.
 *
 * <p>控制器只走 {@code @ComponentScan} 一处注册, 不再对内部包重复 @Bean,
 * 否则宿主 {@code scanBasePackages="com.zifang.z.skill"} 时会爆 BeanDefinitionOverrideException.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "z.skill.enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(SkillProperties.class)
@ComponentScan(basePackages = "com.zifang.z.skill.core.controller")
public class ZSkillAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ZSkillAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public SkillRegistry skillRegistry() {
        return new SkillRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillSecurityScanner skillSecurityScanner() {
        return new SkillSecurityScanner();
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillContentReader skillContentReader() {
        return new SkillContentReader();
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillSearchEngine skillSearchEngine(SkillRegistry registry) {
        return new SkillSearchEngine(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillAggregator skillAggregator(SkillRegistry registry, SkillProperties props,
                                           SkillSecurityScanner scanner,
                                           ObjectProvider<ObjectMapper> mapperProvider) {
        ObjectMapper mapper = mapperProvider.getIfAvailable(ObjectMapper::new);
        SkillAggregator aggregator = new SkillAggregator(registry, mapper, scanner);
        int priority = 10;
        for (SkillProperties.Source declared : props.getSources()) {
            SkillSource source = toSource(declared, props, priority);
            if (source == null) {
                log.info("z-skill: 跳过不存在的来源 {} -> {}", declared.getId(), declared.getPath());
                continue;
            }
            aggregator.addSource(source);
            priority += 10;
        }
        if (props.isDiscoverInstalledPlatforms()) {
            List<SkillSource> presets = PlatformPresets.detect(props.getUserHome(), props.getProjectRoot(), 1000);
            for (SkillSource preset : presets) {
                preset.maxDepth(props.getMaxDepth() > 0 ? preset.getMaxDepth() : props.getMaxDepth());
                aggregator.addSource(preset);
            }
            log.info("z-skill: 自动发现到 {} 个本机已装平台的 skill 目录", presets.size());
        }
        return aggregator;
    }

    private static SkillSource toSource(SkillProperties.Source declared, SkillProperties props, int defaultPriority) {
        if (!declared.isEnabled()) return null;
        String location = declared.getUrl() != null && !declared.getUrl().isEmpty()
                ? declared.getUrl() : declared.getPath();
        if (location == null || location.isEmpty()) return null;
        boolean remote = location.startsWith("http://") || location.startsWith("https://");
        if (!remote && !SkillAggregator.directoryExists(location)) return null;
        SkillFormat format = SkillFormat.fromId(declared.getFormat());
        SkillSource source = new SkillSource(declared.getId() == null ? location : declared.getId(),
                format == null ? SkillFormat.UNKNOWN : format, location,
                declared.getPriority() > 0 ? declared.getPriority() : defaultPriority)
                .maxDepth(declared.getMaxDepth())
                .categoryHint(declared.getCategory());
        return source;
    }

    @Bean
    public SkillStartupRunner skillStartupRunner(SkillAggregator aggregator, SkillProperties props) {
        return new SkillStartupRunner(aggregator, props);
    }

    /** 启动即聚合一次, 让 Marketplace 开箱有内容. */
    public static class SkillStartupRunner implements CommandLineRunner {

        private final SkillAggregator aggregator;
        private final SkillProperties props;

        public SkillStartupRunner(SkillAggregator aggregator, SkillProperties props) {
            this.aggregator = aggregator;
            this.props = props;
        }

        @Override
        public void run(String... args) {
            if (!props.isRefreshOnStartup()) {
                log.info("z-skill: refresh-on-startup=false, 等待手动 POST /skill/refresh");
                return;
            }
            try {
                aggregator.refresh();
            } catch (Exception e) {
                log.warn("z-skill: 启动聚合失败: {}", e.toString());
            }
        }
    }
}
