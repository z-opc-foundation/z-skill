package com.zifang.z.skill.adminharness;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * 手看的入口: 起一个真宿主, 让控制台页能在浏览器里被打开.
 *
 * <p>放在 test 域, 不进产物 —— 它只服务于"跑一遍真页面"这件事, 不是给宿主用的 API.
 */
public final class SkillHostLauncher {

    public static void main(String[] args) {
        new SpringApplicationBuilder(SkillHostOverHttpTest.Host.class)
                .web(WebApplicationType.SERVLET)
                .properties("z.skill.enabled=true", "z.skill.expose-admin=true",
                        "server.port=" + System.getProperty("server.port", "18099"))
                .run(args);
    }

    private SkillHostLauncher() {
    }
}
