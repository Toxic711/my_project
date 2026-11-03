package com.zhouzihang.devflow.review;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** webhook 投递记录 Mapper（主键即幂等键）。 */
@Mapper
public interface WebhookDeliveryMapper extends BaseMapper<WebhookDelivery> {
}
