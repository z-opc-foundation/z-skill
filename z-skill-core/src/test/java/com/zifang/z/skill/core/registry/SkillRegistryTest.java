package com.zifang.z.skill.core.registry;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public class SkillRegistryTest {

    @Test
    public void register_and_list() {
        SkillRegistry r = new SkillRegistry();
        r.register(new SkillRegistry.SkillEntry("code-review", "对 PR 做代码审查", "1.0.0",
                "development", Arrays.asList("code", "review"), "yuku123", "PR review 时", 3));
        r.register(new SkillRegistry.SkillEntry("sql-query", "SQL 数据查询", "1.0.0",
                "data", Arrays.asList("sql"), "yuku123", "SQL 查询时", 5));
        assertEquals(2, r.skillCount());
        assertEquals(0, r.installedCount());
        assertEquals(2, r.listAll().size());
    }

    @Test
    public void list_by_category() {
        SkillRegistry r = new SkillRegistry();
        r.register(new SkillRegistry.SkillEntry("a", null, "1", "dev", null, null, null, 0));
        r.register(new SkillRegistry.SkillEntry("b", null, "1", "data", null, null, null, 0));
        r.register(new SkillRegistry.SkillEntry("c", null, "1", "dev", null, null, null, 0));
        assertEquals(2, r.listByCategory("dev").size());
        assertEquals(1, r.listByCategory("data").size());
    }

    @Test
    public void install_uninstall() {
        SkillRegistry r = new SkillRegistry();
        r.register(new SkillRegistry.SkillEntry("a", null, "1", "dev", null, null, null, 0));
        assertFalse(r.isInstalled("a"));
        r.install("a", "admin", "marketplace");
        assertTrue(r.isInstalled("a"));
        assertEquals(1, r.installedCount());
        assertEquals(1, r.listInstalled().size());
        r.uninstall("a");
        assertFalse(r.isInstalled("a"));
    }

    @Test
    public void install_unknown_throws() {
        SkillRegistry r = new SkillRegistry();
        try {
            r.install("ghost", "admin", "marketplace");
            fail("expected exception");
        } catch (com.zifang.z.skill.api.exception.SkillException e) {
            assertEquals(404, e.getCode());
        }
    }

    @Test
    public void install_twice_throws() {
        SkillRegistry r = new SkillRegistry();
        r.register(new SkillRegistry.SkillEntry("a", null, "1", "dev", null, null, null, 0));
        r.install("a", "admin", "marketplace");
        try {
            r.install("a", "admin", "marketplace");
            fail("expected exception");
        } catch (com.zifang.z.skill.api.exception.SkillException e) {
            assertEquals(409, e.getCode());
        }
    }

    @Test
    public void categories() {
        SkillRegistry r = new SkillRegistry();
        r.register(new SkillRegistry.SkillEntry("a", null, "1", "dev", null, null, null, 0));
        r.register(new SkillRegistry.SkillEntry("b", null, "1", "data", null, null, null, 0));
        r.register(new SkillRegistry.SkillEntry("c", null, "1", "dev", null, null, null, 0));
        assertEquals(2, r.listCategories().size());
        assertTrue(r.listCategories().contains("dev"));
        assertTrue(r.listCategories().contains("data"));
    }
}