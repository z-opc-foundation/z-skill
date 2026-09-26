package com.zifang.z.skill.core.registry;

import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.dto.SkillInstallDto;
import com.zifang.z.skill.api.exception.SkillException;
import com.zifang.z.skill.api.spec.SkillFormat;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SkillRegistryTest {

    private static SkillDto skill(String id, String slug, String name, String hash, String... aliases) {
        return SkillDto.builder()
                .id(id).slug(slug).name(name)
                .description("desc of " + slug)
                .version("1.0.0")
                .source("local-fs").sourceType("local")
                .origin("file://test")
                .format(SkillFormat.AGENT_SKILLS)
                .contentHash(hash)
                .aliases(Arrays.asList(aliases))
                .build();
    }

    private static SkillDto pdf() {
        return skill("pdf-processing", "pdf-processing", "pdf-processing", "hash-a", "legacy/pdf");
    }

    private static SkillDto sql() {
        return skill("site/data-import", "data-import", "DataImport", "hash-b");
    }

    @Test
    public void listAll_isIdSorted_andSnapshotIsReadOnly() {
        SkillRegistry r = new SkillRegistry();
        r.register(sql());
        r.register(pdf());
        assertEquals(Arrays.asList("pdf-processing", "site/data-import"), ids(r.listAll()));
        assertEquals(2, r.snapshot().size());
        assertThrows(UnsupportedOperationException.class,
                () -> r.snapshot().put("x", pdf()));
    }

    @Test
    public void get_resolvesByIdSlugNameAndAlias_caseInsensitive() {
        SkillRegistry r = new SkillRegistry();
        r.register(pdf());
        r.register(sql());
        assertTrue(r.get("site/data-import").isPresent());
        assertTrue(r.get("data-import").isPresent());
        assertTrue(r.get("DataImport").isPresent(), "name 索引应大小写不敏感");
        assertTrue(r.get("SITE/DATA-IMPORT").isPresent(), "id 也应能按小写键命中");
        assertTrue(r.get("legacy/pdf").isPresent(), "alias 也应能命中");
        assertEquals("pdf-processing", r.get("LEGACY/PDF").get().getId());
        assertFalse(r.get("ghost").isPresent());
        assertFalse(r.get(null).isPresent());
        assertEquals(404, assertThrows(SkillException.class, () -> r.require("ghost")).getCode());
    }

    @Test
    public void unregister_reassignsASharedKeyToTheSoleSurvivingClaimant() {
        SkillRegistry r = new SkillRegistry();
        // 两条 skill 抢同一个写法: 先到先得; 持有者撤下后写法归唯一的剩余认领者, 不留死键也不指回幽灵
        r.register(skill("a", "shared", "A", "h1"));
        r.register(skill("b", "shared", "B", "h2"));
        assertEquals("a", r.get("shared").get().getId());
        r.unregister("a");
        assertEquals("b", r.get("shared").get().getId());
        assertFalse(r.get("A").isPresent());
        assertFalse(r.get("a").isPresent());
    }

    @Test
    public void register_requiresId() {
        SkillRegistry r = new SkillRegistry();
        assertThrows(IllegalArgumentException.class, () -> r.register(SkillDto.builder().build()));
        assertThrows(IllegalArgumentException.class, () -> r.register(null));
    }

    @Test
    public void install_uninstall_roundTrip() {
        SkillRegistry r = new SkillRegistry();
        r.register(pdf());
        assertFalse(r.isInstalled("pdf-processing"));
        assertEquals(0, r.installedCount());

        SkillInstallDto rec = r.install("pdf-processing", "admin", "marketplace");
        assertEquals("pdf-processing", rec.getSkillId());
        assertEquals("pdf-processing", rec.getSkillName());
        assertEquals("1.0.0", rec.getVersion());
        assertEquals("admin", rec.getInstalledBy());
        assertEquals("marketplace", rec.getSource());
        assertEquals("hash-a", rec.getContentHash(), "安装记录要带 hash, 否则无法做上游漂移检测");
        assertEquals("file://test", rec.getInstallRef());

        assertTrue(r.isInstalled("pdf-processing"));
        assertEquals(1, r.installedCount());
        assertEquals(1, r.installRecords().size());
        assertEquals(1, r.installedSkills().size());
        assertTrue(r.getInstallRecord("pdf-processing").isPresent());

        assertEquals(rec.getSkillId(), r.uninstall("pdf-processing").getSkillId());
        assertFalse(r.isInstalled("pdf-processing"));
        assertEquals(404, assertThrows(SkillException.class, () -> r.uninstall("pdf-processing")).getCode());
    }

    @Test
    public void install_bySlugAlias_andTwiceConflicts() {
        SkillRegistry r = new SkillRegistry();
        r.register(pdf());
        r.install("pdf-processing", "admin", null);
        assertEquals("marketplace", r.getInstallRecord("pdf-processing").get().getSource(), "channel 为空时回落 marketplace");
        SkillException e = assertThrows(SkillException.class, () -> r.install("pdf-processing", "admin", "cli"));
        assertEquals(409, e.getCode());
        assertEquals("already_installed", e.getErrorId());
    }

    @Test
    public void install_unknown_throws404() {
        SkillRegistry r = new SkillRegistry();
        SkillException e = assertThrows(SkillException.class, () -> r.install("ghost", "admin", "marketplace"));
        assertEquals(404, e.getCode());
        assertEquals("skill_not_found", e.getErrorId());
    }

    @Test
    public void unregister_dropsSkillAndInstall() {
        SkillRegistry r = new SkillRegistry();
        r.register(pdf());
        r.install("pdf-processing", "admin", "marketplace");
        r.unregister("pdf-processing");
        assertEquals(0, r.skillCount());
        assertEquals(0, r.installedCount());
    }

    @Test
    public void replaceAll_computesAddedChangedRemovedDelta() {
        SkillRegistry r = new SkillRegistry();
        Map<String, SkillDto> first = map(pdf(), sql());
        r.replaceAll(first, Collections.emptyList(), null);
        assertEquals(2, r.skillCount());
        assertEquals(Arrays.asList("pdf-processing", "site/data-import"), r.getAdded());
        assertTrue(r.getChanged().isEmpty());
        assertTrue(r.getRemoved().isEmpty());

        // 同名不同 hash = changed; 撤掉一个 = removed; 新增一个 = added
        Map<String, SkillDto> second = map(
                skill("pdf-processing", "pdf-processing", "pdf-processing", "hash-a2"),
                skill("code-review", "code-review", "code-review", "hash-c"));
        r.replaceAll(second, Collections.emptyList(), null);
        assertEquals(Collections.singletonList("code-review"), r.getAdded());
        assertEquals(Collections.singletonList("pdf-processing"), r.getChanged());
        assertEquals(Collections.singletonList("site/data-import"), r.getRemoved());
        assertEquals(2, r.skillCount());
        assertFalse(r.get("data-import").isPresent(), "removed 之后旧 alias 不该还能命中");
    }

    @Test
    public void replaceAll_identicalContent_isNotReportedAsChanged() {
        SkillRegistry r = new SkillRegistry();
        r.replaceAll(map(pdf()), Collections.emptyList(), null);
        r.replaceAll(map(pdf()), Collections.emptyList(), null);
        assertTrue(r.getAdded().isEmpty());
        assertTrue(r.getChanged().isEmpty(), "hash 相同却报 changed 会让上游漂移检测全是噪音");
        assertTrue(r.getRemoved().isEmpty());
    }

    @Test
    public void replaceAll_keepsDanglingInstalls_ofRemovedSkills() {
        SkillRegistry r = new SkillRegistry();
        r.replaceAll(map(pdf(), sql()), Collections.emptyList(), null);
        r.install("site/data-import", "admin", "marketplace");
        r.replaceAll(map(pdf()), Collections.emptyList(), null);

        assertEquals(Collections.singletonList("site/data-import"), r.danglingInstalls(),
                "用户装过的东西不能静默回收, 要显式挂 dangling 让控制面提示");
        assertEquals(1, r.installedCount());
        assertEquals(Collections.emptyList(), r.installedSkills());
    }

    @Test
    public void replaceAll_exposesSourcesAndNullSafe() {
        SkillRegistry r = new SkillRegistry();
        r.replaceAll(map(pdf()), null, null);
        assertTrue(r.getSources().isEmpty());
        assertEquals(null, r.getLastReport());
    }

    @Test
    public void clear_emptiesCatalogButKeepsInstallsIndexCoherent() {
        SkillRegistry r = new SkillRegistry();
        r.replaceAll(map(pdf()), Collections.emptyList(), null);
        r.install("pdf-processing", "admin", "marketplace");
        r.clear();
        assertEquals(0, r.skillCount());
        assertFalse(r.get("pdf-processing").isPresent());
        assertEquals(Collections.singletonList("pdf-processing"), r.danglingInstalls());
    }

    /**
     * installed 只有一个真相源: 注册表的安装表. 它必须同时出现在 get / require / listAll /
     * snapshot / installedSkills 五个出口上 —— 早先只有检索层盖章, 于是 /skill/installed 里
     * 每一行都写着 installed=false, 而同一份响应的 records 里就躺着那条安装记录.
     */
    @Test
    public void installedFlagAgreesAcrossEveryReadPath() {
        SkillRegistry r = new SkillRegistry();
        r.replaceAll(map(pdf(), sql()), Collections.emptyList(), null);

        assertEquals(Arrays.asList(false, false), installedOf(r.listAll()), "没装时五个出口都该是 false");
        r.install("pdf-processing", "tester", "marketplace");

        assertTrue(r.get("pdf-processing").get().isInstalled(), "get 漏盖章");
        assertTrue(r.require("legacy/pdf").isInstalled(), "alias 命中那条路也要盖章, 不能只盖 id 直查");
        assertTrue(r.require("pdf-processing").isInstalled(), "require 漏盖章");
        assertEquals(Arrays.asList(true, false), installedOf(r.listAll()), "listAll 漏盖章");
        // snapshot 背后是 ConcurrentHashMap, 值的迭代顺序不保证 ⇒ 只能按 id 取, 不能按位置断言
        assertEquals(Boolean.TRUE, installedMap(r.snapshot().values()).get("pdf-processing"), "snapshot 漏盖章: 绕过检索层的出口全靠它");
        assertEquals(Boolean.FALSE, installedMap(r.snapshot().values()).get("site/data-import"));
        assertEquals(Collections.singletonList(true), installedOf(r.installedSkills()),
                "「我的安装」里出现 installed=false 是自相矛盾");
        assertFalse(r.get("site/data-import").get().isInstalled(), "没装的那条不能被顺手点亮");

        r.uninstall("pdf-processing");
        assertFalse(r.get("pdf-processing").get().isInstalled(), "卸载后必须回到 false: 只装不掉的章等于没盖章");
        assertEquals(Collections.emptyList(), r.installedSkills());
    }

    /** 上游自称"已安装"不算数: 本机没这条安装记录就得是 false. */
    @Test
    public void upstreamClaimedInstalled_isOverriddenByLocalFact() {
        SkillRegistry r = new SkillRegistry();
        SkillDto claimsInstalled = pdf().toBuilder().installed(true).build();
        r.replaceAll(map(claimsInstalled, sql()), Collections.emptyList(), null);

        assertFalse(r.get("pdf-processing").get().isInstalled(), "来源 payload 自报 installed=true 不能当本机事实");
        assertFalse(r.require("pdf-processing").isInstalled());
        assertEquals(Arrays.asList(false, false), installedOf(r.listAll()));
        assertTrue(r.installedSkills().isEmpty());
        assertEquals(0, r.installedCount());

        r.install("pdf-processing", "tester", "marketplace");
        assertTrue(r.require("pdf-processing").isInstalled(), "真装上了才亮, 且亮的必须是本机事实而非上游声明");
    }

    private static Map<String, Boolean> installedMap(Iterable<SkillDto> dtos) {
        Map<String, Boolean> out = new LinkedHashMap<String, Boolean>();
        for (SkillDto d : dtos) out.put(d.getId(), d.isInstalled());
        return out;
    }

    private static List<Boolean> installedOf(Iterable<SkillDto> dtos) {
        List<Boolean> out = new java.util.ArrayList<Boolean>();
        for (SkillDto d : dtos) out.add(d.isInstalled());
        return out;
    }

    private static Map<String, SkillDto> map(SkillDto... skills) {
        Map<String, SkillDto> m = new LinkedHashMap<String, SkillDto>();
        for (SkillDto s : skills) m.put(s.getId(), s);
        return m;
    }

    private static List<String> ids(List<SkillDto> list) {
        List<String> out = new java.util.ArrayList<String>();
        for (SkillDto d : list) out.add(d.getId());
        return out;
    }
}
