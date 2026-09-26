package com.zifang.z.skill.adminharness;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 只开总开关、不开 {@code z.skill.expose-admin} 的宿主: 控制面在真 HTTP 上也必须拿不到.
 *
 * <p>装配期"容器里没有这个 bean"({@code AdminControllerExposureTest} 已钉)与"打过去是 404"
 * 不是一回事 —— 后者才是聚合结果里那些第三方描述与本机绝对路径不会在没鉴权的端口上裸露的证据.
 * 同一把钥匙不能顺手把兼容面也关掉, 所以 compat 那半边一起断言.
 */
@SpringBootTest(classes = AdminGateClosedOverHttpTest.Host.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "z.skill.enabled=true")
public class AdminGateClosedOverHttpTest {

    @EnableAutoConfiguration
    @Configuration(proxyBeanMethods = false)
    public static class Host {
    }

    @Autowired
    private TestRestTemplate http;
    @LocalServerPort
    private int port;

    @Test
    void adminRestIsAbsentButCompatStaysOpen() {
        ResponseEntity<String> overview = http.getForEntity(
                "http://127.0.0.1:" + port + "/skill/admin/overview", String.class);
        assertEquals(404, overview.getStatusCodeValue(),
                "没开 expose-admin 却能用 HTTP 拿到控制面: " + overview.getBody());

        ResponseEntity<String> sources = http.getForEntity(
                "http://127.0.0.1:" + port + "/skill/admin/sources", String.class);
        assertEquals(404, sources.getStatusCodeValue(), "控制面漏了一个没被门挡住的端点");

        ResponseEntity<Map> search = http.getForEntity(
                "http://127.0.0.1:" + port + "/skill/compat/skills-sh/api/search?q=pdf", Map.class);
        assertEquals(200, search.getStatusCodeValue(), "关控制面不该顺带关掉兼容输出");
        assertFalse(String.valueOf(search.getBody()).contains("skillFilePath"),
                "兼容输出里不该出现本机绝对路径: " + search.getBody());
    }
}
