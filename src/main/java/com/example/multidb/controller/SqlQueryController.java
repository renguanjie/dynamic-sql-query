package com.example.multidb.controller;

import com.example.multidb.common.R;
import com.example.multidb.dto.SqlQueryRequest;
import com.example.multidb.dto.TableSchemaRequest;
import com.example.multidb.service.SqlQueryService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * SQL查询接口控制器
 */
@RestController
@RequestMapping("/api/sql")
public class SqlQueryController {

    private final SqlQueryService sqlQueryService;

    public SqlQueryController(SqlQueryService sqlQueryService) {
        this.sqlQueryService = sqlQueryService;
    }

    /**
     * 通用SQL查询接口
     * @param request 查询请求，包含数据库名称和SQL语句
     * @return 查询结果
     */
    @PostMapping("/query")
    public R<List<Map<String, Object>>> query(@RequestBody SqlQueryRequest request) {
        List<Map<String, Object>> result = sqlQueryService.executeQuery(request.getDbName(), request.getSql(), request.getSchema());
        return R.ok(result);
    }

    /**
     * 获取数据库表结构元数据（表名、表注释、字段名、字段注释、是否为空、是否主键）
     * @param request 请求体，包含数据源名称和可选的schema
     * @return 表结构元数据
     */
    @PostMapping("/schema")
    public R<List<Map<String, Object>>> tableSchema(@RequestBody TableSchemaRequest request) {
        List<Map<String, Object>> result = sqlQueryService.getTableSchema(request.getDbName(), request.getSchema());
        return R.ok(result);
    }
}
