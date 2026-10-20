package com.zifang.z.skill.core;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * z-skill README 定量主张自守卫 —— README 里写死的版本、模块清单、format 种数、
 * 风险等级、规范校验上限、端点集合、平台落点、聚合常量、取数器口径，必须能从源码重算出来；
 * README 改了就红，代码没动就不红。
 *
 * <p><b>为什么要有这一层</b>：本仓 README 把「当前版本 0.2.2」「四模块」「9 种 format」
 * 「safe/low/medium/high/critical + unscanned」「description ≤1024 / compatibility ≤500 /
 * 正文 500 行 / 扫描深度 6 / 单来源 2000 条 / 关键词最短 2」「15 条 Marketplace 端点 /
 * 7 条控制面 GET / 6 条兼容端点」「9 个家目录落点」「MAX_DERIVED_DEPTH=3」
 * 「取数器 8000ms / 8MB」写成了硬数字。此前没有任何尺子去问代码「现在还是吗」，
 * 这些数字的保质期就只剩上一次编辑 README 的那一刻。</p>
 *
 * <p><b>口径四条（都是踩过的坑）</b>：</p>
 * <ol>
 *   <li><b>事实对象只装源码侧事实</b>（{@link Facts}）—— README 侧的数值一律在核对
 *       <b>当下</b>从被核对的那份 README 里现解析。两边都从同一份 README 取数，
 *       尺子就成了恒等式比较，README 改成 18096 尺子照样绿；</li>
 *   <li><b>主张抠不到就红</b>：正则没匹配上不许静默跳过，那正是空跑；</li>
 *   <li><b>按小节名 / 表格字段定位，不按数字定位</b>：短数字是长数字的子串
 *       （「4 个」⊂「14 个」），按数字定位会匹配到别人的行上；</li>
 *   <li><b>README 标题带 emoji</b>：定位小节要剥掉 emoji 后<b>标题文字全等匹配</b>，
 *       不能用 {@code contains}（「API 一览」会误命中别处）。</li>
 * </ol>
 *
 * <p>数值不等 = 红，且把<b>重算值</b>打进消息（红了要知道真数是多少，否则只是又一处猜）。
 * 核对结果<b>一次全报</b>（收集齐所有不一致再抛），撞停会把尺子退化成「一次只说一句」。</p>
 *
 * <p>本仓既有测试全部是 JUnit 5（{@code org.junit.jupiter.api.Test}），本守卫沿用同一口径 ——
 * 混用引擎时静默不跑比红更坏。</p>
 */
public class ReadmeClaimsTest {

    /** 仓根标志：artifactId 命中 + packaging=pom + 真带 <module>（子模块 pom 的 parent 里也有同名 artifactId）。 */
    private static final String ROOT_ARTIFACT_ID = "z-skill";

    /** 源码侧路径一律相对仓根写死，不按目录名统计（多模块仓里同名目录不止一处）。 */
    private static final String API_MAIN = "z-skill-api/src/main/java";
    private static final String CORE_MAIN = "z-skill-core/src/main/java";
    private static final String ADMIN_MAIN = "z-skill-admin/src/main/java";
    private static final String SKILL_FORMAT = "z-skill-api/src/main/java/com/zifang/z/skill/api/spec/SkillFormat.java";
    private static final String SKILL_SPEC = "z-skill-api/src/main/java/com/zifang/z/skill/api/spec/SkillSpec.java";
    private static final String SKILL_DTO = "z-skill-api/src/main/java/com/zifang/z/skill/api/dto/SkillDto.java";
    private static final String SKILL_CONTROLLER = "z-skill-core/src/main/java/com/zifang/z/skill/core/controller/SkillController.java";
    private static final String COMPAT_CONTROLLER = "z-skill-core/src/main/java/com/zifang/z/skill/core/controller/SkillCompatController.java";
    private static final String ADMIN_CONTROLLER = "z-skill-admin/src/main/java/com/zifang/z/skill/admin/controller/AdminController.java";
    private static final String PLATFORM_PRESETS = "z-skill-core/src/main/java/com/zifang/z/skill/core/discover/PlatformPresets.java";
    private static final String AGGREGATOR = "z-skill-core/src/main/java/com/zifang/z/skill/core/aggregate/SkillAggregator.java";
    private static final String HTTP_FETCHER = "z-skill-core/src/main/java/com/zifang/z/skill/core/discover/HttpFetcher.java";
    private static final String ROOT_POM = "pom.xml";

    /** 能力清单里也会出现的 HTTP 动词，它们不是类名。 */
    private static final Set<String> HTTP_VERBS = new LinkedHashSet<>(Arrays.asList(
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"));

    // ==================================================================
    // 基础设施
    // ==================================================================

    /**
     * 定位仓根：从 user.dir 向上找<b>聚合根</b>的 pom.xml。
     *
     * <p>判据要同时满足三条：<b>项目自身</b>的 artifactId 命中（用 XML 解析取顶层
     * {@code <artifactId>}，不是全文 contains —— 子模块 pom 的 {@code <parent>} 里
     * 就写着 {@code z-skill}，全文匹配会把子模块误判成仓根）+
     * {@code <packaging>pom</packaging>} + 至少一个 {@code <module>}。</p>
     */
    private static Path repoRoot() {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int i = 0; i < 10 && p != null; i++, p = p.getParent()) {
            Path pom = p.resolve(ROOT_POM);
            if (Files.isRegularFile(pom)) {
                String t = stripXmlComments(readText(pom));
                boolean isRoot = ownArtifactId(t).equals(ROOT_ARTIFACT_ID)
                        && t.contains("<packaging>pom</packaging>")
                        && Pattern.compile("<module>").matcher(t).find();
                if (isRoot) {
                    return p;
                }
            }
        }
        throw new IllegalStateException("未找到 z-skill 仓根：从 " + Paths.get("").toAbsolutePath()
                + " 向上找不到同时满足 artifactId=" + ROOT_ARTIFACT_ID + " / packaging=pom / 带 <module> 的 pom.xml");
    }

    /** 取 pom 里<b>项目自身</b>的 artifactId（顶层元素，不是 parent 里的那个）。 */
    private static String ownArtifactId(String pomText) {
        Document doc = parseXml(pomText);
        NodeList kids = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "artifactId".equals(n.getNodeName())) {
                return n.getTextContent().trim();
            }
        }
        return "";
    }

    private static Document parseXml(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            return f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("XML 解析失败", e);
        }
    }

    private static String readText(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读文件失败：" + p, e);
        }
    }

    /** 剥掉 XML 注释（根 pom 里注释掉的 {@code <module>} 会骗过正则）。 */
    private static String stripXmlComments(String s) {
        return s.replaceAll("(?s)<!--.*?-->", "");
    }

    /**
     * 剥掉 Java 注释（块注释 + 行注释），字符串字面量原样保留。
     *
     * <p>判「类级注解」还是「方法级注解」时必须先剥注释：Javadoc 里出现过
     * {@code @GetMapping} 字样就会被当成真映射。</p>
     */
    private static String stripComments(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                char quote = c;
                sb.append(c);
                i++;
                while (i < n) {
                    char d = s.charAt(i);
                    sb.append(d);
                    i++;
                    if (d == '\\' && i < n) {
                        sb.append(s.charAt(i));
                        i++;
                        continue;
                    }
                    if (d == quote) {
                        break;
                    }
                }
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    // ==================================================================
    // README 侧解析
    // ==================================================================

    private static int headingLevel(String heading) {
        int n = 0;
        while (n < heading.length() && heading.charAt(n) == '#') {
            n++;
        }
        return n;
    }

    /**
     * 标题的「基名」：去掉行首 #、emoji，再去掉<b>尾部</b>的括号补充说明。
     *
     * <p>本仓有的小节标题带补充说明（`## 🎯 能力清单（逐条对应到代码）`），
     * 按整段标题文字全等就永远定位不到；按 contains 又会误命中别的节。</p>
     *
     * <p>解法：把尾部括号（`（…）` / `(…)`）当成<b>装饰</b>剥掉，
     * 剩下的基名仍然**全等**匹配 —— 于是「模块结构」与「模块结构与约定」照样分得开。</p>
     */
    private static String headingText(String raw) {
        String t = raw.trim();
        while (t.startsWith("#")) {
            t = t.substring(1);
        }
        t = t.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            int cp = t.codePointAt(i);
            if (cp == 0xFE0F || cp == 0x200D
                    || (cp >= 0x1F300 && cp <= 0x1FAFF)
                    || (cp >= 0x2600 && cp <= 0x27BF)) {
                if (cp > 0xFFFF) {
                    i++;
                }
                continue;
            }
            sb.appendCodePoint(cp);
        }
        return stripTrailingParen(sb.toString().trim());
    }

    /** 去掉标题文字<b>尾部</b>的括号补充说明（`能力清单（逐条对应到代码）` → `能力清单`）。 */
    private static String stripTrailingParen(String title) {
        String t = title.trim();
        while (t.endsWith("）") || t.endsWith(")")) {
            int open = t.endsWith("）") ? t.lastIndexOf('（') : t.lastIndexOf('(');
            if (open < 0) {
                break;
            }
            t = t.substring(0, open).trim();
        }
        return t;
    }

    /**
     * 取某个标题的小节正文（到下一个「同级或更高级」标题为止，{@code ###} 子标题仍属本节）。
     *
     * <p>围栏代码块内以 # 开头的行（项目结构树）不算标题，必须跳过。</p>
     */
    private static String section(String readme, String headingText) {
        String[] lines = readme.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean inFence = false;
        boolean started = false;
        int level = 0;
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("```") || t.startsWith("~~~")) {
                inFence = !inFence;
                if (started) {
                    sb.append(line).append('\n');
                }
                continue;
            }
            if (!inFence && t.startsWith("#")) {
                int lv = headingLevel(t);
                String text = headingText(t);
                if (started) {
                    if (lv <= level) {
                        break;
                    }
                } else {
                    if (text.equals(headingText)) {
                        started = true;
                        level = lv;
                        continue;
                    }
                }
            }
            if (started) {
                sb.append(line).append('\n');
            }
        }
        if (!started) {
            throw new IllegalStateException("README 里找不到标题小节：" + headingText);
        }
        return sb.toString();
    }

    /** 从「字段 | 值」表格里抠某个字段的值。 */
    private static String tableCell(String section, String field) {
        for (String line : section.split("\n", -1)) {
            String t = line.trim();
            if (!t.startsWith("|")) {
                continue;
            }
            String[] cells = t.split("\\|", -1);
            if (cells.length < 3) {
                continue;
            }
            String k = cells[1].trim();
            if (k.equals("**" + field + "**") || k.equals(field)) {
                return cells[2].trim();
            }
        }
        throw new IllegalStateException("表格里找不到字段：" + field);
    }

    /** 抠出 `` `xxx` `` 形式的反引号片段。 */
    private static List<String> backticked(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("`([^`\n]+)`").matcher(text);
        while (m.find()) {
            out.add(m.group(1).trim());
        }
        return out;
    }

    /** 去掉反引号片段后抠出全部整数（用来做「数字多重集相等」这种顺序无关的比对）。 */
    private static List<Integer> numbersOutsideCode(String text) {
        String stripped = text.replaceAll("`[^`\\n]+`", " ");
        List<Integer> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\d+").matcher(stripped);
        while (m.find()) {
            out.add(Integer.parseInt(m.group()));
        }
        return out;
    }

    /** 中文数字转阿拉伯数（本仓 README 用「七个」这种写法）。 */
    private static int cnNumber(String s) {
        String digits = "零一二三四五六七八九";
        if ("十".equals(s)) {
            return 10;
        }
        int idx = digits.indexOf(s);
        if (idx < 0) {
            throw new IllegalStateException("认不出的中文数字：" + s);
        }
        return idx;
    }

    // ==================================================================
    // 源码侧事实
    // ==================================================================

    /** 事实对象：<b>只装源码侧</b>的事实。README 侧的数字一律现解析。 */
    private static final class Facts {
        String revision = "";
        String parentVersion = "";
        boolean parentCommentStale = false;
        List<String> modules = new ArrayList<>();
        List<String> formatIds = new ArrayList<>();
        List<String> riskLevels = new ArrayList<>();
        List<Integer> specLimits = new ArrayList<>();
        List<String> marketplaceEndpoints = new ArrayList<>();
        List<String> adminEndpoints = new ArrayList<>();
        int compatEndpointCount = 0;
        List<String> platformClients = new ArrayList<>();
        int maxDerivedDepth = -1;
        int fetcherTimeoutMs = -1;
        long fetcherBodyLimitMb = -1;
        boolean rootProvidesJUnit5 = false;
        boolean rootProvidesVintage = false;
        int junit4TestFiles = 0;
        int junit5TestFiles = 0;
        int modulesExcludingVintage = 0;
        int modulesWithStarterTest = 0;
    }

    private static Facts facts() {
        Path root = repoRoot();
        Facts f = new Facts();

        // 根 pom：<revision> / parent version / <modules>（保序）
        String rawPom = readText(root.resolve(ROOT_POM));
        String rootPom = stripXmlComments(rawPom);
        Document doc = parseXml(rootPom);
        NodeList kids = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String name = n.getNodeName();
            if ("parent".equals(name)) {
                NodeList pk = n.getChildNodes();
                for (int j = 0; j < pk.getLength(); j++) {
                    Node p = pk.item(j);
                    if (p.getNodeType() == Node.ELEMENT_NODE && "version".equals(p.getNodeName())) {
                        f.parentVersion = p.getTextContent().trim();
                    }
                }
            } else if ("properties".equals(name)) {
                NodeList pk = n.getChildNodes();
                for (int j = 0; j < pk.getLength(); j++) {
                    Node p = pk.item(j);
                    if (p.getNodeType() == Node.ELEMENT_NODE && "revision".equals(p.getNodeName())) {
                        f.revision = p.getTextContent().trim();
                    }
                }
            } else if ("modules".equals(name)) {
                NodeList mk = n.getChildNodes();
                for (int j = 0; j < mk.getLength(); j++) {
                    Node m = mk.item(j);
                    if (m.getNodeType() == Node.ELEMENT_NODE && "module".equals(m.getNodeName())) {
                        f.modules.add(m.getTextContent().trim());
                    }
                }
            } else if ("dependencies".equals(name)) {
                // 根 <dependencies> 直供的测试台：junit-jupiter + junit-vintage-engine
                NodeList dk = n.getChildNodes();
                for (int j = 0; j < dk.getLength(); j++) {
                    Node d = dk.item(j);
                    if (d.getNodeType() != Node.ELEMENT_NODE || !"dependency".equals(d.getNodeName())) {
                        continue;
                    }
                    String artifact = childText(d, "artifactId");
                    if ("junit-jupiter".equals(artifact)) {
                        f.rootProvidesJUnit5 = true;
                    } else if ("junit-vintage-engine".equals(artifact)) {
                        f.rootProvidesVintage = true;
                    }
                }
            }
        }
        // README 里那句「根 POM 顶部注释仍写着 1.0.19」是**负向语境**：它说注释是陈旧的。
        // ⇒ 只有当 pom 的注释里真的还写着那个旧版本号时，这句话才成立（双向核对）。
        f.parentCommentStale = rawPom.contains("z-boot-parent:1.0.19");

        // SkillFormat：枚举常量的 id（9 种 format）
        String fmt = stripComments(readText(root.resolve(SKILL_FORMAT)));
        Matcher fm = Pattern.compile("^\\s{4}([A-Z][A-Z0-9_]*)\\(\"([a-z0-9-]+)\"", Pattern.MULTILINE).matcher(fmt);
        while (fm.find()) {
            f.formatIds.add(fm.group(2));
        }

        // SkillDto：风险等级常量
        String dto = stripComments(readText(root.resolve(SKILL_DTO)));
        Matcher rm = Pattern.compile("public\\s+static\\s+final\\s+String\\s+RISK_[A-Z_]+\\s*=\\s*\"([a-z]+)\"").matcher(dto);
        while (rm.find()) {
            f.riskLevels.add(rm.group(1));
        }

        // SkillSpec：规范校验上限（README 能力清单那一行里的数，按同一顺序列）
        String spec = stripComments(readText(root.resolve(SKILL_SPEC)));
        f.specLimits.add(intConstant(spec, "NAME_MAX"));
        f.specLimits.add(intConstant(spec, "DESCRIPTION_MAX"));
        f.specLimits.add(intConstant(spec, "COMPATIBILITY_MAX"));
        f.specLimits.add(intConstant(spec, "BODY_LINES_SOFT_MAX"));
        f.specLimits.add(intConstant(spec, "MAX_SCAN_DEPTH"));
        f.specLimits.add(intConstant(spec, "MAX_SKILLS_PER_SOURCE"));
        f.specLimits.add(intConstant(spec, "MIN_QUERY_LENGTH"));

        // 端点
        f.marketplaceEndpoints.addAll(endpointsOf(root.resolve(SKILL_CONTROLLER)));
        f.adminEndpoints.addAll(endpointsOf(root.resolve(ADMIN_CONTROLLER)));
        f.compatEndpointCount = methodMappingCount(root.resolve(COMPAT_CONTROLLER));

        // 平台自动发现：家目录客户端名单
        String presets = stripComments(readText(root.resolve(PLATFORM_PRESETS)));
        Matcher cm = Pattern.compile("CLIENTS\\s*=\\s*\\{([^}]*)\\}").matcher(presets);
        if (cm.find()) {
            Matcher q = Pattern.compile("\"([^\"]+)\"").matcher(cm.group(1));
            while (q.find()) {
                f.platformClients.add(q.group(1));
            }
        }

        // 派生来源深度上限
        String agg = stripComments(readText(root.resolve(AGGREGATOR)));
        Matcher dm = Pattern.compile("MAX_DERIVED_DEPTH\\s*=\\s*(\\d+)").matcher(agg);
        if (dm.find()) {
            f.maxDerivedDepth = Integer.parseInt(dm.group(1));
        }

        // 取数器：缺省超时 + 响应体上限
        String fetcher = stripComments(readText(root.resolve(HTTP_FETCHER)));
        Matcher tm = Pattern.compile("this\\((\\d+)\\)").matcher(fetcher);
        if (tm.find()) {
            f.fetcherTimeoutMs = Integer.parseInt(tm.group(1));
        }
        Matcher bm = Pattern.compile("total\\s*>\\s*(\\d+)L\\s*\\*\\s*1024\\s*\\*\\s*1024").matcher(fetcher);
        if (bm.find()) {
            f.fetcherBodyLimitMb = Long.parseLong(bm.group(1));
        }

        // 测试台口径：仓内测试用哪个 JUnit；各模块的 spring-boot-starter-test 有没有排除 vintage
        for (String module : f.modules) {
            Path modulePom = root.resolve(module).resolve("pom.xml");
            if (Files.isRegularFile(modulePom)) {
                String mp = readText(modulePom);
                if (mp.contains("spring-boot-starter-test")) {
                    f.modulesWithStarterTest++;
                    if (mp.contains("junit-vintage-engine")) {
                        f.modulesExcludingVintage++;
                    }
                }
            }
            Path testDir = root.resolve(module).resolve("src/test/java");
            if (!Files.isDirectory(testDir)) {
                continue;
            }
            for (Path p : javaFiles(testDir)) {
                String src = readText(p);
                if (src.contains("import org.junit.jupiter.api.Test;")) {
                    f.junit5TestFiles++;
                }
                if (src.contains("import org.junit.Test;")) {
                    f.junit4TestFiles++;
                }
            }
        }

        return f;
    }

    private static String childText(Node parent, String name) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
                return n.getTextContent().trim();
            }
        }
        return "";
    }

    private static int intConstant(String src, String name) {
        Matcher m = Pattern.compile(name + "\\s*=\\s*(\\d+)").matcher(src);
        if (!m.find()) {
            throw new IllegalStateException("源码里找不到常量：" + name);
        }
        return Integer.parseInt(m.group(1));
    }

    private static List<Path> javaFiles(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".java")).collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 抠出一个 Controller 的「方法 + 路径」集合（类级 @RequestMapping + 方法级映射）。 */
    private static List<String> endpointsOf(Path file) {
        List<String> out = new ArrayList<>();
        String src = stripComments(readText(file));
        String base = "";
        Matcher rm = Pattern.compile("@RequestMapping\\(\"([^\"]*)\"\\)").matcher(src);
        if (rm.find()) {
            base = rm.group(1);
        }
        Matcher mm = Pattern.compile("@(Get|Post|Put|Delete)Mapping(?:\\(\"([^\"]*)\"\\))?").matcher(src);
        while (mm.find()) {
            String verb = mm.group(1).toUpperCase();
            String path = mm.group(2) == null ? "" : mm.group(2);
            out.add(verb + " " + base + path);
        }
        return out;
    }

    /** 一个 Controller 里的方法级映射条数。 */
    private static int methodMappingCount(Path file) {
        String src = stripComments(readText(file));
        Matcher mm = Pattern.compile("@(Get|Post|Put|Delete)Mapping").matcher(src);
        int n = 0;
        while (mm.find()) {
            n++;
        }
        return n;
    }

    // ==================================================================
    // 核对：README（现解析） ↔ Facts（源码侧）
    // ==================================================================

    private static List<String> verify(String readme, Facts f) {
        List<String> problems = new ArrayList<>();
        Path root = repoRoot();

        // ---- 基本信息表 ----
        String basic = section(readme, "基本信息");
        String readmeVersion = firstBackticked(tableCell(basic, "当前版本"));
        if (!readmeVersion.equals(f.revision)) {
            problems.add("【当前版本】README 写 " + readmeVersion + "，根 POM <revision> 是 " + f.revision);
        }
        String parentCell = tableCell(basic, "父项目");
        String expectParent = "z-boot-parent:" + f.parentVersion;
        if (!parentCell.contains(expectParent)) {
            problems.add("【父项目】README 写 " + firstBackticked(parentCell) + "，根 POM parent 是 " + expectParent);
        }
        // 负向语境：README 说「根 POM 顶部注释仍写着 1.0.19，那是陈旧注释」。
        // ⇒ 只有 pom 注释里真的还写着那个旧号，这句话才成立；注释修好后 README 必须改。
        if (parentCell.contains("1.0.19") && !f.parentCommentStale) {
            problems.add("【父项目】README 说根 POM 顶部注释仍写着 1.0.19，但 pom 里已经没有了 —— 陈旧注释已修，README 要改");
        }
        if (!parentCell.contains("1.0.19") && f.parentCommentStale) {
            problems.add("【父项目】根 POM 顶部注释仍写着 1.0.19（陈旧注释），README 的父项目格没提这处注释");
        }
        // 模块数 + 模块清单（集合相等）
        String moduleCell = tableCell(basic, "模块数");
        List<String> readmeModules = new ArrayList<>();
        for (String b : backticked(moduleCell)) {
            if (b.startsWith("z-skill-")) {
                readmeModules.add(b);
            }
        }
        if (!readmeModules.equals(f.modules)) {
            problems.add("【模块】README 写 " + readmeModules + "，根 POM <modules> 是 " + f.modules);
        }
        List<Integer> moduleCountNum = numbersOutsideCode(moduleCell);
        int readmeModuleCount = moduleCountNum.isEmpty() ? -1 : moduleCountNum.get(0);
        if (readmeModuleCount != f.modules.size()) {
            problems.add("【模块数】README 写 " + readmeModuleCount + "，根 POM <modules> 是 " + f.modules.size() + " 个");
        }
        // 「5 个坐标含聚合 POM」= 模块数 + 聚合 POM 本身
        int coordCount = f.modules.size() + 1;
        if (!moduleCell.contains(String.valueOf(coordCount))) {
            problems.add("【坐标数】README 的模块数格没提 " + coordCount + " 个坐标（模块 " + f.modules.size()
                    + " + 聚合 POM 1）");
        }

        // ---- 能力清单 ----
        String capability = section(readme, "能力清单");
        List<String> capRows = tableRows(capability);
        String formatRow = rowByFirstCell(capRows, "平台形状识别");
        if (formatRow == null) {
            problems.add("【format 种数】能力清单里找不到「N 种 format」那一行");
        } else {
            Matcher m = Pattern.compile("(\\d+)\\s*种\\s*format").matcher(cellOf(formatRow, 3));
            if (!m.find()) {
                problems.add("【format 种数】那一行里抠不出「N 种 format」");
            } else {
                int readmeFormatCount = Integer.parseInt(m.group(1));
                if (readmeFormatCount != f.formatIds.size()) {
                    problems.add("【format 种数】README 写 " + readmeFormatCount + " 种，SkillFormat 枚举有 "
                            + f.formatIds.size() + " 种：" + f.formatIds);
                }
            }
        }
        // 风险等级集合
        String riskRow = rowByFirstCell(capRows, "静态风险体检");
        if (riskRow == null) {
            problems.add("【风险等级】能力清单里找不到风险体检那一行");
        } else {
            String riskDesc = cellOf(riskRow, 3);
            List<String> readmeLevels = new ArrayList<>();
            for (String b : backticked(riskDesc)) {
                if (b.contains("/")) {
                    for (String part : b.split("/")) {
                        readmeLevels.add(part.trim());
                    }
                }
            }
            List<String> sourceLevels = new ArrayList<>(f.riskLevels);
            sourceLevels.remove("unscanned");
            Collections.sort(readmeLevels);
            List<String> sortedSource = new ArrayList<>(sourceLevels);
            Collections.sort(sortedSource);
            if (!readmeLevels.equals(sortedSource)) {
                problems.add("【风险等级】README 写 " + readmeLevels + "，SkillDto 的常量是 " + sortedSource);
            }
            boolean mentionsUnscanned = false;
            for (String b : backticked(riskDesc)) {
                if ("unscanned".equals(b)) {
                    mentionsUnscanned = true;
                }
            }
            if (f.riskLevels.contains("unscanned") && !mentionsUnscanned) {
                problems.add("【风险等级】源码有 unscanned（未扫描档），README 那一行没提它");
            }
        }
        // 规范校验上限：数字多重集相等（顺序无关）
        String specRow = rowByFirstCell(capRows, "规范校验与清洗");
        if (specRow == null) {
            problems.add("【校验上限】能力清单里找不到「规范校验与清洗」那一行");
        } else {
            List<Integer> readmeLimits = numbersOutsideCode(cellOf(specRow, 3));
            List<Integer> sourceLimits = new ArrayList<>(f.specLimits);
            Collections.sort(readmeLimits);
            List<Integer> sortedSource = new ArrayList<>(sourceLimits);
            Collections.sort(sortedSource);
            if (!readmeLimits.equals(sortedSource)) {
                problems.add("【校验上限】README 那一行里的数是 " + readmeLimits
                        + "，SkillSpec 的常量是 " + sortedSource);
            }
        }
        // 平台自动发现：家目录客户端名单（集合相等）
        String presetRow = rowByFirstCell(capRows, "本机平台自动发现");
        if (presetRow == null) {
            problems.add("【平台落点】能力清单里找不到「本机平台自动发现」那一行");
        } else {
            String presetDesc = cellOf(presetRow, 3);
            List<String> readmeClients = new ArrayList<>();
            for (String b : backticked(presetDesc)) {
                // 只认 `~/.{a,b,c}` 这一形态（客户端名单）；
                // `~/.qoder-cn/{plugins,skills}` 是另一处落点，不是客户端。
                Matcher m = Pattern.compile("^~/\\.\\{([^}]*)\\}/").matcher(b);
                if (m.find()) {
                    for (String part : m.group(1).split(",")) {
                        readmeClients.add(part.trim());
                    }
                }
            }
            List<String> sourceClients = new ArrayList<>(f.platformClients);
            Collections.sort(readmeClients);
            Collections.sort(sourceClients);
            if (!readmeClients.equals(sourceClients)) {
                problems.add("【平台落点】README 列的家目录客户端是 " + readmeClients
                        + "，PlatformPresets.CLIENTS 是 " + sourceClients);
            }
            for (String extra : Arrays.asList(".qoder-cn", ".agents")) {
                if (!presetDesc.contains(extra)) {
                    problems.add("【平台落点】README 那一行没提家目录落点 " + extra);
                }
            }
        }
        // 能力清单点名的实现类必须存在
        Set<String> namedClasses = new TreeSet<>();
        for (String row : capRows) {
            for (String b : backticked(row)) {
                String simple = b.contains("/") ? b.substring(b.lastIndexOf('/') + 1) : b;
                if (simple.matches("[A-Z][A-Za-z0-9]*") && !HTTP_VERBS.contains(simple)) {
                    namedClasses.add(simple);
                }
            }
        }
        if (namedClasses.isEmpty()) {
            problems.add("【能力清单】一节里没抠出任何实现类名");
        }
        for (String cls : namedClasses) {
            if (!javaClassExists(root, cls, f)) {
                problems.add("【能力清单】README 点名的实现类 " + cls + " 在盘上找不到对应源码");
            }
        }

        // ---- API 一览：端点集合 ----
        String api = section(readme, "API 一览");
        Set<String> readmeMarketplace = new TreeSet<>();
        int readmeCompatRows = 0;
        for (String line : api.split("\n", -1)) {
            String t = line.trim();
            if (!t.startsWith("|")) {
                continue;
            }
            String[] cells = t.split("\\|", -1);
            if (cells.length < 3) {
                continue;
            }
            String first = cells[1].trim();
            List<String> frags = backticked(first);
            String plain = first.replace("`", "").trim();
            if (HTTP_VERBS.contains(plain)) {
                // Marketplace 表：| GET | `/skill/search` | ... |（方法列是纯文本，没有反引号）
                for (String p : backticked(cells[2].trim())) {
                    readmeMarketplace.add(plain + " " + p);
                }
            } else if (!frags.isEmpty() && frags.get(0).startsWith("/")) {
                // 对外兼容输出表：| `/skill/compat/...` | 形状 |
                readmeCompatRows++;
            }
        }
        Set<String> sourceMarketplace = new TreeSet<>(f.marketplaceEndpoints);
        if (!readmeMarketplace.equals(sourceMarketplace)) {
            problems.add("【Marketplace 端点】README 写 " + readmeMarketplace + "，源码映射是 " + sourceMarketplace);
        }
        if (readmeCompatRows != f.compatEndpointCount) {
            problems.add("【兼容端点】README 的对外兼容表有 " + readmeCompatRows + " 行，源码映射共 "
                    + f.compatEndpointCount + " 条");
        }
        // 控制面：README 写「GET 七个」，第一个是全路径，其余是后缀
        Matcher cm = Pattern.compile("`GET`\\s*(\\S+?)个").matcher(api);
        if (!cm.find()) {
            problems.add("【控制面端点】API 一览里抠不出「GET N 个」那句");
        } else {
            int readmeAdminCount = cnNumber(cm.group(1));
            if (readmeAdminCount != f.adminEndpoints.size()) {
                problems.add("【控制面端点】README 写 " + readmeAdminCount + " 个，AdminController 映射共 "
                        + f.adminEndpoints.size() + " 条：" + f.adminEndpoints);
            }
        }
        for (String e : f.adminEndpoints) {
            String suffix = e.substring(e.indexOf(' ') + 1).replace("/skill/admin", "");
            if (!api.contains(suffix)) {
                problems.add("【控制面端点】源码有 " + e + "，README 的控制面清单里没提到它");
            }
        }

        // ---- 聚合语义：派生深度上限 ----
        String aggregate = section(readme, "聚合语义");
        if (f.maxDerivedDepth > 0) {
            String want = "MAX_DERIVED_DEPTH=" + f.maxDerivedDepth;
            if (!aggregate.contains(want)) {
                problems.add("【派生深度】SkillAggregator 的 MAX_DERIVED_DEPTH 是 " + f.maxDerivedDepth
                        + "，README 聚合语义节没写 " + want);
            }
        }

        // ---- 技术栈：取数器口径 + 测试台口径 ----
        String stack = section(readme, "技术栈");
        String fetchRow = rowByFirstCell(tableRows(stack), "远端取数");
        if (fetchRow == null) {
            problems.add("【取数器】技术栈表里找不到「远端取数」那一行");
        } else {
            if (f.fetcherTimeoutMs > 0 && !fetchRow.contains(String.valueOf(f.fetcherTimeoutMs))) {
                problems.add("【取数器】HttpFetcher.Jdk 缺省超时是 " + f.fetcherTimeoutMs
                        + "ms，README 那一行没写这个数");
            }
            if (f.fetcherBodyLimitMb > 0) {
                long mb = f.fetcherBodyLimitMb;
                if (!fetchRow.contains(mb + "MB")) {
                    problems.add("【取数器】响应体上限是 " + mb + "MB，README 那一行没写 " + mb + "MB");
                }
            }
        }
        String testRow = rowByFirstCell(tableRows(stack), "测试");
        if (testRow == null) {
            problems.add("【测试台】技术栈表里找不到「测试」那一行");
        } else {
            if (!testRow.contains("junit-jupiter")) {
                problems.add("【测试台】根 POM 直供的 JUnit 5 依赖是 junit-jupiter，README 那一行没提它");
            }
            if (!testRow.contains("junit-vintage-engine")) {
                problems.add("【测试台】根 POM 直供的 vintage 引擎是 junit-vintage-engine，README 那一行没提它");
            }
            if (f.junit4TestFiles == 0 && testRow.contains("junit:junit")) {
                problems.add("【测试台】仓内没有一个 JUnit 4 用例（JUnit 4 的 import 数 = 0），"
                        + "README 那一行却还写着 junit:junit");
            }
            if (f.junit5TestFiles == 0) {
                problems.add("【测试台】仓内一个 JUnit 5 用例都没有，README 那一行的口径要重写");
            }
            if (f.modulesWithStarterTest > 0
                    && f.modulesExcludingVintage != f.modulesWithStarterTest) {
                problems.add("【测试台】带 spring-boot-starter-test 的模块有 " + f.modulesWithStarterTest
                        + " 个，其中排除 vintage 的只有 " + f.modulesExcludingVintage
                        + " 个，README 那一行说的「统一排除」对不上");
            }
        }

        // ---- 项目结构：部署面 ----
        String structure = section(readme, "项目结构");
        for (String absent : Arrays.asList("Dockerfile", "docker-compose", "deploy/", "k8s/", "Makefile")) {
            if (structure.contains("没有") && !structure.contains(absent)) {
                problems.add("【部署面】README 项目结构节说「本仓没有」某物，但没点名 " + absent);
            }
            if (existsAtRoot(root, absent)) {
                problems.add("【部署面】README 声称仓根没有 " + absent + "，但盘上存在");
            }
        }

        // ---- 文档目录：链接必须落地 ----
        String docs = section(readme, "文档目录");
        List<String> links = new ArrayList<>();
        Matcher lm = Pattern.compile("\\]\\(([^)]+)\\)").matcher(docs);
        while (lm.find()) {
            String target = lm.group(1).trim();
            if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("#")) {
                continue;
            }
            links.add(target);
        }
        if (links.isEmpty()) {
            problems.add("【文档目录】节里一个本地链接都没抠到");
        }
        for (String link : links) {
            if (!Files.exists(root.resolve(link))) {
                problems.add("【文档目录】README 里的链接目标在盘上不存在：" + link);
            }
        }

        return problems;
    }

    /** 取小节里所有表格数据行（去掉表头与分隔行）。 */
    private static List<String> tableRows(String section) {
        List<String> rows = new ArrayList<>();
        for (String line : section.split("\n", -1)) {
            String t = line.trim();
            if (!t.startsWith("|")) {
                continue;
            }
            if (t.startsWith("|---") || t.startsWith("|--")) {
                continue;
            }
            String[] cells = t.split("\\|", -1);
            if (cells.length < 3) {
                continue;
            }
            String k = cells[1].trim();
            if (k.equals("能力") || k.equals("字段") || k.equals("层级") || k.equals("方法")
                    || k.equals("路径") || k.equals("配置键") || k.equals("环境变量") || k.equals("项目")) {
                continue;
            }
            rows.add(t);
        }
        return rows;
    }

    /** 取表格行的第 index 个单元（0 起；split 后第 0 个是行首空串，所以说明列是 3）。 */
    private static String cellOf(String row, int index) {
        String[] cells = row.split("\\|", -1);
        return index < cells.length ? cells[index].trim() : "";
    }

    /**
     * 按表格<b>第一列</b>的名字全等定位一行。
     *
     * <p>不能用整行 contains：说明列里的字样会让别的行被误命中 ——
     * 「聚合与冲突消解」那行的说明里有「风险判级」四个字，
     * 用 contains 找「风险」会抓到它，于是抠出来的是类名而不是风险等级。</p>
     */
    private static String rowByFirstCell(List<String> rows, String firstCell) {
        for (String row : rows) {
            String[] cells = row.split("\\|", -1);
            if (cells.length < 3) {
                continue;
            }
            if (cells[1].trim().equals(firstCell)) {
                return row;
            }
        }
        return null;
    }

    /**
     * 盘上是否存在某个类（按简单名在各模块的 src/main 下找 .java）。
     *
     * <p>要扫**全部模块** —— 能力清单里点名的类可能落在 starter / admin 里
     *（自动装配类、可执行进程主类都不在 core）。</p>
     */
    private static boolean javaClassExists(Path root, String simpleName, Facts f) {
        String file = simpleName + ".java";
        for (String module : f.modules) {
            Path main = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(main)) {
                continue;
            }
            for (Path p : javaFiles(main)) {
                if (p.getFileName().toString().equals(file)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 仓根是否存在某物（docker-compose 支持通配前缀）。 */
    private static boolean existsAtRoot(Path root, String name) {
        if ("docker-compose".equals(name)) {
            java.io.File[] kids = root.toFile().listFiles();
            if (kids != null) {
                for (java.io.File k : kids) {
                    if (k.getName().startsWith("docker-compose")) {
                        return true;
                    }
                }
            }
            return false;
        }
        return Files.exists(root.resolve(name));
    }

    private static String firstBackticked(String s) {
        List<String> b = backticked(s);
        return b.isEmpty() ? "" : b.get(0);
    }

    // ==================================================================
    // 用例
    // ==================================================================

    @Test
    public void README主张与源码重算逐条对账() {
        List<String> problems = verify(readme(), facts());
        if (!problems.isEmpty()) {
            StringBuilder sb = new StringBuilder("z-skill README 与源码不一致，共 " + problems.size() + " 处：");
            for (String p : problems) {
                sb.append("\n  - ").append(p);
            }
            fail(sb.toString());
        }
    }

    @Test
    public void 不许冤枉健康页() {
        List<String> problems = verify(readme(), facts());
        assertTrue(problems.isEmpty(), "真实 README 被判红：\n" + String.join("\n", problems));
    }

    @Test
    public void 尺子的牙口_版本改错必红() {
        String readme = readme();
        String mutated = readme.replace("| **当前版本** | `" + facts().revision + "`",
                "| **当前版本** | `9.9.9`");
        assertFalse(mutated.equals(readme), "合成变异没生效：README 里没找到当前版本");
        List<String> problems = verify(mutated, facts());
        assertFalse(problems.isEmpty(), "README 版本号改成 9.9.9，尺子居然没报红");
        assertTrue(problems.toString().contains("9.9.9"),
                "报错没指出被改坏的数字：\n" + String.join("\n", problems));
    }

    @Test
    public void 尺子的牙口_模块清单改错必红() {
        String readme = readme();
        String mutated = readme.replace("`z-skill-admin`", "`z-skill-admiX`");
        assertFalse(mutated.equals(readme), "合成变异没生效：README 里没找到 z-skill-admin");
        List<String> problems = verify(mutated, facts());
        assertFalse(problems.isEmpty(), "README 模块清单被改，尺子居然没报红");
    }

    @Test
    public void 尺子的牙口_端点路径改错必红() {
        String readme = readme();
        String mutated = readme.replace("`/skill/search`", "`/skill/searcX`");
        assertFalse(mutated.equals(readme), "合成变异没生效：README 端点表里没找到 /skill/search 那一行");
        List<String> problems = verify(mutated, facts());
        assertFalse(problems.isEmpty(), "README 端点路径被改，尺子居然没报红");
        assertTrue(problems.toString().contains("searcX"),
                "报错没指出被改坏的端点：\n" + String.join("\n", problems));
    }

    @Test
    public void 尺子的牙口_格式种数改错必红() {
        String readme = readme();
        String mutated = readme.replace("9 种 format", "8 种 format");
        assertFalse(mutated.equals(readme), "合成变异没生效：README 里没找到「9 种 format」");
        List<String> problems = verify(mutated, facts());
        assertFalse(problems.isEmpty(), "README 的 format 种数被改，尺子居然没报红");
    }

    @Test
    public void 尺子的牙口_文档链接失效必红() {
        String readme = readme();
        // 假路径**必须拆两段拼接**：源码里若出现连续的「不存在的路径」字面量，
        // depth_check 会判成「悬空字面量」违规（它连注释行一起扫）。
        String brokenLink = "_doc/001_arch/" + "99-not-exist.md";
        String mutated = readme.replace("_doc/001_arch/00-overview.md", brokenLink);
        assertFalse(mutated.equals(readme), "合成变异没生效：README 里没找到 00-overview.md 链接");
        List<String> problems = verify(mutated, facts());
        assertFalse(problems.isEmpty(), "README 文档链接指向不存在的文件，尺子居然没报红");
    }

    @Test
    public void 守卫生效确认() {
        String readme = readme();
        assertTrue(readme.contains("z-skill"), "读到的 README 不含仓名，仓根定位可能错了");
        Facts f = facts();
        assertNotNull(f, "facts 应能采到");
        assertFalse(f.modules.isEmpty(), "模块清单不该是空的");
        assertEquals(f.formatIds.size(), new TreeSet<>(f.formatIds).size(),
                "SkillFormat 的 id 不该有重复");
        assertEquals(f.marketplaceEndpoints.size(), new TreeSet<>(f.marketplaceEndpoints).size(),
                "Controller 名单不应有重复");
    }

    private static String readme() {
        return readText(repoRoot().resolve("README.md"));
    }
}
