package com.zifang.z.skill.core.normalize;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.util.json.JsonCursor;
import com.zifang.util.json.JsonUtil;
import com.zifang.z.skill.core.discover.RawSkill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * JSON 类来源的读取辅助.
 */
public final class JsonSupport {

    private JsonSupport() {
    }

    public static JsonCursor readJson(RawSkill raw, ObjectMapper mapper) throws IOException {
        Object payload = raw.getJson();
        if (payload == null) {
            if (raw.getEntry() == null) {
                throw new IOException("no json payload for " + raw.getOrigin());
            }
            String text = new String(Files.readAllBytes(raw.getEntry()), StandardCharsets.UTF_8);
            payload = JsonUtil.parse(text);
        }
        return JsonCursor.of(plain(payload, mapper));
    }

    /**
     * 树导航一律走 JsonCursor, 这里只负责把载荷摊平成 plain 结构.
     * <p>本仓产出的都是 z-util 树; 外部(插件/测试)直接塞进来的 Jackson JsonNode 走 mapper 摊平,
     * 等调用方也迁完之后这个分支就可以删掉.</p>
     */
    private static Object plain(Object payload, ObjectMapper mapper) {
        if (payload instanceof Map || payload instanceof List
                || payload instanceof com.zifang.util.json.model.JsonObject
                || payload instanceof com.zifang.util.json.model.JsonArray) {
            return JsonUtil.toPlainValue(payload);
        }
        return mapper == null ? payload : JsonUtil.toPlainValue(mapper.convertValue(payload, Object.class));
    }

    /** 兼容 {@code data[]} / {@code skills[]} / 根数组三种包裹形状. */
    public static JsonCursor skillArray(JsonCursor root) {
        if (root == null || root.isNull() || root.isMissing()) return null;
        if (root.isArray()) return root;
        if (root.path("skills").isArray()) return root.path("skills");
        if (root.path("data").isArray()) return root.path("data");
        if (root.path("items").isArray()) return root.path("items");
        return null;
    }

    public static String text(JsonCursor node, String... fieldNames) {
        if (node == null) return null;
        for (String f : fieldNames) {
            JsonCursor v = node.path(f);
            if (v.isScalar()) {
                String s = v.asText();
                if (s != null && !s.trim().isEmpty()) return s.trim();
            }
        }
        return null;
    }

    public static int integer(JsonCursor node, String... fieldNames) {
        return (int) number(node, 0L, fieldNames);
    }

    /**
     * epoch 毫秒这类字段必须按 long 取: 用 int 读 1700000000000 会溢出成负数,
     * 于是 sort=updated 与上游漂移检测双双失真.
     */
    public static long longValue(JsonCursor node, long fallback, String... fieldNames) {
        return number(node, fallback, fieldNames);
    }

    private static long number(JsonCursor node, long fallback, String... fieldNames) {
        if (node == null) return fallback;
        for (String f : fieldNames) {
            Object raw = node.path(f).value();
            if (raw instanceof Number) return ((Number) raw).longValue();
            if (raw instanceof String) {
                try {
                    return Long.parseLong(((String) raw).trim());
                } catch (NumberFormatException ignored) {
                    // 非数字字符串字段不参与计数
                }
            }
        }
        return fallback;
    }
}
