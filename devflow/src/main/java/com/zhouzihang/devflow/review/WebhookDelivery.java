package com.zhouzihang.devflow.review;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * GitHub webhook 投递记录（表 webhook_delivery）。
 *
 * <p>面试讲解点：<b>幂等</b>不是"再查一次有没有"这么简单——并发重投会双写。
 * 正确做法是把 {@code delivery_id} 作为主键，先 INSERT 抢锁：
 * 插入成功=首次处理，抛 {@code DuplicateKeyException}=重复投递。
 * 这是用数据库唯一约束做分布式互斥的经典手法，比"先 select 再 insert"可靠得多。</p>
 */
@Data
@TableName("webhook_delivery")
public class WebhookDelivery {

    @TableId(type = IdType.INPUT)
    private String deliveryId;

    private String eventType;

    private String action;

    /** PROCESSING | PROCESSED | IGNORED | FAILED */
    private String status;

    private Long runId;

    private LocalDateTime receivedAt;
}
