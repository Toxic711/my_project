package com.zhouzihang.devflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 启动时是否灌入演示数据（demo 场景需要，生产应关闭）。 */
@Data
@ConfigurationProperties(prefix = "app.seed")
public class SeedProperties {

    private boolean enabled = true;
}
