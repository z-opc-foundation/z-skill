package com.zifang.z.skill.core.spec;

import com.zifang.z.skill.api.spec.SkillSpec;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SkillSpec 清洗/校验规则的契约测试.
 *
 * <p>口径: NAME 的 javadoc("1-64 字符, a-z0-9 与单个连字符, 不得以连字符开头/结尾, 不得含 --")
 * 与 slugify/validate/splitAllowedTools 各自的 javadoc, 外加各家仓库真实写法(RedBookSkills 大写、
 * Qoder 逗号分隔 allowed-tools、Cursor 分号).
 */
public class SkillSpecTest {

    private static String times(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    @Test
    public void slugify_lowercasesAndMapsSeparators() {
        assertEquals("redbookskills", SkillSpec.slugify("RedBookSkills"));
        assertEquals("my-skill-name-here", SkillSpec.slugify("My_Skill.Name Here"));
        assertEquals("spaced-out", SkillSpec.slugify("  Spaced   Out  "));
        assertEquals("v1-2-3", SkillSpec.slugify("v1.2.3"));
        assertEquals("pdf-processing", SkillSpec.slugify("PDF-Processing"));
        assertEquals("a-b", SkillSpec.slugify("a@b"), "其他非法字符也转连字符");
    }

    @Test
    public void slugify_collapsesAndTrimsHyphens() {
        assertEquals("a-b", SkillSpec.slugify("a---b"));
        assertEquals("foo", SkillSpec.slugify("--foo--"));
        assertEquals("x", SkillSpec.slugify("__x__"));
        assertEquals("a-b", SkillSpec.slugify("a-_-b"));
        assertEquals("a-b-c", SkillSpec.slugify("a - b - c"));
    }

    @Test
    public void slugify_returnsNullWhenNothingSurvives() {
        assertNull(SkillSpec.slugify(null));
        assertNull(SkillSpec.slugify(""));
        assertNull(SkillSpec.slugify("   "));
        assertNull(SkillSpec.slugify("小红书技能"), "纯 CJK 清洗后为空, 调用方要回退目录名");
        assertNull(SkillSpec.slugify("!!!@@@"));
        assertNull(SkillSpec.slugify("..."));
        assertEquals("x", SkillSpec.slugify("技能 x 工具"));
    }

    @Test
    public void slugify_truncatesTo64_neverEndingWithAHyphen() {
        String longSlug = SkillSpec.slugify(times('a', 80));
        assertEquals(SkillSpec.NAME_MAX, longSlug.length());
        assertEquals(times('a', SkillSpec.NAME_MAX), longSlug);

        String cutOnHyphen = SkillSpec.slugify(times('b', 63) + "-tail");
        assertEquals(63, cutOnHyphen.length(), "截断落在连字符上时必须再去掉尾字符");
        assertFalse(cutOnHyphen.endsWith("-"));
        assertEquals(SkillSpec.NAME_MAX - 1, cutOnHyphen.length());
    }

    @Test
    public void slugify_isIdempotent() {
        String[] inputs = new String[]{"RedBookSkills", "My_Skill.Name Here", "--foo--", "小红书 x",
                "a---b", "  Spaced   Out  ", times('c', 70), "v1.2.3", "a@b"};
        for (String in : inputs) {
            String once = SkillSpec.slugify(in);
            assertEquals(once, SkillSpec.slugify(once), "slugify 不幂等: " + in);
            assertTrue(SkillSpec.isValidName(once) || once == null, "清洗结果应自带合规: " + in + " -> " + once);
        }
    }

    @Test
    public void isValidName_acceptsConformingSlugs() {
        assertTrue(SkillSpec.isValidName("a"));
        assertTrue(SkillSpec.isValidName("0"));
        assertTrue(SkillSpec.isValidName("pdf-processing"));
        assertTrue(SkillSpec.isValidName("a1-b2-c3"));
        assertTrue(SkillSpec.isValidName(times('d', 64)), "恰好 64 字符应放行");
        assertTrue(SkillSpec.isValidName("redbookskills"));
    }

    @Test
    public void isValidName_rejectsDocumentedIllegalShapes() {
        assertFalse(SkillSpec.isValidName(null));
        assertFalse(SkillSpec.isValidName(""));
        assertFalse(SkillSpec.isValidName("-lead"));
        assertFalse(SkillSpec.isValidName("trail-"));
        assertFalse(SkillSpec.isValidName("UPPER"));
        assertFalse(SkillSpec.isValidName(times('e', 65)), "65 字符超过上限");
        assertFalse(SkillSpec.isValidName("a b"));
        assertFalse(SkillSpec.isValidName("a_b"));
        assertFalse(SkillSpec.isValidName("a.b"));
        assertFalse(SkillSpec.isValidName("中文"));
    }

    @Test
    public void nameRegex_rejectsConsecutiveHyphens_perSpec() {
        // NAME 的 javadoc 与 Agent Skills 规范(^ [a-z0-9]+(-[a-z0-9]+)* $)都禁止 --;
        // SkillSpec.java:17 的 [a-z0-9-]* 却放行了 "a--b"
        assertFalse(SkillSpec.isValidName("a--b"), "不得含 -- 是 NAME javadoc 明写的规则");
        assertFalse(SkillSpec.NAME.matcher("pdf--processing").matches());
    }

    @Test
    public void validate_missingName_shortCircuitsEverythingElse() {
        assertEquals(Arrays.asList("error:name-missing"), SkillSpec.validate(null, null, null, null));
        assertEquals(Arrays.asList("error:name-missing"), SkillSpec.validate("   ", "fallback", "desc", "compat"));
    }

    @Test
    public void validate_nameNonConforming_warnsWithRawArrowSlug() {
        assertEquals(Arrays.asList("warning:name-non-conforming:RedBookSkills->redbookskills"),
                SkillSpec.validate("RedBookSkills", "redbookskills", "Extract PDF text.", null));
    }

    @Test
    public void validate_nameUnsanitizeable_isAnError() {
        assertEquals(Arrays.asList("error:name-unsanitizeable:小红书技能"),
                SkillSpec.validate("小红书技能", null, "描述", null));
    }

    @Test
    public void validate_nameTooLong_warnsWithLength() {
        String raw = times('f', 65);
        assertEquals(Arrays.asList("warning:name-too-long:65"),
                SkillSpec.validate(raw, times('f', 64), "描述", null));
    }

    @Test
    public void validate_descriptionBranches() {
        assertEquals(Arrays.asList("error:description-missing"),
                SkillSpec.validate("pdf-processing", "pdf-processing", null, null));
        assertEquals(Arrays.asList("error:description-missing"),
                SkillSpec.validate("pdf-processing", "pdf-processing", "   ", null));
        assertEquals(Arrays.asList("warning:description-too-long:1025"),
                SkillSpec.validate("pdf-processing", "pdf-processing", times('g', 1025), null));
        assertEquals(Collections.<String>emptyList(),
                SkillSpec.validate("pdf-processing", "pdf-processing", times('g', 1024), null));
        assertEquals(Arrays.asList("info:description-multiline"),
                SkillSpec.validate("pdf-processing", "pdf-processing", "first line\nsecond line", null));
    }

    @Test
    public void validate_compatibilityBoundary() {
        assertEquals(Collections.<String>emptyList(),
                SkillSpec.validate("pdf-processing", "pdf-processing", "desc", times('h', 500)));
        assertEquals(Arrays.asList("warning:compatibility-too-long"),
                SkillSpec.validate("pdf-processing", "pdf-processing", "desc", times('h', 501)));
    }

    @Test
    public void validate_fullyConformingSkill_returnsEmptyList() {
        assertEquals(Collections.<String>emptyList(),
                SkillSpec.validate("pdf-processing", "pdf-processing", "Extract text and tables from PDF files.",
                        "Requires poppler-utils on PATH"));
    }

    @Test
    public void validate_stacksAllFindingsInDocumentedOrder() {
        List<String> issues = SkillSpec.validate("Red_Book", "red-book", times('i', 1025) + "\nmore", times('j', 600));
        assertEquals(Arrays.asList(
                "warning:name-non-conforming:Red_Book->red-book",
                "warning:description-too-long:1030",
                "info:description-multiline",
                "warning:compatibility-too-long"), issues, String.valueOf(issues));
    }

    @Test
    public void splitAllowedTools_handlesSpaceCommaAndSemicolonForms() {
        assertEquals(Arrays.asList("Read", "Write", "Bash"), SkillSpec.splitAllowedTools("Read Write Bash"));
        assertEquals(Arrays.asList("Read", "Write"), SkillSpec.splitAllowedTools("Read,Write"));
        assertEquals(Arrays.asList("Read", "Write"), SkillSpec.splitAllowedTools("Read, Write"));
        assertEquals(Arrays.asList("Read", "Write"), SkillSpec.splitAllowedTools("Read; Write"));
        assertEquals(Arrays.asList("Read", "Write"), SkillSpec.splitAllowedTools(" Read ,  Write ;"));
    }

    @Test
    public void splitAllowedTools_keepsParameterisedToolNameAsOneToken() {
        assertEquals(Arrays.asList("Bash(git:*)", "Read", "Write"),
                SkillSpec.splitAllowedTools("Bash(git:*) Read Write"));
        assertEquals(Arrays.asList("Read", "Bash(git:*)"),
                SkillSpec.splitAllowedTools("Read, Bash(git:*)"), "输出顺序应与原文出现顺序一致");
    }

    @Test
    public void splitAllowedTools_dedupesKeepingOrder_andTreatsBlankAsEmpty() {
        assertEquals(Arrays.asList("Read", "Write", "Bash(git:*)"),
                SkillSpec.splitAllowedTools("Read Write Read Bash(git:*) Write"));
        assertEquals(Collections.<String>emptyList(), SkillSpec.splitAllowedTools(null));
        assertEquals(Collections.<String>emptyList(), SkillSpec.splitAllowedTools(""));
        assertEquals(Collections.<String>emptyList(), SkillSpec.splitAllowedTools("   "));
        assertEquals(Collections.<String>emptyList(), SkillSpec.splitAllowedTools(",; ,"));
    }

    @Test
    public void nameLengthCap_isEnforcedConsistently() {
        assertEquals(SkillSpec.NAME_MAX, SkillSpec.slugify(times('k', 100)).length());
        assertTrue(SkillSpec.isValidName(times('k', SkillSpec.NAME_MAX)));
        assertFalse(SkillSpec.isValidName(times('k', SkillSpec.NAME_MAX + 1)));
    }
}
