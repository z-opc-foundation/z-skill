package com.zifang.z.skill.core.normalize;

import com.zifang.util.json.JsonCursor;
import com.zifang.util.json.JsonUtil;
import com.zifang.z.skill.core.discover.RawSkill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * JSON 类来源的读取辅助.
 */
public final class JsonSupport {

    private JsonSupport() {
    }

    public static JsonCursor readJson(RawSkill raw) throws IOException {
        Object payload = raw.getJson();
        if (!raw.hasJson()) {
            if (raw.getEntry() == null) {
                throw new IOException("no json payload for " + raw.getOrigin());
            }
            String text = new String(Files.readAllBytes(raw.getEntry()), StandardCharsets.UTF_8);
            payload = JsonUtil.parse(text);
        }
        return JsonCursor.of(JsonUtil.toPlainValue(payload));
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
