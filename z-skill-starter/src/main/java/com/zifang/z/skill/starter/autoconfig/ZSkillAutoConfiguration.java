package com.zifang.z.skill.starter.autoconfig;

import com.zifang.z.skill.core.controller.SkillController;
import com.zifang.z.skill.core.loader.SkillLoader;
import com.zifang.z.skill.core.properties.SkillProperties;
import com.zifang.z.skill.core.registry.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

/**
 * z-skill 自动装配 — 注册 SkillRegistry + SkillLoader + SkillController.
 *
 * <p>如果配置了 z.skill.scan-dir, 启动时扫描该目录加载本地 Skill.
 */
@Configuration
@EnableConfigurationProperties(SkillProperties.class)
public class ZSkillAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ZSkillAutoConfiguration.class);

    @Bean
    public SkillRegistry skillRegistry() {
        return new SkillRegistry();
    }

    @Bean
    public SkillLoader skillLoader(SkillRegistry registry) {
        return new SkillLoader(registry);
    }

    @Bean
    public SkillController skillController(SkillRegistry registry) {
        return new SkillController(registry);
    }

    @Bean
    public SkillStartupRunner skillStartupRunner(SkillLoader loader, SkillProperties props) {
        return new SkillStartupRunner(loader, props);
    }

    /**
     * 启动 runner: 在应用启动时扫描本地 Skill 目录.
     */
    public static class SkillStartupRunner implements org.springframework.boot.CommandLineRunner {
        private final SkillLoader loader;
        private final SkillProperties props;

        public SkillStartupRunner(SkillLoader loader, SkillProperties props) {
            this.loader = loader;
            this.props = props;
        }

        @Override
        public void run(String... args) {
            String dir = props.getScanDir();
            if (dir != null && !dir.isEmpty()) {
                int n = loader.scanDirectory(new File(dir));
                log.info("z-skill: loaded {} skills from {}", n, dir);
            }
        }
    }
}