package com.example.multidb.config;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * openGauss 到 SQLite 的表名映射配置。
 * 两个列表下标一一对应：openGaussTables.get(i) -> sqliteTables.get(i)
 */
@Component
public class TargetTable {

    private final List<String> openGaussTables = new ArrayList<>();
    private final List<String> sqliteTables = new ArrayList<>();

    public TargetTable() {
        // 示例映射：q_a_user -> user
        addMapping("q_a_user", "user");
        addMapping("q_a_order", "order");
    }

    private void addMapping(String openGaussTable, String sqliteTable) {
        openGaussTables.add(openGaussTable);
        sqliteTables.add(sqliteTable);
    }

    public List<String> getOpenGaussTables() {
        return Collections.unmodifiableList(openGaussTables);
    }

    public List<String> getSqliteTables() {
        return Collections.unmodifiableList(sqliteTables);
    }

    /**
     * 根据 openGauss 表名查找对应的 SQLite 表名，找不到返回 null
     */
    public String findSqliteTableName(String openGaussTable) {
        int index = openGaussTables.indexOf(openGaussTable);
        if (index >= 0 && index < sqliteTables.size()) {
            return sqliteTables.get(index);
        }
        return null;
    }

    /**
     * 根据 SQLite 表名反查对应的 openGauss 表名，找不到返回 null
     */
    public String findOpenGaussTableName(String sqliteTable) {
        int index = sqliteTables.indexOf(sqliteTable);
        if (index >= 0 && index < openGaussTables.size()) {
            return openGaussTables.get(index);
        }
        return null;
    }
}
