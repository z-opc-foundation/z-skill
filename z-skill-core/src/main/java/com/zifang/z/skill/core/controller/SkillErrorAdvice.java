package com.zifang.z.skill.core.controller;

import com.zifang.z.skill.api.exception.SkillException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 */
@RestControllerAdvice(basePackages = "com.zifang.z.skill.core.controller")
public class SkillErrorAdvice {

    private static final Logger log = LoggerFactory.getLogger(SkillErrorAdvice.class);

    @ExceptionHandler(SkillException.class)
    public ResponseEntity<Map<String, Object>> onSkill(SkillException e) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("error", e.getErrorId());
        body.put("message", e.getMessage());
        body.put("code", e.getCode());
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
