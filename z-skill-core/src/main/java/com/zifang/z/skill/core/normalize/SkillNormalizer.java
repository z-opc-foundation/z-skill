package com.zifang.z.skill.core.normalize;

import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.dto.SkillIssueDto;
import com.zifang.z.skill.api.spec.SkillFormat;
import com.zifang.z.skill.core.discover.RawSkill;
import com.zifang.z.skill.core.discover.SkillSource;

import java.util.List;

/**
 * 归一化 SPI: 一种平台格式一个实现, 把 {@link RawSkill} 折成内部 {@link SkillDto}.
 *
 * <p>新增一个平台只需要加一个实现 + 让扫描器识别出它的 format, 聚合/检索/安装链路不用动.
 */
public interface SkillNormalizer {

    SkillFormat format();

    /**
     * @return 归一化结果; 空列表表示该候选被判为不可收录(问题已写入 issues)
     */
    List<SkillDto> normalize(RawSkill raw, SkillSource source, List<SkillIssueDto> issues);
}
