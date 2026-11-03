package com.zhouzihang.exam;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能在线考试系统启动类。
 *
 * <p>面试讲解点：为什么不用 spring-boot-maven-plugin / shade 打 fat jar？
 * 本地离线仓库缺 Boot repackage 的 loader-tools；shade 又会破坏
 * META-INF/spring.factories 的自动配置列表。最终采用
 * "BOM import + 普通 jar + run-java-app.ps1 解析 classpath 启动"，
 * 构建可复现、启动脚本显式可见，排查问题不依赖打包黑盒。</p>
 */
@SpringBootApplication
public class ExamApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExamApplication.class, args);
    }

    /** 最小存活探针，供启动脚本/演示脚本轮询使用。 */
    @Mapper
    public interface ProbeMapper {
        @Select("select 1")
        int one();
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
            return "db=" + probeMapper.one();
        }
    }
}
