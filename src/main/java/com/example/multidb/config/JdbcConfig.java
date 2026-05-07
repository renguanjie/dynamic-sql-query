package com.example.multidb.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * JDBC配置类
 */
@Configuration
public class JdbcConfig {

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        // 单条SQL最大执行时间60秒，超时自动终止并释放连接
        jdbcTemplate.setQueryTimeout(60);
        // 限制最大返回行数为1000，防止大表查询导致OOM
        jdbcTemplate.setMaxRows(1000);
        return jdbcTemplate;
    }
}
