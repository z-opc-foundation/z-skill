package com.zifang.z.skill.adminharness;

import com.zifang.z.skill.core.registry.SkillRegistry;
import com.zifang.z.skill.core.search.SkillSearchEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 宿主侧装配替身 —— 必须放在 {@code com.zifang.z.skill.admin} 之外.
 *
 * <p>放在 admin 包里(哪怕只是 src/test 下的同名包)就会被产品侧那两次
 * {@code @ComponentScan("com.zifang.z.skill.admin.*")} 顺手扫进容器: 替身里的 {@code @Bean}
 * 会和 runner 自己注册的同类 bean 撞名, Boot 默认禁覆盖 ⇒ {@code BeanDefinitionStoreException},
 * 报出来的还是"替身非法覆盖了定义"这种看着像产品缺陷的错. 真实宿主从来不把配置类写进库的包里,
 * 所以替身也不该写进去.
 */
public final class HostConfigs {

    private HostConfigs() {
    }

    /** 宿主自己扫 admin 包那条路(core 侧的 bean 由宿主提供). */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackages = "com.zifang.z.skill.admin")
    public static class ScansAdmin {

        @Bean
        public SkillRegistry skillRegistry() {
            return new SkillRegistry();
        }

        @Bean
        public SkillSearchEngine skillSearchEngine(SkillRegistry registry) {
            return new SkillSearchEngine(registry);
        }
    }

    /** 宿主只提供 core 侧 bean, 不碰 admin 包 —— 控制面只能由那份双钥匙自动装配带进来. */
    @Configuration(proxyBeanMethods = false)
    public static class CoreBeansOnly {

        @Bean
        public SkillRegistry skillRegistry() {
            return new SkillRegistry();
        }

        @Bean
        public SkillSearchEngine skillSearchEngine(SkillRegistry registry) {
            return new SkillSearchEngine(registry);
        }
    }
}
