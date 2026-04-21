package com.example.multidb.service;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * SQL查询服务
 */
@Service
public class SqlQueryService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SqlQueryService.class);

    private final JdbcTemplate jdbcTemplate;

    public SqlQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 执行SQL查询
     * @param dbName 数据库名称，对应配置文件中的数据源名称
     * @param sql SQL查询语句
     * @return 查询结果，List<Map<String, Object>>，其中Map的key是列名，value是列值
     */
    public List<Map<String, Object>> executeQuery(String dbName, String sql) {
        if (dbName == null || dbName.trim().isEmpty()) {
            throw new IllegalArgumentException("数据库名称不能为空");
        }
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("SQL语句不能为空");
        }
        log.info("执行SQL查询，数据源：{}，SQL：{}", dbName, sql);
        try {
            // 手动切换数据源
            DynamicDataSourceContextHolder.push(dbName);
            // 执行查询，返回通用结果
            return jdbcTemplate.queryForList(sql);
        } finally {
            // 必须清除数据源上下文，防止污染后续请求
            DynamicDataSourceContextHolder.clear();
            log.info("清除数据源上下文：{}", dbName);
        }
    }
}
