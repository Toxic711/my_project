package com.zhouzihang.devflow;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DevFlow 启动类。
 * 说明：保留最小 /ping 探针用于验证离线 fat jar 可启动性；MyBatis-Plus 探针用于验证持久层装配。
 */
@SpringBootApplication
// 面试讲解点：只扫描带 @Mapper 的接口。
// 若写成 @MapperScan("com.zhouzihang.devflow") 而不限定 annotationClass，
// MyBatis 会把包内**所有接口**（如 LlmClient）也注册成 Mapper 代理，
// 导致调用 LlmClient#providerName() 时报 "Invalid bound statement"。
@MapperScan(basePackages = "com.zhouzihang.devflow", annotationClass = Mapper.class)
public class DevFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(DevFlowApplication.class, args);
    }

    @TableName("probe")
    public static class Probe {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String name;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @Mapper
    public interface ProbeMapper extends BaseMapper<Probe> {
    }

    @RestController
    static class ProbeController {
        private final ProbeMapper probeMapper;

        ProbeController(ProbeMapper probeMapper) {
            this.probeMapper = probeMapper;
        }

        @GetMapping("/ping")
        public String ping() {
            return "pong";
        }

        @GetMapping("/db")
        public String db() {
            return "rows=" + probeMapper.selectCount(null);
        }
    }
}
