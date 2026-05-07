package com.example.multidb.service;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.example.multidb.config.TargetTable;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * SQL查询服务
 */
@Service
public class SqlQueryService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SqlQueryService.class);

    private static final String OPENGAUSS_DS = "opengauss";
    private static final int MAX_SYNC_ROWS = 5000;

    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;
    private final TargetTable targetTable;
    private final JdbcTemplate sqliteJdbcTemplate;
    private final Map<String, String> dbTypeCache = new HashMap<>();

    public SqlQueryService(JdbcTemplate jdbcTemplate, Environment environment, TargetTable targetTable) {
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
        this.targetTable = targetTable;
        this.sqliteJdbcTemplate = initSqliteMemoryDataSource();
    }

    /**
     * 执行SQL查询
     */
    public List<Map<String, Object>> executeQuery(String dbName, String sql) {
        if (dbName == null || dbName.trim().isEmpty()) {
            throw new IllegalArgumentException("数据库名称不能为空");
        }
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("SQL语句不能为空");
        }

        // SQL安全校验 + 提取表名（使用 JSqlParser）
        SqlParseResult parseResult = parseSqlWithJSqlParser(sql);

        // SQLite 特殊处理：从 openGauss 同步表数据到内存，再执行查询
        if ("sqlite".equals(getDbType(dbName))) {
            return executeQueryOnSqlite(sql, parseResult.getTableNames());
        }

        log.info("执行SQL查询，数据源：{}，SQL：{}", dbName, sql);
        try {
            DynamicDataSourceContextHolder.push(dbName);
            return jdbcTemplate.queryForList(sql);
        } finally {
            DynamicDataSourceContextHolder.clear();
            log.info("清除数据源上下文：{}", dbName);
        }
    }

    /**
     * SQLite 查询流程：传入的 SQL 已是合法的 SQLite 语句（表名为简化后的 SQLite 表名）。
     * 通过 TargetTable 反向查找 openGauss 表名 → 同步数据 → 直接用原始 SQL 执行。
     */
    private synchronized List<Map<String, Object>> executeQueryOnSqlite(String sql, Set<String> tableNames) {
        log.info("SQLite 查询流程，原始SQL：{}", sql);

        if (tableNames.isEmpty()) {
            throw new IllegalArgumentException("未能从SQL中提取到表名");
        }
        log.info("从SQL中提取到 SQLite 表名：{}", tableNames);

        // 反查 openGauss 表名，构建同步列表
        List<String> unconfiguredTables = new ArrayList<>();
        for (String sqliteTable : tableNames) {
            String ogTable = targetTable.findOpenGaussTableName(sqliteTable);
            if (ogTable == null) {
                unconfiguredTables.add(sqliteTable);
            } else {
                syncTableFromOpenGauss(ogTable, sqliteTable);
            }
        }

        if (!unconfiguredTables.isEmpty()) {
            throw new IllegalArgumentException("SQL 中引用了未配置的 SQLite 表：" + unconfiguredTables);
        }

        // SQL 已经是合法的 SQLite 语句，直接执行
        try {
            log.info("执行 SQLite 查询：{}", sql);
            return sqliteJdbcTemplate.queryForList(sql);
        } catch (Exception e) {
            throw new RuntimeException("SQLite 查询执行失败：" + e.getMessage(), e);
        }
    }

    /**
     * SQL安全校验 + 表名提取（使用 JSqlParser 官方 TablesNamesFinder）
     */
    private SqlParseResult parseSqlWithJSqlParser(String sql) {
        SqlParseResult result = new SqlParseResult();
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);

            // 安全检查：只允许 SELECT 和 WITH (CTE) 语句
            if (!(stmt instanceof Select)) {
                throw new IllegalArgumentException("仅支持 SELECT/WITH 开头的只读查询语句");
            }

            // 使用 JSqlParser 官方 TablesNamesFinder 提取所有表名（包括子查询中的表）
            TablesNamesFinder tablesNamesFinder = new TablesNamesFinder();
            List<String> rawTableNames = tablesNamesFinder.getTableList(stmt);

            Set<String> tableNames = new LinkedHashSet<>();
            for (String name : rawTableNames) {
                // 清理引号（反引号、双引号）
                String cleanName = name.replace("`", "").replace("\"", "");
                if (!isSystemTable(cleanName)) {
                    tableNames.add(cleanName);
                }
            }
            result.setTableNames(tableNames);

        } catch (JSQLParserException e) {
            throw new IllegalArgumentException("SQL 解析失败：" + e.getMessage(), e);
        }
        return result;
    }

    private boolean isSystemTable(String name) {
        return name.startsWith("sqlite_") || name.startsWith("pg_") ||
               name.startsWith("information_schema") || name.startsWith("sys.");
    }

    /**
     * SQL 解析结果
     */
    private static class SqlParseResult {
        private Set<String> tableNames = new LinkedHashSet<>();

        public Set<String> getTableNames() { return tableNames; }
        public void setTableNames(Set<String> tableNames) { this.tableNames = tableNames; }
    }

    /**
     * 获取数据库表结构元数据
     */
    public List<Map<String, Object>> getTableSchema(String dbName, String schema) {
        if (dbName == null || dbName.trim().isEmpty()) {
            throw new IllegalArgumentException("数据库名称不能为空");
        }
        log.info("获取表结构元数据，数据源：{}，schema：{}", dbName, schema);
        try {
            DynamicDataSourceContextHolder.push(dbName);

            String dbType = getDbType(dbName);

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
     * 检测数据库类型（通过 JDBC URL 前缀判断）
     */
    private String getDbType(String dataSourceName) {
        if (dbTypeCache.containsKey(dataSourceName)) {
            return dbTypeCache.get(dataSourceName);
        }

        try {
            String jdbcUrl = getJdbcUrlFromDataSource(dataSourceName);
            String urlLower = jdbcUrl.toLowerCase();

            String dbType;
            if (urlLower.startsWith("jdbc:sqlite:")) {
                dbType = "sqlite";
            } else if (urlLower.startsWith("jdbc:oracle:")) {
                dbType = "oracle";
            } else if (urlLower.startsWith("jdbc:opengauss:") || urlLower.startsWith("jdbc:postgresql:")) {
                dbType = "opengauss";
            } else if (urlLower.startsWith("jdbc:oceanbase:")) {
                dbType = "oceanbase";
            } else {
                dbType = "mysql";
            }

            dbTypeCache.put(dataSourceName, dbType);
            log.info("数据源 {} 数据库类型：{} (JDBC URL: {})", dataSourceName, dbType, jdbcUrl);
            return dbType;
        } catch (Exception e) {
            throw new RuntimeException("无法检测数据库类型", e);
        }
    }

    private String getJdbcUrlFromDataSource(String dataSourceName) {
        String urlKey = "spring.datasource.dynamic.datasource." + dataSourceName + ".url";
        String jdbcUrl = environment.getProperty(urlKey);
        if (jdbcUrl == null || jdbcUrl.isEmpty()) {
            throw new RuntimeException("未找到数据源配置：" + dataSourceName);
        }
        return jdbcUrl;
    }

    // ==================== SQLite 内存数据库管理 ====================

    /**
     * 初始化 SQLite 内存数据源，使用 shared cache 模式，
     * 并通过 org.sqlite.Function.create 原生 API 注册自定义函数。
     */
    private JdbcTemplate initSqliteMemoryDataSource() {
        String sqliteUrl = getJdbcUrlFromDataSource("sqlite");
        if (!sqliteUrl.contains(":memory:") && !sqliteUrl.contains("cache=shared")) {
            sqliteUrl = ensureSharedCache(sqliteUrl);
        }

        try {
            Class.forName("org.sqlite.JDBC");
            Connection conn = DriverManager.getConnection(sqliteUrl);

            // 使用 org.sqlite.Function.create 原生 API 注册自定义函数
            org.sqlite.Function.create(conn, "MonthsBetween", new org.sqlite.Function() {
                @Override
                protected void xFunc() throws SQLException {
                    try {
                        LocalDate d1 = parseDate(value_text(0));
                        LocalDate d2 = parseDate(value_text(1));
                        long diff = ChronoUnit.MONTHS.between(d1, d2);
                        result(diff);
                    } catch (Exception e) {
                        result(0);
                    }
                }
            });

            org.sqlite.Function.create(conn, "MonthsBetweenWithDB", new org.sqlite.Function() {
                @Override
                protected void xFunc() throws SQLException {
                    try {
                        LocalDate d1 = parseDate(value_text(0));
                        LocalDate d2 = parseDate(value_text(1));
                        double diff = monthsBetweenOracle(d1, d2);
                        result(diff);
                    } catch (Exception e) {
                        result(0.0);
                    }
                }
            });

            JdbcTemplate sqliteJt = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
            sqliteJt.setQueryTimeout(60);
            sqliteJt.setMaxRows(1000);
            log.info("SQLite 内存数据库初始化完成，URL：{}，已注册函数：MonthsBetween, MonthsBetweenWithDB", sqliteUrl);
            return sqliteJt;
        } catch (Exception e) {
            throw new RuntimeException("SQLite 内存数据库初始化失败：" + e.getMessage(), e);
        }
    }

    private String ensureSharedCache(String url) {
        if (url.contains("cache=shared")) return url;
        if (url.contains(":memory:")) {
            return url.replace(":memory:", "file::memory:?cache=shared");
        }
        if (url.contains("?")) {
            return url + "&cache=shared";
        }
        return url + "?cache=shared";
    }

    // ==================== SQLite 自定义函数 ====================

    private static LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            throw new IllegalArgumentException("日期参数不能为空");
        }
        dateStr = dateStr.trim();

        DateTimeFormatter[] formatters = {
                DateTimeFormatter.ofPattern("yyyy-MM-dd"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd"),
                DateTimeFormatter.ofPattern("yyyyMMdd"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM"),
                DateTimeFormatter.ofPattern("yyyyMM"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"),
        };

        for (DateTimeFormatter formatter : formatters) {
            try {
                return LocalDate.parse(dateStr, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }

        throw new IllegalArgumentException("无法解析日期格式：" + dateStr);
    }

    /**
     * MonthsBetweenWithDB：模拟 Oracle MONTHS_BETWEEN 行为，返回带小数的精确值
     */
    private static double monthsBetweenOracle(LocalDate date1, LocalDate date2) {
        long months = ChronoUnit.MONTHS.between(date1.withDayOfMonth(1), date2.withDayOfMonth(1));

        boolean d1IsLastDay = (date1.getDayOfMonth() == date1.lengthOfMonth());
        boolean d2IsLastDay = (date2.getDayOfMonth() == date2.lengthOfMonth());

        if (d1IsLastDay && d2IsLastDay) {
            return (double) months;
        }
        if (date1.equals(date2)) {
            return (double) months;
        }

        int day1 = date1.getDayOfMonth();
        int day2 = date2.getDayOfMonth();
        double fraction = (day1 == day2) ? 0 : (double) (day2 - day1) / 31.0;

        return months + fraction;
    }

    // ==================== openGauss -> SQLite 同步 ====================

    /**
     * 从 openGauss 同步单张表的结构和数据到 SQLite 内存数据库。
     * 每次同步前先 DROP 旧表，保证数据是源库的最新快照。
     */
    private void syncTableFromOpenGauss(String ogTableName, String sqliteTableName) {

        // 先删除 SQLite 中可能存在的旧表，强制刷新数据
        sqliteJdbcTemplate.execute("DROP TABLE IF EXISTS " + sqliteTableName);
        log.info("开始从 openGauss 同步表 {} 到 SQLite", ogTableName);

        List<Map<String, Object>> columns = getOpenGaussTableColumns(ogTableName);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("openGauss 中不存在表：" + ogTableName);
        }

        createTableInSqlite(sqliteTableName, columns);
        importDataFromOpenGauss(ogTableName, sqliteTableName, columns);

        log.info("表 {} 同步完成 -> {}", ogTableName, sqliteTableName);
    }

    private List<Map<String, Object>> getOpenGaussTableColumns(String tableName) {
        try {
            DynamicDataSourceContextHolder.push(OPENGAUSS_DS);
            String targetDbTableName = tableName.toLowerCase();
            String sql = "SELECT a.attname AS column_name, " +
                    "format_type(a.atttypid, a.atttypmod) AS data_type, " +
                    "a.attnotnull AS not_null, " +
                    "a.attnum AS ordinal " +
                    "FROM pg_attribute a " +
                    "JOIN pg_class c ON a.attrelid = c.oid " +
                    "JOIN pg_namespace n ON c.relnamespace = n.oid " +
                    "WHERE n.nspname = 'public' AND c.relname = '" + targetDbTableName + "' " +
                    "AND a.attnum > 0 AND NOT a.attisdropped " +
                    "ORDER BY a.attnum";

            return jdbcTemplate.queryForList(sql);
        } finally {
            DynamicDataSourceContextHolder.clear();
        }
    }

    private void createTableInSqlite(String sqliteTableName, List<Map<String, Object>> columns) {
        StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
        ddl.append(sqliteTableName).append(" (");

        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) ddl.append(", ");
            String colName = getStringValue(columns.get(i), "column_name");
            String dataType = getStringValue(columns.get(i), "data_type");
            boolean notNull = "true".equals(String.valueOf(columns.get(i).get("not_null")));

            ddl.append(colName).append(" ").append(mapDataTypeToSqlite(dataType));
            if (notNull) {
                ddl.append(" NOT NULL");
            }
        }

        ddl.append(")");

        log.info("SQLite 建表 DDL：{}", ddl);
        sqliteJdbcTemplate.execute(ddl.toString());
    }

    private String mapDataTypeToSqlite(String ogType) {
        if (ogType == null) return "TEXT";
        String t = ogType.toLowerCase();

        if (t.contains("int") || t.contains("serial") || t.contains("oid")) return "INTEGER";
        if (t.contains("numeric") || t.contains("decimal") || t.contains("money")) return "NUMERIC";
        if (t.contains("float") || t.contains("double") || t.contains("real")) return "REAL";
        if (t.contains("bool")) return "INTEGER";
        if (t.contains("timestamp") || t.contains("date") || t.contains("time")) return "TEXT";
        if (t.contains("bytea") || t.contains("blob") || t.contains("binary")) return "BLOB";
        if (t.contains("json")) return "TEXT";
        if (t.contains("uuid") || t.contains("inet") || t.contains("cidr") || t.contains("macaddr")) return "TEXT";
        return "TEXT";
    }

    /**
     * 从 openGauss 读取数据并批量插入 SQLite，强制限制最多同步 MAX_SYNC_ROWS 行，防止 OOM。
     */
    private void importDataFromOpenGauss(String ogTableName, String sqliteTableName, List<Map<String, Object>> columns) {
        try {
            DynamicDataSourceContextHolder.push(OPENGAUSS_DS);

            String sql = "SELECT * FROM " + ogTableName + " LIMIT " + MAX_SYNC_ROWS;
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

            log.info("从 openGauss 表 {} 读取 {} 行数据（上限 {}）", ogTableName, rows.size(), MAX_SYNC_ROWS);

            if (rows.isEmpty()) return;

            String[] colNames = new String[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                colNames[i] = getStringValue(columns.get(i), "column_name");
            }

            String placeholders = String.join(",", Collections.nCopies(colNames.length, "?"));
            String insertSql = "INSERT INTO " + sqliteTableName + " (" + String.join(",", colNames) + ") VALUES (" + placeholders + ")";

            sqliteJdbcTemplate.batchUpdate(insertSql, rows, rows.size(), (ps, row) -> {
                for (int i = 0; i < colNames.length; i++) {
                    Object val = row.get(colNames[i]);
                    if (val != null && "org.postgresql.util.PGobject".equals(val.getClass().getName())) {
                        val = val.toString();
                    }
                    ps.setObject(i + 1, val);
                }
            });

            log.info("向 SQLite 表 {} 插入 {} 行数据", sqliteTableName, rows.size());
        } finally {
            DynamicDataSourceContextHolder.clear();
        }
    }

    // ==================== MySQL / OceanBase 元数据 ====================

    private List<Map<String, Object>> queryMetadataForMySQL(String schema) {
        String trimmedSchema = (schema != null) ? schema.trim() : "";
        boolean useCurrentDb = trimmedSchema.isEmpty();
        String schemaName = useCurrentDb ? null : trimmedSchema;

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

        return buildTableSchemaResult(tables, columnsByTable, pkMap);
    }

    // ==================== Oracle 元数据 ====================

    private List<Map<String, Object>> queryMetadataForOracle(String schema) {
        String trimmedSchema = (schema != null) ? schema.trim() : "";
        boolean useCurrentUser = trimmedSchema.isEmpty();
        String upperSchema = trimmedSchema.toUpperCase();

        String tableSql = useCurrentUser
                ? "SELECT t.TABLE_NAME, " +
                "(SELECT c.COMMENTS FROM USER_TAB_COMMENTS c WHERE c.TABLE_NAME = t.TABLE_NAME) AS TABLE_COMMENT " +
                "FROM USER_TABLES t ORDER BY t.TABLE_NAME"
                : "SELECT t.TABLE_NAME, " +
                "(SELECT c.COMMENTS FROM ALL_TAB_COMMENTS c " +
                "WHERE c.TABLE_NAME = t.TABLE_NAME AND c.OWNER = t.OWNER) AS TABLE_COMMENT " +
                "FROM ALL_TABLES t WHERE t.OWNER = '" + upperSchema + "' ORDER BY t.TABLE_NAME";
        List<Map<String, Object>> tables = jdbcTemplate.queryForList(tableSql);

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
                "JOIN ALL_CONS_COLUMNS accol ON acc.CONSTRAINT_NAME = accol.CONSTRAINT_NAME AND acc.OWNER = accol.OWNER " +
                "WHERE acc.OWNER = '" + upperSchema + "' AND acc.CONSTRAINT_TYPE = 'P' " +
                "GROUP BY acc.TABLE_NAME";
        List<Map<String, Object>> pkList = jdbcTemplate.queryForList(pkSql);
        Map<String, String> pkMap = buildPrimaryKeyMap(pkList);

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

        return buildTableSchemaResult(tables, columnsByTable, pkMap);
    }

    // ==================== openGauss 元数据 ====================

    private List<Map<String, Object>> queryMetadataForPostgreSQL(String schema) {
        String targetSchema = (schema != null && !schema.trim().isEmpty()) ? schema.trim() : "public";

        String tableSql = "SELECT c.relname AS TABLE_NAME, " +
                "COALESCE(obj_description(c.oid, 'pg_class'), '') AS TABLE_COMMENT " +
                "FROM pg_class c " +
                "JOIN pg_namespace n ON c.relnamespace = n.oid " +
                "WHERE n.nspname = '" + targetSchema + "' AND c.relkind = 'r' " +
                "ORDER BY c.relname";
        List<Map<String, Object>> tables = jdbcTemplate.queryForList(tableSql);

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

        return buildTableSchemaResult(tables, columnsByTable, pkMap);
    }

    // ==================== 通用辅助方法 ====================

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

    private String getStringValue(Map<String, Object> row, String key) {
        Object val = row.get(key);
        if (val == null) return "";
        return val.toString();
    }
}
