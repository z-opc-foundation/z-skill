package com.zifang.z.skill.core.aggregate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.skill.api.dto.AggregateReportDto;
import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.spec.SkillFormat;
import com.zifang.z.skill.core.discover.SkillSource;
import com.zifang.z.skill.core.registry.SkillRegistry;
import com.zifang.z.skill.core.scan.SkillSecurityScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命名空间与 id 稳定性 — 聚合器给撞名条目起的标识不能带 {@code #2#2} 这种二次后缀,
 * 第二次刷新也不能把同一份内容换成另一个 id.
 */
public class AggregatorIdStabilityTest {

    @TempDir
    Path tmp;

    private SkillAggregator aggregator(SkillRegistry registry) {
        return new SkillAggregator(registry, new ObjectMapper(), new SkillSecurityScanner());
    }

    private void writeSkill(String sourceDir, String skillDir, String name, String body) throws IOException {
        Path dir = tmp.resolve(sourceDir).resolve(skillDir);
        Files.createDirectories(dir);
        Files.write(dir.resolve("SKILL.md"),
                ("---\nname: " + name + "\ndescription: " + body + "\n---\n\n" + body + "\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private SkillSource source(String id, int priority) {
        return new SkillSource(id, SkillFormat.AGENT_SKILLS, tmp.resolve(id).toString(), priority);
    }

    @Test
    void divergentSameSlugSiblings_getOneSuffixAtMost() throws Exception {
        writeSkill("market-a", "pdf-processing", "pdf-processing", "甲内容");
        writeSkill("market-b", "pdf-processing", "pdf-processing", "乙内容");
        writeSkill("market-c", "pdf-processing", "pdf-processing", "丙内容");

        SkillRegistry registry = new SkillRegistry();
        SkillAggregator agg = aggregator(registry);
        int priority = 10;
        for (String id : new String[]{"market-a", "market-b", "market-c"}) {
            agg.addSource(source(id, priority));
            priority += 10;
        }
        AggregateReportDto report = agg.refresh();

        SortedSet<String> ids = new TreeSet<String>(registry.snapshot().keySet());
        assertEquals(3, report.getSkillCount(), "同名不同内容都保留: " + ids);
        assertEquals(new TreeSet<String>(Arrays.asList(
                "market-a/pdf-processing", "market-b/pdf-processing", "market-c/pdf-processing")), ids);
        for (String id : ids) {
            assertFalse(id.matches(".*#\\d+#\\d+.*"), "id 不该长出二次后缀: " + id);
        }
        assertTrue(report.getConflictCount() >= 2, "撞名要记冲突: " + report.getConflictCount());
    }

    @Test
    void identicalContentAcrossSources_foldsIntoOneEntryWithAliases() throws Exception {
        String body = "同一份内容被两个平台目录互链";
        writeSkill("claude-dir", "sync", "sync", body);
        writeSkill("agents-dir", "sync", "sync", body);

        SkillRegistry registry = new SkillRegistry();
        SkillAggregator agg = aggregator(registry);
        agg.addSource(source("claude-dir", 10));
        agg.addSource(source("agents-dir", 20));
        AggregateReportDto report = agg.refresh();

        assertEquals(1, report.getSkillCount());
        assertEquals(1, report.getDedupedCount());
        SkillDto kept = registry.snapshot().values().iterator().next();
        List<String> aliases = new ArrayList<String>(kept.getAliases());
        assertTrue(aliases.contains("sync"), aliases.toString());
        assertTrue(aliases.contains("claude-dir/sync") || aliases.contains("agents-dir/sync"), aliases.toString());
    }

    @Test
    void secondRefresh_isIdempotent() throws Exception {
        writeSkill("stable", "code-review", "code-review", "对 PR 做代码审查");
        writeSkill("mirror", "code-review", "code-review", "另一个版本的内容");

        SkillRegistry registry = new SkillRegistry();
        SkillAggregator agg = aggregator(registry);
        agg.addSource(source("stable", 10));
        agg.addSource(source("mirror", 20));
        AggregateReportDto first = agg.refresh();
        SortedSet<String> firstIds = new TreeSet<String>(registry.snapshot().keySet());
        AggregateReportDto second = agg.refresh();
        SortedSet<String> secondIds = new TreeSet<String>(registry.snapshot().keySet());

        assertEquals(first.getSkillCount(), second.getSkillCount());
        assertEquals(firstIds, secondIds, "重复刷新不该让 id 漂移: " + firstIds + " vs " + secondIds);
    }

    @Test
    void unrelatedFiles_inTheSameDirectoryAreIgnoredWithoutKillingTheRun() throws Exception {
        writeSkill("mixed", "pdf", "pdf", "正文");
        Files.write(tmp.resolve("mixed").resolve("notes.txt"), "不是 skill".getBytes(StandardCharsets.UTF_8));

        SkillRegistry registry = new SkillRegistry();
        SkillAggregator agg = aggregator(registry);
        agg.addSource(source("mixed", 10));
        AggregateReportDto report = agg.refresh();

        assertEquals(1, report.getSkillCount());
        assertTrue(registry.snapshot().containsKey("pdf"));
    }
}
