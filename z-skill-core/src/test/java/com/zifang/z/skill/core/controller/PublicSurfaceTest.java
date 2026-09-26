package com.zifang.z.skill.core.controller;

import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.dto.SkillResourceDto;
import com.zifang.z.skill.api.spec.SkillFormat;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判别器本体的口径测试.
 *
 * <p>出口级测试({@code SkillControllerTest.publicEndpointsNeverCarryHostPaths})只能覆盖到夹具凑得出来的
 * 那几种写法; UNC 与 Windows 盘符在这台机器上永远没有猎物 —— 摘掉那两行不会有任何测试变红, 于是
 * "四种写法都认"这句话没有尺子量过(而 {@link PublicSurface} 的 javadoc 就是这么承诺的). 所以判别器
 * 单独钉一层.
 *
 * <p>反向同样要钉: 把绝对路径的判断写宽一点, {@code pdf/SKILL.md} 这类主文件位就会被误抹,
 * 市场页与 skills.sh 客户端同时瞎掉.
 */
public class PublicSurfaceTest {

    @Test
    public void hostLayoutsAreRecognisedInEverySpelling() {
        for (String v : Arrays.asList(
                "file:///Users/demo/.claude/skills/pdf/SKILL.md",
                "file:/D:/skills/pdf/SKILL.md",
                "/Users/demo/.qoder/skills/pdf/SKILL.md",
                "/var/folders/zz/tmp/skills/pdf/SKILL.md",
                "\\\\nas01\\share\\skills\\pdf\\SKILL.md",
                "C:\\Users\\demo\\skills\\pdf\\SKILL.md",
                "d:\\skills\\pdf\\SKILL.md")) {
            assertTrue(PublicSurface.isLocalPath(v), "这仍然是一台机器的目录布局: " + v);
            assertNull(PublicSurface.stripIfLocal(v), "公共面该给 null: " + v);
        }
    }

    @Test
    public void clientUsableAddressesSurviveTheStrip() {
        for (String v : Arrays.asList(
                "https://acme.example/.well-known/agent-skills/weather/SKILL.md",
                "anthropics/pdf",
                "acme-registry/weather-lookup",
                "owner/repo@skill",
                "pdf-processing/SKILL.md",
                "references/notes.md",
                "./scripts/run.sh",
                "hash-pdf")) {
            assertFalse(PublicSurface.isLocalPath(v), "这不是宿主机布局, 抹掉它就是抹掉客户端要用的信息: " + v);
            assertSame(v, PublicSurface.stripIfLocal(v), "原样返回, 不许改写: " + v);
        }
        assertNull(PublicSurface.stripIfLocal(null), "null 进 null 出, 不许抛");
    }

    /** 抹一个字段不许顺带丢别的: 视图重建(toBuilder)时最容易掉队的是集合与计数类字段. */
    @Test
    public void redressingASkillKeepsEverythingTheMarketplacePageShows() {
        SkillDto local = SkillDto.builder()
                .id("pdf").slug("pdf").name("pdf").description("Extract PDF text")
                .source("anthropics").sourceType("local").origin("/Users/demo/.claude/skills/pdf/SKILL.md")
                .skillFilePath("pdf/SKILL.md").format(SkillFormat.AGENT_SKILLS)
                .resources(Collections.singletonList(
                        new SkillResourceDto("references/notes.md", "reference", 8L)))
                .aliases(Arrays.asList("anthropic/pdf"))
                .contentHash("hash-pdf").installCount(7).riskLevel(SkillDto.RISK_LOW)
                .build();

        SkillDto out = PublicSurface.skill(local);
        assertNull(out.getOrigin(), "本机目录还带在公共面上");
        assertEquals("pdf/SKILL.md", out.getSkillFilePath(), "相对主文件位要照给");
        assertEquals("references/notes.md", out.getResources().get(0).getPath(), "相对资源路径要照给");
        assertEquals(Arrays.asList("anthropic/pdf"), out.getAliases());
        assertEquals("hash-pdf", out.getContentHash());
        assertEquals(7, out.getInstallCount());
        assertEquals(SkillDto.RISK_LOW, out.getRiskLevel());
        assertEquals(SkillFormat.AGENT_SKILLS, out.getFormat());

        SkillDto remote = local.toBuilder().origin("anthropics/pdf").build();
        assertSame(remote, PublicSurface.skill(remote), "没有本机路径时不许白重建一份 dto");
    }
}
