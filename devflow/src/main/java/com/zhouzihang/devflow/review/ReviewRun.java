package com.zhouzihang.devflow.review;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次代码审查运行记录（表 review_run）。
 *
 * <p>面试讲解点：审查结果要<b>持久化</b>。GitHub webhook 是"一次投递 + 无人工在场"，
 * 出问题时唯一能回看的就是这次运行的输入摘要、统计与 findings。
 * {@code findings_json} 直接存原始 JSON（读多写少、结构随 LLM 演进），
 * 不做关系化拆表——这是"先跑通、再规范化"的现实取舍，README 里写明。</p>
 */
@Data
@TableName("review_run")
public class ReviewRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** GitHub delivery id（手动/sample 审查时为 null）。 */
    private String deliveryId;

    /** sample | manual | webhook */
    private String source;

    private String repo;

    private Integer prNumber;

    /** COMPLETED | PARTIAL | FAILED */
    private String status;

    private Integer filesChanged;

    private Integer addLines;

    private Integer findingsCount;

    private Integer highCount;

    private Integer mediumCount;

    private Integer lowCount;

    /** 0/1：是否因超长被截断。 */
    private Integer truncated;

    private Integer diffChars;

    private Integer segments;

    private Integer durationMs;

    /** findings 的原始 JSON 数组。 */
    private String findingsJson;

    private LocalDateTime createdAt;
}
