package com.zifang.z.skill.core.controller;

import com.zifang.z.skill.api.exception.SkillException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * z-skill 自己的错误包络: {@code {"error","message"}}, 与 skills.sh 等外部 API 同形.
 *
 * <p>刻意只对 z-skill 的控制器生效(basePackages 限定), 不去改宿主应用全局的异常处理行为.
 *
 * <h3>为什么必须有 {@link Order}(2026-10-24 实测)</h3>
 * 宿主的全局 advice(主壳/AIO 的 {@code GlobalExceptionAdvice})都带一个 {@code Exception.class} 兜底
 * ⇒ <b>没被显式登记的异常一律被压成 500</b>（"服务器内部错误: XXX"）。
 * 本 advice 若不显式排序, 谁先命中取决于注册顺序 ⇒ 在 AIO 里实测:
 * {@code GET /api/skill/detail?id=不存在} 返回 **500**（"SkillException"），
 * 而不是 404 —— 「没有这个 skill」被说成「服务炸了」，调用方没法分支。
 * <p>⇒ 这里给最高优先级: 本 advice 只管 {@code com.zifang.z.skill.core.controller} 下的控制器，
 * 不会抢别人的异常；抢到的也只是**本板块自己的**异常类型。
 * <p>⚠ 同一宿主里 z-oss / z-kb / z-msg 的异常也是被同样的兜底压过一遍，
 * 那些板块是各自在自己仓里登记例外处理的 —— 口径一致.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.zifang.z.skill.core.controller")
public class SkillErrorAdvice {

    private static final Logger log = LoggerFactory.getLogger(SkillErrorAdvice.class);

    @ExceptionHandler(SkillException.class)
    public ResponseEntity<Map<String, Object>> onSkill(SkillException e) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("error", e.getErrorId());
        body.put("message", e.getMessage());
        body.put("code", e.getCode());
        // ⚠ 包络**只留这三个键**: 本仓的契约测试钉死了「错误正文里不许出现 null」
        //   （assertLeakFree: 错误正文该有人话）⇒ 别为了迁就宿主的 fleet 信封
        //   往里塞 data=null / success=false —— 那是**另一个仓**的形状。
        //   调用方按 HTTP 状态码 + message 分支即可。
        HttpStatus status = HttpStatus.resolve(e.getCode());
        return ResponseEntity.status(status == null ? HttpStatus.BAD_REQUEST : status).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> onIllegal(IllegalArgumentException e) {
        log.debug("z-skill: bad request", e);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("error", "bad_request");
        body.put("message", String.valueOf(e.getMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
