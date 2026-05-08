package com.example.multidb.dto;

/**
 * SQL查询请求DTO
 */
public class SqlQueryRequest {
    /**
     * 数据库名称，对应配置文件中的数据源名称：mysql/oracle/oceanbase
     */
    private String dbName;
    /**
     * 要执行的SQL查询语句
     */
    private String sql;
    /**
     * 可选的模式名（仅Oracle/openGauss有效），不传则使用数据源配置的默认模式
     */
    private String schema;

    public String getDbName() {
        return dbName;
    }

    public void setDbName(String dbName) {
        this.dbName = dbName;
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }
}
