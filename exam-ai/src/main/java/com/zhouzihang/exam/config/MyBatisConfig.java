package com.zhouzihang.exam.config;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * MyBatis 手动装配。
 *
 * <p>面试讲解点 1：为什么不用 mybatis-spring-boot-starter 的自动配置？
 * 本项目不使用 shade 打 fat jar（见 CONVENTIONS），但仍显式装配 SqlSessionFactory：
 * 不依赖自动配置顺序，行为可预期，也便于排查“方言/驼峰/空值”类问题。</p>
 *
 * <p>面试讲解点 2：{@code annotationClass = Mapper.class} 很关键。
 * MapperScan 默认会把包内**所有接口**注册成 MyBatis Mapper；本项目有
 * {@code LlmClient} 这类业务端口接口，如果不加过滤会被误注册成 Mapper，
 * 与真正的实现类产生 Bean 冲突。只扫描带 {@code @Mapper} 的接口可避免。</p>
 */
@Configuration
@MapperScan(basePackages = "com.zhouzihang.exam", annotationClass = Mapper.class,
        sqlSessionFactoryRef = "sqlSessionFactory")
public class MyBatisConfig {

    @Bean("sqlSessionFactory")
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);

        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        // 下划线转驼峰：password_hash -> passwordHash
        configuration.setMapUnderscoreToCamelCase(true);
        // 允许返回 Map 中的 null 字段（避免前端拿到缺字段的 Map 产生歧义）
        configuration.setCallSettersOnNulls(true);
        factoryBean.setConfiguration(configuration);

        return factoryBean.getObject();
    }

    @Bean("sqlSessionTemplate")
    public SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }
}
