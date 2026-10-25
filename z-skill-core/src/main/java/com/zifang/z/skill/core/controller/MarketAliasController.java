package com.zifang.z.skill.core.controller;

import com.zifang.z.skill.api.dto.SkillInstallDto;
import com.zifang.z.skill.api.dto.SkillSearchResultDto;
import com.zifang.z.skill.api.dto.SkillSourceDto;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 前端别名面 (2026-10-22 智能板块技能市场改造).
 *
 * <p>fleet 惯例是 /api 前缀 + Result 信封 (前端 axios baseURL=/api、createRequest 统一解包)，
 * 而本仓原生面 {@code /skill/*} 是裸 DTO + skills.sh 兼容生态的形状。这里把市场需要的
 * 只读+安装面投影到 {@code /api/skill/*}，实现全部委托 {@link SkillController} ——
 * PublicSurface 出口脱敏只做一遍，别名不复制任何业务逻辑。
 *
 * <p>端点:
 * <ul>
 *   <li>{@code GET /api/skill/search} — 关键词/分类/来源/风险分面检索 + 分页排序</li>
 *   <li>{@code GET /api/skill/categories|sources|stats|installed} — 目录侧信息与"我的安装"</li>
 *   <li>{@code GET /api/skill/detail?id=} 与 {@code GET /api/skill/content?id=&file=} — 详情与文件预览</li>
 *   <li>{@code POST /api/skill/install} / {@code DELETE /api/skill/uninstall/{id}} / {@code POST /api/skill/refresh}</li>
 *   <li>{@code POST /api/skill/publish} — 写出并发布（发布目录是市场的一个来源）</li>
 *   <li>{@code GET /api/skill/download?id=} — 拿下来（取完整文件包）</li>
 *   <li>{@code POST /api/skill/install-files} — 装到本机技能目录（落盘）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/skill")
public class MarketAliasController {

    private final SkillController delegate;

    public MarketAliasController(SkillController delegate) {
        this.delegate = delegate;
    }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam(required = false) String q,
                                               @RequestParam(required = false) String category,
                                               @RequestParam(required = false) String tag,
                                               @RequestParam(required = false) String source,
                                               @RequestParam(required = false) String format,
                                               @RequestParam(required = false) String risk,
                                               @RequestParam(required = false) Boolean installed,
                                               @RequestParam(required = false, defaultValue = "relevance") String sort,
                                               @RequestParam(required = false, defaultValue = "0") int page,
                                               @RequestParam(required = false, defaultValue = "20") int perPage) {
        return ok(delegate.search(q, category, tag, source, format, risk, installed, sort, page, perPage));
    }

    @GetMapping("/categories")
    public Map<String, Object> categories() {
        return ok(delegate.categories());
    }

    @GetMapping("/sources")
    public Map<String, Object> sources() {
        return ok(delegate.sources());
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return ok(delegate.stats());
    }

    @GetMapping("/installed")
    public Map<String, Object> installed() {
        return ok(delegate.installed());
    }

    @GetMapping("/detail")
    public Map<String, Object> detail(@RequestParam("id") String id) {
        return ok(delegate.detailById(id));
    }

    @GetMapping("/content")
    public Map<String, Object> content(@RequestParam("id") String id,
                                               @RequestParam(value = "file", required = false) String file) {
        return ok(delegate.contentById(id, file));
    }

    @PostMapping("/install")
    public Map<String, Object> install(@RequestBody(required = false) Map<String, Object> body) {
        return ok(delegate.install(body));
    }

    @DeleteMapping("/uninstall/{id}")
    public Map<String, Object> uninstall(@PathVariable("id") String id) {
        return ok(delegate.uninstall(id));
    }

    /**
     * 查询串形态的卸载 — 与 {@link #uninstall(String)} 同义.
     *
     * <p>聚合出来的 skill id 形如 {@code source/slug}, 带斜杠: 放进路径段会被 Tomcat 切段
     * (或按 %2F 拒收), 放进 query 由 axios 编码才是稳的。前端走这条。
     */
    @DeleteMapping("/uninstall")
    public Map<String, Object> uninstallById(@RequestParam("id") String id) {
        return ok(delegate.uninstall(id));
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        return ok(delegate.refresh());
    }

    /**
     * 「写出并发布」—— 自己写的 skill 落进发布目录.
     *
     * <p>发布目录是市场的一个来源 ⇒ 发布完 {@code POST /skill/refresh} 即可检索到.
     * 前端曾经对着 {@code /skill/upload} 写过, 但那个端点在后端<b>从未存在过</b> ⇒ 这里用 publish 这条正名.
     */
    @PostMapping("/publish")
    public Map<String, Object> publish(@RequestBody(required = false) Map<String, Object> body) {
        return ok(delegate.publish(body));
    }

    /** 「拿下来」—— 取一个 skill 的完整文件包（主文件 + 随包文件）. */
    @GetMapping("/download")
    public Map<String, Object> download(@RequestParam("id") String id) {
        return ok(delegate.download(id));
    }

    /** 「装上能用」—— 把 skill 的文件复制到安装目录（agent 运行时的 skill 根）. */
    @PostMapping("/install-files")
    public Map<String, Object> installFiles(@RequestBody(required = false) Map<String, Object> body) {
        return ok(delegate.installFiles(body));
    }

    /**
     * fleet Result 信封的本仓内联镜像 ({code, message, data, success})。
     *
     * <p>{@code code} 必须是 <strong>200</strong> —— 前端 {@code createRequest} 的
     * {@code unwrapResponse} 认的是 {@code code === 200} 才解包 data, 别的值一律当失败 reject.
     * 刻意不引 z-util-core —— 本仓依赖清单里没有它, 为一个信封拉进整个 z-util 不划算.
     */
    private static Map<String, Object> ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("data", data);
        body.put("success", Boolean.TRUE);
        body.put("code", 200);
        body.put("message", "success");
        return body;
    }
}
