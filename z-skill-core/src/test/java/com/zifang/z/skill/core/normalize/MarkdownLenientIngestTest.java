package com.zifang.z.skill.core.normalize;

import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.dto.SkillIssueDto;
import com.zifang.z.skill.api.spec.SkillFormat;
import com.zifang.z.skill.core.discover.RawSkill;
import com.zifang.z.skill.core.discover.SkillSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宽松收录策略 — "聚合别人的目录"时, 不合规的条目要收进来并记账, 而不是悄悄丢掉.
 *
 * <p>回归的是一条真实缺陷: 本仓 lead 的 10 个 SKILL.md 只有正文没有 frontmatter,
 * 老实现整条 return 空列表, 于是"聚合了 10 个技能的平台"显示为空.
 */
public class MarkdownLenientIngestTest {

    private final MarkdownSkillNormalizer normalizer = new MarkdownSkillNormalizer();
    private final SkillSource source = new SkillSource("fs-lead", SkillFormat.AGENT_SKILLS, "/tmp/lead", 10);

    private List<SkillDto> ingest(String text, String dirName, List<SkillIssueDto> issues) {
        RawSkill raw = new RawSkill()
                .format(SkillFormat.AGENT_SKILLS)
                .origin("/tmp/lead/" + dirName + "/SKILL.md")
                .entryRelative(dirName + "/SKILL.md")
                .dirName(dirName)
                .fallbackName(dirName)
                .text(text);
        return normalizer.normalize(raw, source, issues);
    }

    @Test
    void skillMdWithoutFrontmatter_isStillCollected() {
        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
        String text = "# sync — 把 lead/templates 同步到所有产品仓\n\n用来同步模板.\n";
        List<SkillDto> out = ingest(text, "sync", issues);

        assertEquals(1, out.size(), "没有 frontmatter 也要收录, 否则整个平台看起来是空的");
        SkillDto dto = out.get(0);
        assertEquals("sync", dto.getSlug());
        assertEquals("sync", dto.getName());
        assertNotNull(dto.getDescription());
        assertTrue(dto.getDescription().contains("sync"), "描述应从正文首标题兜底: " + dto.getDescription());
        assertTrue(dto.getBodyLines() >= 2);
        assertEquals("fs-lead", dto.getSource());
        assertEquals(SkillFormat.AGENT_SKILLS, dto.getFormat());
        assertNotNull(dto.getContentHash());

        assertTrue(hasIssue(issues, "warning", "frontmatter-missing"), "不合规必须记账: " + codes(issues));
        assertTrue(hasIssue(issues, "info", "description-derived-from-body"), codes(issues).toString());
    }

    @Test
    void bodyWithoutHeading_fallsBackToFirstProseLine() {
        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
        List<SkillDto> out = ingest("直接部署 250-opc 前后端\n\n步骤如下.\n", "deploy", issues);

        assertEquals(1, out.size());
        assertEquals("直接部署 250-opc 前后端", out.get(0).getDescription());
    }

    @Test
    void codeFenceAndQuoteLines_areNotMistakenForDescriptions() {
        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
        List<SkillDto> out = ingest("```bash\nrm -rf /tmp/x\n```\n\n真正的说明段落.\n", "fenced", issues);

        assertEquals(1, out.size());
        assertEquals("真正的说明段落.", out.get(0).getDescription());
    }

    @Test
    void missingDescriptionButEmptyBody_isCollectedAndFlagged() {
        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
        List<SkillDto> out = ingest("---\nname: bare\ndescription: \"\"\n---\n\n", "bare", issues);

        assertEquals(1, out.size(), "description 缺失只记账, 不丢弃条目");
        // 条目进了目录, 所以只能是 warning —— error 的口径是"这条没进目录"(见 SkillIssueDto)
        assertTrue(hasIssue(issues, "warning", "description-missing"), codes(issues).toString());
        assertEquals("", out.get(0).getDescription());
    }

    @Test
    void unsanitizeableName_isTheOnlyHardDrop() {
        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
        List<SkillDto> out = ingest("---\nname: 中文\ndescription: d\n---\n\nbody\n", "ignored", issues);

        assertTrue(out.isEmpty(), "连标识都给不出来时才丢");
        assertTrue(hasIssue(issues, "error", "name-unsanitizeable"), codes(issues).toString());
    }

    @Test
    void issueCodes_areBoundedBareTokens() {
        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
        ingest("---\nname: Bad_Name\ndescription: d\nnot a yaml pair\n---\n\nbody\n", "bad-name", issues);

        List<String> seen = codes(issues);
        assertFalse(seen.isEmpty(), "脏 frontmatter 必须留痕: " + seen);
        boolean sawUnparsedLine = false;
        boolean sawNonConforming = false;
        for (SkillIssueDto issue : issues) {
            String code = issue.getCode();
            // code 里既不许混进 severity, 也不许混进取值/命名空间 —— 否则"按码计数"会一条一名
            assertFalse(code.contains(":"), "code 必须是裸码: " + code);
            assertFalse(code.startsWith("error"), "severity 不许进 code: " + code);
            assertFalse(code.contains("->"), "取值不许进 code: " + code);
            if ("unparsed-line".equals(code)) {
                sawUnparsedLine = true;
                assertEquals("warning", issue.getSeverity(), code);
                assertTrue(issue.getMessage().contains("not a yaml pair"), "原文要留在 message 里: " + issue);
            }
            if ("name-non-conforming".equals(code)) {
                sawNonConforming = true;
                assertEquals("warning", issue.getSeverity(), code);
            }
        }
        assertTrue(sawUnparsedLine, "解析器的 note 要转成 bare code unparsed-line: " + seen);
        assertTrue(sawNonConforming, "规范问题要转成 bare code name-non-conforming: " + seen);
    }

    @Test
    void conformingSkill_hasNoCompatibilityNoise() {
        List<SkillIssueDto> issues = new ArrayList<SkillIssueDto>();
        String text = "---\nname: pdf-processing\ndescription: 处理 PDF\nallowed-tools: Read Write\n---\n\n# 用法\n";
        List<SkillDto> out = ingest(text, "pdf-processing", issues);

        assertEquals(1, out.size());
        assertEquals("处理 PDF", out.get(0).getDescription());
        assertEquals(2, out.get(0).getAllowedTools().size());
        for (SkillIssueDto issue : issues) {
            assertEquals("info", issue.getSeverity(), "合规条目不该被记账: " + issue.getCode());
        }
    }

    private static boolean hasIssue(List<SkillIssueDto> issues, String severity, String codePrefix) {
        for (SkillIssueDto issue : issues) {
            if (severity.equals(issue.getSeverity()) && issue.getCode().startsWith(codePrefix)) return true;
        }
        return false;
    }

    private static List<String> codes(List<SkillIssueDto> issues) {
        List<String> out = new ArrayList<String>();
        for (SkillIssueDto issue : issues) out.add(issue.getSeverity() + ":" + issue.getCode());
        return out;
    }
}
