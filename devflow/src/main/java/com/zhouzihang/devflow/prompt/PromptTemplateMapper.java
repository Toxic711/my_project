package com.zhouzihang.devflow.prompt;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** Prompt 模板 Mapper。 */
@Mapper
public interface PromptTemplateMapper extends BaseMapper<PromptTemplate> {

    /** 某 code 的最新版本（LIMIT 1 避免 selectOne 多行异常）。 */
    default PromptTemplate selectLatest(String code) {
        QueryWrapper<PromptTemplate> qw = new QueryWrapper<>();
        qw.eq("code", code).orderByDesc("version").last("LIMIT 1");
        return selectOne(qw);
    }

    /** 某 code 的最大版本号，不存在返回 0。 */
    default int maxVersion(String code) {
        QueryWrapper<PromptTemplate> qw = new QueryWrapper<>();
        qw.select("COALESCE(MAX(version), 0) AS version").eq("code", code);
        List<Object> objs = selectObjs(qw);
        if (objs.isEmpty() || objs.get(0) == null) {
            return 0;
        }
        return ((Number) objs.get(0)).intValue();
    }

    /** 某 code 的所有版本（新→旧）。 */
    default List<PromptTemplate> selectVersions(String code) {
        QueryWrapper<PromptTemplate> qw = new QueryWrapper<>();
        qw.eq("code", code).orderByDesc("version");
        return selectList(qw);
    }
}
