package com.zhouzihang.devflow.llm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 用量表 Mapper。
 *
 * <p>面试讲解点：这里只保留 MyBatis-Plus 的通用 CRUD，聚合统计放在
 * {@link TokenAccounting} 的内存里做。原因很务实——demo/测试跑在 H2 的
 * PostgreSQL 兼容模式，生产跑真 PG，两者对 {@code COALESCE/SUM} 的返回类型
 * （BigDecimal vs Double）有细微差别，写聚合 SQL 会引入方言耦合；
 * 而用量是"每次调用追加一行"的低基数数据，内存聚合足够。
 * 生产规模上来后，正确做法是按小时预聚合物化视图 + 增量刷写，见 DESIGN.md。</p>
 */
@Mapper
public interface LlmUsageMapper extends BaseMapper<LlmUsage> {

    /** 最近 N 条（按 id 倒序，id 自增即时间序）。 */
    default List<LlmUsage> recent(int limit) {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<LlmUsage> qw =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        qw.orderByDesc("id").last("LIMIT " + Math.max(1, Math.min(limit, 200)));
        return selectList(qw);
    }
}
