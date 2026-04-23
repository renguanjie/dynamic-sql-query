package com.example.multidb.service;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL查询服务
 */
@Service
public class SqlQueryService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SqlQueryService.class);

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final Map<String, String> dbTypeCache = new HashMap<>();

    public SqlQueryService(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
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

    /**
     * 获取数据库表结构元数据
     * @param dbName 数据源名称
     * @param schema Schema名称（Oracle传用户名，openGauss传public，MySQL/OceanBase可传null）
     * @return 表结构元数据
     */
    public List<Map<String, Object>> getTableSchema(String dbName, String schema) {
        if (dbName == null || dbName.trim().isEmpty()) {
            throw new IllegalArgumentException("数据库名称不能为空");
        }
        log.info("获取表结构元数据，数据源：{}，schema：{}", dbName, schema);
        try {
            DynamicDataSourceContextHolder.push(dbName);

            // 获取数据库类型
            String dbType = getDbType(dbName);

            // 根据数据库类型执行不同的元数据查询
            if ("mysql".equals(dbType) || "oceanbase".equals(dbType)) {
                return queryMetadataForMySQL(schema);
            } else if ("oracle".equals(dbType)) {
                return queryMetadataForOracle(schema);
            } else if ("postgresql".equals(dbType) || "opengauss".equals(dbType)) {
                return queryMetadataForPostgreSQL(schema);
            } else {
                throw new IllegalArgumentException("不支持的数据库类型：" + dbType);
            }
        } finally {
            DynamicDataSourceContextHolder.clear();
            log.info("清除数据源上下文：{}", dbName);
        }
    }

    /**
     * 检测数据库类型
     */
    private String getDbType(String dataSourceName) {
        if (dbTypeCache.containsKey(dataSourceName)) {
            return dbTypeCache.get(dataSourceName);
        }

        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            String productName = metaData.getDatabaseProductName();
            String url = metaData.getURL();

            String dbType;
            if (productName != null && productName.toLowerCase().contains("oracle")) {
                dbType = "oracle";
            } else if (productName != null && productName.toLowerCase().contains("postgresql")) {
                dbType = "opengauss";
            } else if (url != null && url.toLowerCase().contains("oceanbase")) {
                dbType = "oceanbase";
            } else {
                dbType = "mysql";
            }

            dbTypeCache.put(dataSourceName, dbType);
            log.info("数据源 {} 数据库类型：{} (JDBC productName: {})", dataSourceName, dbType, productName);
            return dbType;
        } catch (SQLException e) {
            throw new RuntimeException("无法检测数据库类型", e);
        }
    }

    /**
     * MySQL / OceanBase (MySQL兼容模式) 元数据查询
     */
    private List<Map<String, Object>> queryMetadataForMySQL(String schema) {
        String trimmedSchema = (schema != null) ? schema.trim() : "";
        boolean useCurrentDb = trimmedSchema.isEmpty();
        String schemaName = useCurrentDb ? null : trimmedSchema;

        // 获取所有表及注释
        String tableSql;
        if (useCurrentDb) {
            tableSql = "SELECT TABLE_NAME, TABLE_COMMENT " +
                    "FROM information_schema.tables " +
                    "WHERE table_schema = DATABASE() AND TABLE_TYPE = 'BASE TABLE' " +
                    "ORDER BY TABLE_NAME";
        } else {
            tableSql = "SELECT TABLE_NAME, TABLE_COMMENT " +
                    "FROM information_schema.tables " +
                    "WHERE table_schema = '" + schemaName + "' AND TABLE_TYPE = 'BASE TABLE' " +
                    "ORDER BY TABLE_NAME";
        }

        List<Map<String, Object>> tables = jdbcTemplate.queryForList(tableSql);

        // 获取所有主键信息
        String pkSql;
        if (useCurrentDb) {
            pkSql = "SELECT TABLE_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY ORDINAL_POSITION) AS PK_COLUMNS " +
                    "FROM information_schema.KEY_COLUMN_USAGE " +
                    "WHERE table_schema = DATABASE() AND CONSTRAINT_NAME = 'PRIMARY' " +
                    "GROUP BY TABLE_NAME";
        } else {
            pkSql = "SELECT TABLE_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY ORDINAL_POSITION) AS PK_COLUMNS " +
                    "FROM information_schema.KEY_COLUMN_USAGE " +
                    "WHERE table_schema = '" + schemaName + "' AND CONSTRAINT_NAME = 'PRIMARY' " +
                    "GROUP BY TABLE_NAME";
        }
        List<Map<String, Object>> pkList = jdbcTemplate.queryForList(pkSql);
        Map<String, String> pkMap = buildPrimaryKeyMap(pkList);

        // 获取所有列信息
        String columnSql;
        if (useCurrentDb) {
            columnSql = "SELECT TABLE_NAME, COLUMN_NAME, COLUMN_COMMENT, COLUMN_TYPE, " +
                    "CASE WHEN IS_NULLABLE = 'YES' THEN 1 ELSE 0 END AS IS_NULLABLE " +
                    "FROM information_schema.columns " +
                    "WHERE table_schema = DATABASE() " +
                    "ORDER BY TABLE_NAME, ORDINAL_POSITION";
        } else {
            columnSql = "SELECT TABLE_NAME, COLUMN_NAME, COLUMN_COMMENT, COLUMN_TYPE, " +
                    "CASE WHEN IS_NULLABLE = 'YES' THEN 1 ELSE 0 END AS IS_NULLABLE " +
                    "FROM information_schema.columns " +
                    "WHERE table_schema = '" + schemaName + "' " +
                    "ORDER BY TABLE_NAME, ORDINAL_POSITION";
        }
        List<Map<String, Object>> allColumns = jdbcTemplate.queryForList(columnSql);
        Map<String, List<Map<String, Object>>> columnsByTable = groupColumnsByTable(allColumns, pkMap);

        // 组装结果
        return buildTableSchemaResult(tables, columnsByTable, pkMap);
    }

    /**
     * Oracle 元数据查询
     */
    private List<Map<String, Object>> queryMetadataForOracle(String schema) {
        // 不传 schema 时查询当前用户（用 USER_* 视图，无需额外权限）
        String trimmedSchema = (schema != null) ? schema.trim() : "";
        boolean useCurrentUser = trimmedSchema.isEmpty();
        String upperSchema = trimmedSchema.toUpperCase();

        // 获取所有表
        String tableSql = useCurrentUser
                ? "SELECT t.TABLE_NAME, " +
                "(SELECT c.COMMENTS FROM USER_TAB_COMMENTS c WHERE c.TABLE_NAME = t.TABLE_NAME) AS TABLE_COMMENT " +
                "FROM USER_TABLES t ORDER BY t.TABLE_NAME"
                : "SELECT t.TABLE_NAME, " +
                "(SELECT c.COMMENTS FROM ALL_TAB_COMMENTS c " +
                "WHERE c.TABLE_NAME = t.TABLE_NAME AND c.OWNER = t.OWNER) AS TABLE_COMMENT " +
                "FROM ALL_TABLES t WHERE t.OWNER = '" + upperSchema + "' ORDER BY t.TABLE_NAME";
        List<Map<String, Object>> tables = jdbcTemplate.queryForList(tableSql);

        // 获取所有主键信息
        String pkSql = useCurrentUser
                ? "SELECT acc.TABLE_NAME, " +
                "LISTAGG(accol.COLUMN_NAME, ',') WITHIN GROUP (ORDER BY accol.POSITION) AS PK_COLUMNS " +
                "FROM USER_CONSTRAINTS acc " +
                "JOIN USER_CONS_COLUMNS accol ON acc.CONSTRAINT_NAME = accol.CONSTRAINT_NAME " +
                "WHERE acc.CONSTRAINT_TYPE = 'P' " +
                "GROUP BY acc.TABLE_NAME"
                : "SELECT acc.TABLE_NAME, " +
                "LISTAGG(accol.COLUMN_NAME, ',') WITHIN GROUP (ORDER BY accol.POSITION) AS PK_COLUMNS " +
                "FROM ALL_CONSTRAINTS acc " +
                // 【修复】：改为 ALL_CONS_COLUMNS
                "JOIN ALL_CONS_COLUMNS accol ON acc.CONSTRAINT_NAME = accol.CONSTRAINT_NAME AND acc.OWNER = accol.OWNER " +
                "WHERE acc.OWNER = '" + upperSchema + "' AND acc.CONSTRAINT_TYPE = 'P' " +
                "GROUP BY acc.TABLE_NAME";
        List<Map<String, Object>> pkList = jdbcTemplate.queryForList(pkSql);
        Map<String, String> pkMap = buildPrimaryKeyMap(pkList);

        // 获取所有列信息
        String columnSql = useCurrentUser
                ? "SELECT tc.TABLE_NAME, tc.COLUMN_NAME, " +
                "(SELECT cc.COMMENTS FROM USER_COL_COMMENTS cc WHERE cc.TABLE_NAME = tc.TABLE_NAME AND cc.COLUMN_NAME = tc.COLUMN_NAME) AS COLUMN_COMMENT, " +
                "tc.DATA_TYPE, " +
                "CASE WHEN tc.NULLABLE = 'Y' THEN 1 ELSE 0 END AS IS_NULLABLE " +
                "FROM USER_TAB_COLUMNS tc ORDER BY tc.TABLE_NAME, tc.COLUMN_ID"
                : "SELECT tc.TABLE_NAME, tc.COLUMN_NAME, " +
                "(SELECT cc.COMMENTS FROM ALL_COL_COMMENTS cc " +
                "WHERE cc.TABLE_NAME = tc.TABLE_NAME AND cc.COLUMN_NAME = tc.COLUMN_NAME AND cc.OWNER = tc.OWNER) AS COLUMN_COMMENT, " +
                "tc.DATA_TYPE, " +
                "CASE WHEN tc.NULLABLE = 'Y' THEN 1 ELSE 0 END AS IS_NULLABLE " +
                "FROM ALL_TAB_COLUMNS tc " +
                "WHERE tc.OWNER = '" + upperSchema + "' " +
                "ORDER BY tc.TABLE_NAME, tc.COLUMN_ID";
        List<Map<String, Object>> allColumns = jdbcTemplate.queryForList(columnSql);
        Map<String, List<Map<String, Object>>> columnsByTable = groupColumnsByTable(allColumns, pkMap);

        // 组装结果
        return buildTableSchemaResult(tables, columnsByTable, pkMap);
    }

    /**
     * openGauss (PostgreSQL兼容) 元数据查询
     */
    private List<Map<String, Object>> queryMetadataForPostgreSQL(String schema) {
        String targetSchema = (schema != null && !schema.trim().isEmpty()) ? schema.trim() : "public";

        // 获取所有表及注释
        String tableSql = "SELECT c.relname AS TABLE_NAME, " +
                "COALESCE(obj_description(c.oid, 'pg_class'), '') AS TABLE_COMMENT " +
                "FROM pg_class c " +
                "JOIN pg_namespace n ON c.relnamespace = n.oid " +
                "WHERE n.nspname = '" + targetSchema + "' AND c.relkind = 'r' " +
                "ORDER BY c.relname";

        List<Map<String, Object>> tables = jdbcTemplate.queryForList(tableSql);

        // 获取所有主键信息（避免使用 array_position，该函数在部分 openGauss 版本存在 bug）
        // 改用 generate_subscripts 替代 array_position
        String pkSql = "SELECT t.relname AS TABLE_NAME, " +
                "string_agg(a.attname, ',' ORDER BY s.n) AS PK_COLUMNS " +
                "FROM pg_constraint pk " +
                "JOIN pg_class t ON pk.conrelid = t.oid " +
                "JOIN pg_namespace n ON t.relnamespace = n.oid " +
                "JOIN pg_attribute a ON a.attrelid = t.oid " +
                ", generate_subscripts(pk.conkey, 1) s(n) " +
                "WHERE n.nspname = '" + targetSchema + "' AND pk.contype = 'p' " +
                "AND a.attnum = pk.conkey[s.n] " +
                "GROUP BY t.relname";
        List<Map<String, Object>> pkList = jdbcTemplate.queryForList(pkSql);
        Map<String, String> pkMap = buildPrimaryKeyMap(pkList);

        // 获取所有列信息
        String columnSql = "SELECT c.relname AS TABLE_NAME, a.attname AS COLUMN_NAME, " +
                "COALESCE(col_description(c.oid, a.attnum), '') AS COLUMN_COMMENT, " +
                "format_type(a.atttypid, a.atttypmod) AS DATA_TYPE, " +
                "CASE WHEN a.attnotnull THEN 0 ELSE 1 END AS IS_NULLABLE " +
                "FROM pg_attribute a " +
                "JOIN pg_class c ON a.attrelid = c.oid " +
                "JOIN pg_namespace n ON c.relnamespace = n.oid " +
                "WHERE n.nspname = '" + targetSchema + "' AND c.relkind = 'r' " +
                "AND a.attnum > 0 AND NOT a.attisdropped " +
                "ORDER BY c.relname, a.attnum";
        List<Map<String, Object>> allColumns = jdbcTemplate.queryForList(columnSql);
        Map<String, List<Map<String, Object>>> columnsByTable = groupColumnsByTable(allColumns, pkMap);

        // 组装结果
        return buildTableSchemaResult(tables, columnsByTable, pkMap);
    }

    /**
     * 构建主键映射表: tableName -> comma-separated primary key columns
     */
    private Map<String, String> buildPrimaryKeyMap(List<Map<String, Object>> pkList) {
        Map<String, String> map = new HashMap<>();
        for (Map<String, Object> row : pkList) {
            String tableName = getStringValue(row, "TABLE_NAME");
            String pkCols = getStringValue(row, "PK_COLUMNS");
            if (tableName != null && pkCols != null) {
                map.put(tableName, pkCols);
            }
        }
        return map;
    }

    /**
     * 将列按表名分组
     */
    private Map<String, List<Map<String, Object>>> groupColumnsByTable(
            List<Map<String, Object>> allColumns, Map<String, String> pkMap) {
        Map<String, List<Map<String, Object>>> map = new LinkedHashMap<>();
        for (Map<String, Object> col : allColumns) {
            String tableName = getStringValue(col, "TABLE_NAME");
            if (tableName == null) continue;
            map.computeIfAbsent(tableName, k -> new ArrayList<>()).add(col);
        }
        return map;
    }

    /**
     * 组装最终的表结构结果
     */
    private List<Map<String, Object>> buildTableSchemaResult(
            List<Map<String, Object>> tables,
            Map<String, List<Map<String, Object>>> columnsByTable,
            Map<String, String> pkMap) {

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> table : tables) {
            String tableName = getStringValue(table, "TABLE_NAME");
            if (tableName == null) continue;

            Map<String, Object> tableObj = new LinkedHashMap<>();
            tableObj.put("name", tableName);
            tableObj.put("chinese_name", getStringValue(table, "TABLE_COMMENT"));
            tableObj.put("primary_key", pkMap.getOrDefault(tableName, ""));

            List<Map<String, Object>> columnList = columnsByTable.getOrDefault(tableName, new ArrayList<>());
            List<Map<String, Object>> columnResult = new ArrayList<>();
            for (Map<String, Object> col : columnList) {
                Map<String, Object> colObj = new LinkedHashMap<>();
                colObj.put("name", getStringValue(col, "COLUMN_NAME"));
                colObj.put("chinese_name", getStringValue(col, "COLUMN_COMMENT"));
                colObj.put("data_type", getStringValue(col, "DATA_TYPE"));

                String tableNameKey = getStringValue(col, "TABLE_NAME");
                String pkCols = pkMap.getOrDefault(tableNameKey, "");
                String colName = getStringValue(col, "COLUMN_NAME");
                colObj.put("is_primary_key", pkCols.contains(colName));

                Object nullable = col.get("IS_NULLABLE");
                colObj.put("nullable", nullable != null && (Integer.parseInt(nullable.toString()) == 1));

                columnResult.add(colObj);
            }
            tableObj.put("columns", columnResult);
            result.add(tableObj);
        }
        return result;
    }

    /**
     * 安全获取字符串值
     */
    private String getStringValue(Map<String, Object> row, String key) {
        Object val = row.get(key);
        if (val == null) return "";
        return val.toString();
    }
}
