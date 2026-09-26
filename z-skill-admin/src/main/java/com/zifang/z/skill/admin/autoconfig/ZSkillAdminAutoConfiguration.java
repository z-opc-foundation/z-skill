package com.zifang.z.skill.admin.autoconfig;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * z-skill-admin 控制面装配 — 只有把本模块显式加进 classpath, 且 {@code z.skill.enabled=true}
 * 与 {@code z.skill.expose-admin=true} 同时开, 才注册.
 *
 * <p>两道门是故意的: z-skill-starter 不依赖本模块(默认谁都拉不到控制面), 加了依赖还要开属性.
 * 聚合出来的第三方 SKILL.md 描述与本机路径属于不该在没鉴权的端口上裸露的内容.
 *
 * <p>{@code z.skill.enabled} 也进条件, 是因为控制面依赖 core 的 {@code SkillRegistry}/{@code SkillSearchEngine}
 * bean — 只关总开关而开控制面会让容器以 UnsatisfiedDependency 起不来, 报错还看不出缺了哪个开关.
 *
 * <p>只扫 controller/service 两个叶子包, 不扫 {@code com.zifang.z.skill.admin} 本身 —
 * 否则本装配类会被自己的 component scan 再注册一遍.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"z.skill.enabled", "z.skill.expose-admin"}, havingValue = "true",
        matchIfMissing = false)
@ComponentScan(basePackages = {
        "com.zifang.z.skill.admin.controller",
        "com.zifang.z.skill.admin.service"
})
public class ZSkillAdminAutoConfiguration {
}
