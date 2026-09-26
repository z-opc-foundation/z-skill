package com.zifang.z.skill.core.spec;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Frontmatter 宽松解析器的契约测试.
 *
 * <p>口径来自类自身 javadoc 承诺兜住的脏写法: CRLF/BOM/围栏前空行/{@code ...} 收尾/空值续行/
 * 块标量 {@code |}{@code >}{@code |-}{@code >-}/未加引号却带冒号/行内与块列表/逗号串/一层嵌套映射/
 * 规范外的键原样保留, 以及"解析失败不抛异常, 问题落 issues".
 */
public class FrontmatterTest {

    @Test
    public void plainKeyValues_areParsed_andBodyStartsAfterClosingFence() {
        Frontmatter fm = Frontmatter.parse("---\nname: pdf-processing\ndescription: Extract text from PDFs.\n---\n# Title\n\nBody line.\n");
        assertTrue(fm.isPresent());
        assertEquals(Collections.<String>emptyList(), fm.issues(), "规范写法不该报任何 issue");
        assertEquals("pdf-processing", fm.string("name"));
        assertEquals("Extract text from PDFs.", fm.string("description"));
        assertEquals("# Title\n\nBody line.\n", fm.body());
        assertNull(fm.string("ghost"));
    }

    @Test
    public void quotedValues_loseOuterQuotes_andKeepEmbeddedColons() {
        Frontmatter fm = Frontmatter.parse("---\n"
                + "description: \"Use this: carefully\"\n"
                + "when_to_use: 'when the user says: deploy'\n"
                + "name: plain-value\n"
                + "license: \"\"\n"
                + "---\n");
        assertEquals("Use this: carefully", fm.string("description"));
        assertEquals("when the user says: deploy", fm.string("when_to_use"));
        assertEquals("plain-value", fm.string("name"));
        assertEquals("", fm.values().get("license"));
        assertEquals(Collections.<String>emptyList(), fm.issues());
    }

    @Test
    public void unquotedValueWithSeveralColons_isNotSplitAtTheFirstColon() {
        // Codex/Cursor 的 description 极常见: 值里没有引号却带冒号
        Frontmatter fm = Frontmatter.parse("---\ndescription: Use this: when the user asks: why\n---\nbody\n");
        assertEquals("Use this: when the user asks: why", fm.string("description"));
        assertEquals(Collections.<String>emptyList(), fm.issues());
        assertEquals("body\n", fm.body());
    }

    @Test
    public void missingFence_isReportedAsError_andWholeTextBecomesBody() {
        Frontmatter fm = Frontmatter.parse("name: ghost\nThis is a plain markdown file.\n");
        assertFalse(fm.isPresent());
        assertEquals(Arrays.asList("error:frontmatter-missing"), fm.issues());
        assertTrue(fm.values().isEmpty());
        assertEquals("name: ghost\nThis is a plain markdown file.\n", fm.body());
    }

    @Test
    public void unclosedFence_isAWarning_andStillYieldsValues() {
        Frontmatter fm = Frontmatter.parse("---\nname: pdf-processing\ndescription: truncated doc\n");
        assertTrue(fm.isPresent(), "围栏没关上也应算 frontmatter present");
        assertEquals(Arrays.asList("warning:frontmatter-unclosed"), fm.issues());
        assertEquals("pdf-processing", fm.string("name"));
        assertEquals("truncated doc", fm.string("description"));
        assertEquals("", fm.body());
    }

    @Test
    public void bom_crlf_blankLinesBeforeFence_andDotEndFence_areTolerated() {
        Frontmatter fm = Frontmatter.parse("\ufeff\r\n\r\n---\r\nname: x\r\ndescription: y\r\n...\r\nbody line\r\n");
        assertEquals(Collections.<String>emptyList(), fm.issues());
        assertTrue(fm.isPresent());
        assertEquals("x", fm.string("name"));
        assertEquals("y", fm.string("description"));
        assertEquals("body line\n", fm.body());
    }

    @Test
    public void normalize_stripsBomAtPositionZeroAndUnifiesNewlines() {
        assertEquals("a\nb\nc", Frontmatter.normalize("\ufeffa\r\nb\rc"));
        assertEquals("keep \uFEFF inside", Frontmatter.normalize("keep \ufeff inside"));
    }

    @Test
    public void literalBlockScalar_keepsLineBreaksAndDeeperIndentation() {
        Frontmatter fm = Frontmatter.parse("---\ninstructions: |\n  step one\n    indented step\n  last step\nname: x\n---\n");
        assertEquals("step one\n  indented step\nlast step", fm.values().get("instructions"));
        assertEquals("x", fm.string("name"), "块标量之后的键不该被吞掉");
        assertEquals(Collections.<String>emptyList(), fm.issues());
    }

    @Test
    public void foldedBlockScalar_joinsTextLines_andKeepsBlankLineAsParagraphBreak() {
        Frontmatter fm = Frontmatter.parse("---\ndescription: >\n  line one\n  line two\n\n  second paragraph\nname: x\n---\n");
        assertEquals("line one line two\nsecond paragraph", fm.values().get("description"));
        assertEquals("x", fm.string("name"));
    }

    @Test
    public void chompMinusModifier_stripsWhatPlainModifierKeeps() {
        String raw = "---\ndescription: |\n\n  body\n  more\nname: x\n---\n";
        assertEquals("\nbody\nmore", Frontmatter.parse(raw).values().get("description"), "| 不该吃掉前导空行");
        assertEquals("body\nmore", Frontmatter.parse(raw.replace("description: |", "description: |-")).values().get("description"));
        assertEquals("one two", Frontmatter.parse("---\ndescription: >-\n  one\n  two\n---\n").values().get("description"));
    }

    @Test
    public void foldedContinuationLines_underAnEmptyKey_areJoinedIntoOneScalar() {
        // Qoder 内置 skill 的写法: description: 空, 说明在下一层缩进的续行里
        Frontmatter fm = Frontmatter.parse("---\nname: x\ndescription:\n  Do the thing\n  in two lines\nallowed-tools: Read\n---\n");
        assertEquals("Do the thing in two lines", fm.string("description"));
        assertEquals("Read", fm.string("allowed-tools"), "续行块不该把后面的同级键吃掉");
        assertEquals(Collections.<String>emptyList(), fm.issues());
    }

    @Test
    public void blockList_isCollectedAsItems_andQuotedItemWithSurvivesAsOneItem() {
        Frontmatter fm = Frontmatter.parse("---\nname: x\ntags:\n  - pdf\n  - \"data, import\"\n  - ocr\n---\n");
        assertEquals(Arrays.asList("pdf", "data, import", "ocr"), fm.list("tags"));
        assertNull(fm.string("tags"), "块列表值按标量读应给 null");
    }

    @Test
    public void flowList_commaScalar_semicolonScalar_andSpaceScalar_areHandled() {
        Frontmatter fm = Frontmatter.parse("---\n"
                + "tags: [pdf, ocr, pdf]\n"
                + "paths: a.md; b.md\n"
                + "allowed-tools: Read Write\n"
                + "---\n");
        assertEquals(Arrays.asList("pdf", "ocr"), fm.list("tags"), "行内列表去重且保序");
        assertEquals(Arrays.asList("a.md", "b.md"), fm.list("paths"));
        assertEquals(Arrays.asList("Read Write"), fm.list("allowed-tools"), "默认分隔是 [,;]");
        assertEquals(Arrays.asList("Read", "Write"), fm.list("allowed-tools", "\\s+"));
        assertEquals(Collections.<String>emptyList(), fm.list("ghost"));
    }

    @Test
    public void commaSeparatedScalar_isSplitAndDeduped() {
        Frontmatter fm = Frontmatter.parse("---\ntags: pdf, ocr, pdf\n---\n");
        assertEquals(Arrays.asList("pdf", "ocr"), fm.list("tags"));
    }

    @Test
    public void nestedMetadataMap_keepsEveryChild() {
        Frontmatter fm = Frontmatter.parse("---\nname: x\nmetadata:\n  version: \"1.2.0\"\n  author: zifang\n  category: docs\ndescription: d\n---\n");
        Object meta = fm.values().get("metadata");
        assertTrue(meta instanceof Map, "metadata 应解析成映射, 实际: " + meta);
        assertEquals(3, ((Map<?, ?>) meta).size(),
                "嵌套映射的每个子键都要保留(Frontmatter.java:152 把 blockEnd 当作 exclusive to 传给 parseMapping, 丢掉最后一个子键)");
        Map<String, String> m = fm.map("metadata");
        assertEquals("1.2.0", m.get("version"));
        assertEquals("zifang", m.get("author"));
        assertEquals("docs", m.get("category"));
        assertEquals("d", fm.string("description"));
    }

    @Test
    public void singleChildMetadataMap_isNotEmpty() {
        Frontmatter fm = Frontmatter.parse("---\nname: x\nmetadata:\n  version: 1.0.0\ndescription: d\n---\n");
        assertEquals("1.0.0", fm.map("metadata").get("version"),
                "只有一个子键的嵌套映射被整块丢掉(Frontmatter.java:152 的 to 少了一)");
    }

    @Test
    public void dottedFlatMetadataKeys_areCollectedIntoTheSameMap() {
        Frontmatter fm = Frontmatter.parse("---\nname: x\nmetadata.version: 2.1.0\nmetadata.author: zifang\n---\n");
        Map<String, String> m = fm.map("metadata");
        assertEquals("2.1.0", m.get("version"));
        assertEquals("zifang", m.get("author"));
        assertEquals("2.1.0", fm.string("metadata.version"), "扁平点键也应是可读的标量");
        assertEquals("x", fm.string("name"));
    }

    @Test
    public void nonSpecKeys_passThroughWithoutComplaint() {
        Frontmatter fm = Frontmatter.parse("---\nname: x\ndescriptionZh: 中文说明\nmodel: gpt-4\nwhen_to_use: 用户问小红书时\nicon: target\n---\n");
        assertEquals(Collections.<String>emptyList(), fm.issues());
        assertEquals("中文说明", fm.string("descriptionZh"));
        assertEquals("gpt-4", fm.string("model"));
        assertEquals("用户问小红书时", fm.string("when_to_use"));
        assertEquals("target", fm.string("icon"));
        assertEquals(5, fm.values().size());
    }

    @Test
    public void commentLinesAndBlankLines_areSkipped() {
        Frontmatter fm = Frontmatter.parse("---\n# full line comment\nname: x\n\n   # indented comment\ndescription: d\n---\n");
        assertEquals(Collections.<String>emptyList(), fm.issues());
        assertEquals(2, fm.values().size());
        assertEquals("x", fm.string("name"));
        assertEquals("d", fm.string("description"));
    }

    @Test
    public void valueThatLegitimatelyContainsHash_isKeptVerbatim() {
        Frontmatter fm = Frontmatter.parse("---\nname: c-sharp\ndescription: Use # tags and C# notes\n---\n");
        assertEquals("c-sharp", fm.string("name"));
        assertEquals("Use # tags and C# notes", fm.string("description"));
    }

    @Test
    public void tabAfterColonIsAValueSeparator_tabIndentIsOneColumnOfIndent() {
        Frontmatter fm = Frontmatter.parse("---\nname:\tpdf-processing\ntags:\n\t- pdf\n\t- ocr\n---\n");
        assertEquals("pdf-processing", fm.string("name"));
        assertEquals(Arrays.asList("pdf", "ocr"), fm.list("tags"));
        assertEquals(Collections.<String>emptyList(), fm.issues());
    }

    @Test
    public void emptyDocumentAndNull_areReportedNotThrown() {
        Frontmatter fm = Frontmatter.parse("");
        assertFalse(fm.isPresent());
        assertEquals(Arrays.asList("error:frontmatter-missing"), fm.issues());
        assertEquals("", fm.body());
        assertTrue(fm.values().isEmpty());

        Frontmatter ws = Frontmatter.parse("   \n\t\n  \n");
        assertFalse(ws.isPresent());
        assertEquals(Arrays.asList("error:frontmatter-missing"), ws.issues());

        Frontmatter nul = Frontmatter.parse(null);
        assertFalse(nul.isPresent());
        assertEquals(Arrays.asList("error:empty-content"), nul.issues());
        assertEquals("", nul.body());
    }

    @Test
    public void presentButEmptyValue_isDistinctFromAMissingKey() {
        Frontmatter fm = Frontmatter.parse("---\nname:\ndescription: real text\n---\n");
        assertTrue(fm.values().containsKey("name"), "空值键仍应存在");
        assertEquals("", fm.values().get("name"));
        assertNull(fm.string("name"), "string() 把空值读成 null");
        assertEquals("real text", fm.string("description"));
        assertEquals("real text", fm.firstOf("name", "ghost", "description"));
        assertNull(fm.firstOf("name", "ghost"));
        assertEquals(Collections.<String>emptyList(), fm.list("name"));

        Frontmatter last = Frontmatter.parse("---\nname:\n---\n");
        assertEquals("", last.values().get("name"), "文档末尾的空值也应为空串而不是缺失");
    }

    @Test
    public void unparsableLines_becomeIssues_andNeighbourKeysStillParse() {
        Frontmatter fm = Frontmatter.parse("---\nthis is not yaml\nname: x\n随机 中文 行\ndescription: d\n---\n");
        assertEquals(Arrays.asList("warning:unparsed-line:this is not yaml", "warning:unparsed-line:随机 中文 行"), fm.issues());
        assertEquals("x", fm.string("name"));
        assertEquals("d", fm.string("description"));
    }

    @Test
    public void malformedDocuments_neverThrow_andOnlyEmitDocumentedIssueCodes() {
        String[] bad = new String[]{
                "- - -\n",
                "...\nname: x\n",
                "---\n: colon-first\nname\n",
                "---\ndescription: |\n",
                "---\nmetadata:\n- a\n- b\n---\n",
                "---\nkey: [unclosed\n",
                "---\nname: x\n\ttabbed-child: y\n",
                "---\nallowed-tools:\n- Bash(git:*)\n---\nbody\n",
                "---\n a\n\tb\n  c\n---\n",
                "null-ish doc without fence at all"
        };
        List<String> seen = new java.util.ArrayList<String>();
        for (String s : bad) {
            Frontmatter fm = Frontmatter.parse(s);
            for (String issue : fm.issues()) {
                assertTrue(issue.startsWith("error:") || issue.startsWith("warning:") || issue.startsWith("info:"),
                        "issue 必须在词表内, 实际: " + issue);
                seen.add(issue);
            }
            fm.values().size();
            fm.body().length();
            fm.list("name");
            fm.map("metadata");
            fm.string("description");
        }
        assertTrue(seen.contains("error:frontmatter-missing"), "无围栏样本应记 error:frontmatter-missing, 实际: " + seen);
        assertTrue(seen.contains("warning:frontmatter-unclosed"), "未闭合样本应记 warning:frontmatter-unclosed, 实际: " + seen);
    }

    @Test
    public void accessors_areReadOnlyViews_andEmptyIsAFullyInertInstance() {
        Frontmatter fm = Frontmatter.parse("---\nname: x\n---\n");
        assertThrows(UnsupportedOperationException.class, () -> fm.values().put("k", "v"));
        assertThrows(UnsupportedOperationException.class, () -> fm.issues().add("i"));

        Frontmatter e = Frontmatter.empty();
        assertFalse(e.isPresent());
        assertTrue(e.values().isEmpty());
        assertEquals("", e.body());
        assertTrue(e.issues().isEmpty());
        assertNull(e.string("name"));
        assertEquals(Collections.<String>emptyList(), e.list("tags"));
        assertTrue(e.map("metadata").isEmpty());
    }

    @Test
    public void unquote_isSymmetricAndLeavesUnquotedTextAlone() {
        assertEquals("a: b", Frontmatter.unquote("\"a: b\""));
        assertEquals("a: b", Frontmatter.unquote("'a: b'"));
        assertEquals("a: b", Frontmatter.unquote("  a: b  "));
        assertEquals("\"a: b", Frontmatter.unquote("\"a: b"));
        assertEquals("", Frontmatter.unquote("\"\""));
        assertNull(Frontmatter.unquote(null));
    }
}
