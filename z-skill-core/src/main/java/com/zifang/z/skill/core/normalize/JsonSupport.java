package com.zifang.z.skill.core.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    public static JsonNode readJson(RawSkill raw, ObjectMapper mapper) throws IOException {
        if (raw.getJson() != null) return raw.getJson();
        if (raw.getEntry() == null) {
            throw new IOException("no json payload for " + raw.getOrigin());
        }
        String text = new String(Files.readAllBytes(raw.getEntry()), StandardCharsets.UTF_8);
        return mapper.readTree(text);
    }

    /** 兼容 {@code data[]} / {@code skills[]} / 根数组三种包裹形状. */
    public static JsonNode skillArray(JsonNode root) {
        if (root == null || root.isNull()) return null;
        if (root.isArray()) return root;
        if (root.has("skills") && root.get("skills").isArray()) return root.get("skills");
        if (root.has("data") && root.get("data").isArray()) return root.get("data");
        if (root.has("items") && root.get("items").isArray()) return root.get("items");
        return null;
    }

    public static String text(JsonNode node, String... fieldNames) {
        if (node == null) return null;
        for (String f : fieldNames) {
            JsonNode v = node.get(f);
            if (v != null && v.isValueNode()) {
                String s = v.asText();
                if (s != null && !s.trim().isEmpty()) return s.trim();
            }
        }
        return null;
    }

    public static int integer(JsonNode node, String... fieldNames) {
        return (int) number(node, 0L, fieldNames);
    }

    /**
     * epoch 毫秒这类字段必须按 long 取: 用 int 读 1700000000000 会溢出成负数,
     * 于是 sort=updated 与上游漂移检测双双失真.
     */
    public static long longValue(JsonNode node, long fallback, String... fieldNames) {
        return number(node, fallback, fieldNames);
    }

    private static long number(JsonNode node, long fallback, String... fieldNames) {
        if (node == null) return fallback;
        for (String f : fieldNames) {
            JsonNode v = node.get(f);
            if (v != null && v.isNumber()) return v.asLong();
            if (v != null && v.isTextual()) {
                try {
                    return Long.parseLong(v.asText().trim());
                } catch (NumberFormatException ignored) {
                    // 非数字字符串字段不参与计数
                }
            }
        }
        return fallback;
    }
}
