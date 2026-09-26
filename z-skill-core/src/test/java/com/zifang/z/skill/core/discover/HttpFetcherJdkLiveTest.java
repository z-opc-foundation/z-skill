package com.zifang.z.skill.core.discover;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zifang.z.skill.api.dto.AggregateReportDto;
import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.dto.SkillIssueDto;
import com.zifang.z.skill.api.spec.SkillFormat;
import com.zifang.z.skill.core.aggregate.SkillAggregator;
import com.zifang.z.skill.core.registry.SkillRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 远端取数的那段真代码, 在真 socket 上跑一遍.
 *
 * <p>聚合器层的远端测试全部塞的是 {@code StubFetcher}(所以它们从不经受 {@code HttpFetcher.Jdk}),
 * 而 {@code SkillAggregator} 默认实例化的恰恰是 {@code new HttpFetcher.Jdk()} —— 也就是说
 * "聚合各家远端注册表"这条广告, 之前唯一没被量过的环节就是它自己发出去的那个请求:
 * 状态码判定、8MB 上限、超时、跳转、字符集, 全在桩后面. 这里把它们挪到真端口上逐个测.
 *
 * <p>字符集那一条尤其要真跑: 桩给的是 Java 字符串, 只有真字节流才会暴露"按什么解码"这个决定 ——
 * 注册表响应按规范是 UTF-8, 第三方常常不回 {@code charset}, 所以这里服务端故意只声明
 * {@code application/json} 并回一段中文描述.
 */
public class HttpFetcherJdkLiveTest {

    private static HttpServer server;
    private static String base;
    /** 服务端故意没回 charset 的那段中文, 用来钉"按 UTF-8 解码"这个决定. */
    private static final String CJK = "查询天气与预报，含中文说明。";

    @BeforeAll
    public static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        serve("/index.json", 200, jsonBytes(registryIndex()));
        serve("/sh.json", 200, jsonBytes("{\"data\":[{\"slug\":\"api-alpha\","
                + "\"description\":\"Alpha over the wire\",\"installs\":5,\"source\":\"acme/skills\"}]}"));
        serve("/no-charset", 200, ("{\"skills\":[{\"name\":\"cjk-only\",\"description\":\"" + CJK
                + "\",\"files\":[\"SKILL.md\"]}]}").getBytes(StandardCharsets.UTF_8));
        serve("/boom", 500, "内部错误".getBytes(StandardCharsets.UTF_8));
        serve("/not-json", 200, "<html>登录页, 不是索引</html>".getBytes(StandardCharsets.UTF_8));
        serve("/huge", 200, bytesOfSize(9 * 1024 * 1024));
        serve("/slow", 200, new Bytes() {
            @Override
            public byte[] get() {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return jsonBytes("{}");
            }
        });
        serve("/moved", 302, new byte[0]);
        // 跨协议的跳转 HttpURLConnection 不跟: 这时候"状态码不是 2xx 就失败"这一句才有活干 ——
        // 少了它, 取到的是那张跳转提示页本身, 聚合器会把它当成注册表响应去解析(比 404 更难查).
        redirect("/cross-proto", 307, "https://registry.invalid/index.json",
                "<html>307 Temporary Redirect, 这不是索引</html>".getBytes(StandardCharsets.UTF_8));
        server.start();
    }

    @AfterAll
    public static void stopServer() throws InterruptedException {
        if (server == null) {
            return;
        }
        // stop() 在这台机器的分叉 JVM 里挂过(卡在 preClose0, 整个 fork 永久不退出), 所以收尾要有上限.
        final HttpServer toStop = server;
        server = null;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                toStop.stop(0);
            }
        });
        t.setDaemon(true);
        t.start();
        t.join(5000);
    }

    // ---------- 传输层本身 ----------

    @Test
    public void fetchesBodyTextOverRealSocket() throws IOException {
        String body = new HttpFetcher.Jdk().get(base + "/index.json");
        assertTrue(body.contains("weather-lookup"), "真 socket 上没取到响应体: " + body.substring(0, 80));
    }

    @Test
    public void decodesUtf8EvenWhenServerOmitsCharset() throws IOException {
        // 桩在这一条上是拿不到的: 只有真字节流才有"按什么解码"这个决定.
        // 但这一条只在"跑测试的 JVM 默认字符集不是 UTF-8"时才有牙: 本机实测
        // defaultCharset=UTF-8/file.encoding=UTF-8, 所以把主代码里的 StandardCharsets.UTF_8 换成
        // 平台默认, 这一条在本机仍然绿(等价变异). 它的价值在非 UTF-8 宿主机上, 别把它当已证死.
        String body = new HttpFetcher.Jdk().get(base + "/no-charset");
        assertTrue(body.contains(CJK), "服务端没回 charset 时中文解码坏了(说明解码字符集随环境漂): " + body);
    }

    @Test
    public void non2xxBecomesIOExceptionNamingStatusAndUrl() {
        // 实测: 把主代码里那句显式状态码判定摘掉, 这一条**仍然绿** —— HttpURLConnection 对 500
        // 自己就抛 "Server returned HTTP response code: 500 for URL: ..."。所以这一条钉的是
        // "失败要带状态码和 URL"这个对外口径, 不是那句判定本身; 判定本身的猎物在下面那条 307 上。
        HttpFetcher.Jdk fetcher = new HttpFetcher.Jdk();
        try {
            fetcher.get(base + "/boom");
            fail("500 不该被当成一次成功的取数");
        } catch (IOException e) {
            String msg = String.valueOf(e.getMessage());
            assertTrue(msg.contains("500"), "消息里没有状态码, 报告里就只能看到一句空话: " + msg);
            assertTrue(msg.contains("/boom"), "消息里没有出错的那个 URL: " + msg);
        }
    }

    @Test
    public void oversizedBodyIsRefusedNotBuffered() {
        try {
            new HttpFetcher.Jdk().get(base + "/huge");
            fail("9MB 响应必须被 8MB 上限挡住 —— 第三方注册表可以是任意的");
        } catch (IOException e) {
            assertTrue(String.valueOf(e.getMessage()).contains("8MB"),
                    "挡住的不是那条上限, 而是别的什么: " + e.getMessage());
        }
    }

    @Test
    public void readTimeoutBoundsTheCall() {
        HttpFetcher.Jdk fetcher = new HttpFetcher.Jdk(150);
        long start = System.currentTimeMillis();
        try {
            fetcher.get(base + "/slow");
            fail("服务端拖 1.5s 而超时设的 150ms, 取数不该就这样返回");
        } catch (IOException e) {
            long took = System.currentTimeMillis() - start;
            assertTrue(took < 3000, "超时没起作用, 一次远端聚合可以拖死整个 refresh: " + took + "ms");
            assertTrue(causedBy(e, SocketTimeoutException.class),
                    "超时后拿到的是别的失败(读侧要按它分级): " + e + " / " + e.getCause());
        }
    }

    @Test
    public void followsRedirect() throws IOException {
        String body = new HttpFetcher.Jdk().get(base + "/moved");
        assertTrue(body.contains("weather-lookup"), "302 没跟到索引, 一个挂在 CDN 后面的注册表就聚不进来: "
                + body.substring(0, Math.min(80, body.length())));
    }

    @Test
    public void unfollowedRedirectFailsInsteadOfHandingOverTheRedirectPage() {
        try {
            String body = new HttpFetcher.Jdk().get(base + "/cross-proto");
            fail("跨协议跳转没跟, 取回来的必须是失败而不是那张提示页; 实际拿到 " + body.length() + " 字节: "
                    + body.substring(0, Math.min(80, body.length())));
        } catch (IOException e) {
            assertTrue(String.valueOf(e.getMessage()).contains("307"),
                    "失败理由要把状态码说清楚, 否则第三方排查时只知道\"取不到\": " + e.getMessage());
        }
    }

    // ---------- 聚合器默认就用这个取数器 ----------

    @Test
    public void aggregatorIngestsRemoteRegistryWithItsDefaultFetcher() {
        SkillRegistry registry = new SkillRegistry();
        SkillAggregator aggregator = new SkillAggregator(registry, new ObjectMapper());
        // 关键: 一次 setHttpFetcher 都不调, 走的就是主代码里那句 new HttpFetcher.Jdk()
        aggregator.addSource(new SkillSource("acme-registry", SkillFormat.REGISTRY_INDEX,
                base + "/index.json", 10));

        AggregateReportDto report = aggregator.refresh();

        assertEquals(1, report.getSourceCount());
        assertNull(sourceIssue(report, "acme-registry", "fetch-failed"), "远端来源被记成取数失败");
        assertTrue(report.getSkillCount() >= 1, "远端索引一条都没进目录, 报告=" + report.getRawCount() + "/"
                + report.getSkillCount());
        SkillDto entry = bySlug(registry, "weather-lookup");
        assertNotNull(entry, "索引里那条没落到目录: " + ids(registry));
        assertTrue(String.valueOf(entry.getOrigin()).startsWith("http://"),
                "远端来源的 origin 要照给(它才是客户端能用的地址): " + entry.getOrigin());
        assertEquals(SkillFormat.REGISTRY_INDEX, entry.getFormat());
    }

    @Test
    public void oneDeadRemoteSourceCannotTakeDownTheGoodOne() {
        SkillRegistry registry = new SkillRegistry();
        SkillAggregator aggregator = new SkillAggregator(registry, new ObjectMapper());
        aggregator.addSource(new SkillSource("dead", SkillFormat.REGISTRY_INDEX, base + "/boom", 10));
        aggregator.addSource(new SkillSource("alive", SkillFormat.SKILLS_SH_API, base + "/sh.json", 20));

        AggregateReportDto report = aggregator.refresh();

        assertTrue(ids(registry).toString().contains("api-alpha"),
                "一个来源 500 就把整次聚合带走了: 目录=" + ids(registry));
        SkillIssueDto issue = sourceIssue(report, "dead", "fetch-failed");
        assertNotNull(issue, "坏来源没留下 fetch-failed 账目, 体检报告等于没体检: " + codes(report));
        assertTrue(String.valueOf(issue.getMessage()).contains("500"),
                "记账没带上失败原因: " + issue.getMessage());
    }

    @Test
    public void remotePayloadThatIsNotJsonIsChalkedUpNotThrown() {
        SkillRegistry registry = new SkillRegistry();
        SkillAggregator aggregator = new SkillAggregator(registry, new ObjectMapper());
        // 第三方端点最常见的"200 但给的是 HTML 登录页"
        aggregator.addSource(new SkillSource("loginwall", SkillFormat.REGISTRY_INDEX,
                base + "/not-json", 10));

        AggregateReportDto report = aggregator.refresh();

        assertNotNull(sourceIssue(report, "loginwall", "payload-not-json"),
                "非 JSON 响应必须单独记一笔, 否则它与\"注册表本来就是空的\"长得一模一样: " + codes(report));
        assertEquals(0, registry.listAll().size());
    }

    @Test
    public void remoteChineseDescriptionSurvivesTheWholePipeline() {
        SkillRegistry registry = new SkillRegistry();
        SkillAggregator aggregator = new SkillAggregator(registry, new ObjectMapper());
        aggregator.addSource(new SkillSource("cjk", SkillFormat.REGISTRY_INDEX, base + "/no-charset", 10));
        aggregator.refresh();

        SkillDto entry = bySlug(registry, "cjk-only");
        assertNotNull(entry, "带中文的那条没进目录: " + ids(registry));
        assertEquals(CJK, entry.getDescription(), "描述经字节流→解析→DTO 之后不该变样");
    }

    // ---------- helpers ----------

    private interface Bytes {
        byte[] get();
    }

    private static void serve(String path, final int status, final byte[] body) {
        serve(path, status, new Bytes() {
            @Override
            public byte[] get() {
                return body;
            }
        });
    }

    private static void serve(String path, final int status, final Bytes body) {
        server.createContext(path, new com.sun.net.httpserver.HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                if (status == 302) {
                    ex.getResponseHeaders().add("Location", base + "/index.json");
                    ex.sendResponseHeaders(302, -1);
                    ex.close();
                    return;
                }
                byte[] payload = body.get();
                // 故意只声明 application/json 不带 charset: 真实注册表大多就这样
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
                if (payload.length > 0) {
                    try (OutputStream out = ex.getResponseBody()) {
                        out.write(payload);
                    }
                }
                ex.close();
            }
        });
    }

    private static void redirect(final String path, final int status, final String location, final byte[] body) {
        server.createContext(path, new com.sun.net.httpserver.HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                ex.getResponseHeaders().add("Location", location);
                ex.getResponseHeaders().add("Content-Type", "text/html");
                ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    try (OutputStream out = ex.getResponseBody()) {
                        out.write(body);
                    }
                }
                ex.close();
            }
        });
    }

    private static byte[] jsonBytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] bytesOfSize(int size) {
        byte[] big = new byte[size];
        Arrays.fill(big, (byte) 'a');
        return big;
    }

    private static String registryIndex() {
        return "{\"skills\":[{"
                + "\"name\":\"weather-lookup\","
                + "\"description\":\"Look up current weather and forecasts.\","
                + "\"files\":[\"SKILL.md\",\"scripts/fetch.py\",\"references/api.md\"],"
                + "\"metadata\":{\"version\":\"1.0.0\",\"author\":\"acme\"},"
                + "\"installs\":4210}]}";
    }

    private static boolean causedBy(Throwable t, Class<?> kind) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (kind.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> ids(SkillRegistry registry) {
        List<String> out = new ArrayList<String>();
        for (SkillDto d : registry.listAll()) {
            out.add(d.getId());
        }
        return out;
    }

    private static SkillDto bySlug(SkillRegistry registry, String slug) {
        for (SkillDto d : registry.listAll()) {
            if (slug.equals(d.getSlug())) {
                return d;
            }
        }
        return null;
    }

    private static List<String> codes(AggregateReportDto report) {
        List<String> out = new ArrayList<String>();
        for (SkillIssueDto i : report.getIssues()) {
            out.add(i.getSource() + ":" + i.getCode());
        }
        return out;
    }

    private static SkillIssueDto sourceIssue(AggregateReportDto report, String sourceId, String code) {
        for (SkillIssueDto i : report.getIssues()) {
            if (code.equals(i.getCode()) && sourceId.equals(i.getSource())) {
                return i;
            }
        }
        return null;
    }
}
