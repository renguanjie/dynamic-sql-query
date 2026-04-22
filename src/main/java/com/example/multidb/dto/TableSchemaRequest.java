package com.example.multidb.dto;

/**
 * 数据库表结构元数据查询请求
 */
public class TableSchemaRequest {
    /**
     * 数据源名称，对应配置文件中定义的数据源：mysql/oracle/oceanbase/opengauss
     */
    private String dbName;
    /**
     * Schema名称，MySQL/OceanBase可传null（忽略），Oracle传用户名，openGauss传public
     */
    private String schema;

    public String getDbName() {
        return dbName;
    }

    public void setDbName(String dbName) {
        this.dbName = dbName;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }
}
