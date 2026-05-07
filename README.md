# 多数据库SQL查询工具

这是一个封装了多数据库SQL查询的Spring Boot Maven项目，支持MySQL、Oracle、OceanBase、openGauss、**SQLite** 五种数据库，通过传入数据库名称和SQL语句，即可返回对应数据库的查询结果。

## 功能特点
1. 支持MySQL、Oracle、OceanBase、openGauss、SQLite五种数据库的动态切换
2. SQLite内存数据库模式：传入标准SQLite SQL，自动从openGauss同步数据到内存沙箱，查询后自动清理，用完即焚
3. SQLite自定义函数：`MonthsBetween`（整数月份差）和 `MonthsBetweenWithDB`（小数精确月份差，兼容Oracle `MONTHS_BETWEEN`）
4. 基于 JSqlParser 的SQL安全校验与表名提取，仅允许 SELECT/WITH 只读查询
5. 通用的查询结果返回，自动适配不同数据库的返回格式
6. 提供Java服务接口和HTTP REST接口两种调用方式
7. 统一的异常处理和日志记录
8. 连接池优化与LIMIT 5000行数限制，防止OOM和连接泄漏

## 项目结构
```
multi-db-sql-query/
├── pom.xml                          # Maven依赖配置
├── README.md                        # 项目说明
└── src/
    └── main/
        ├── java/
        │   └── com/
        │       └── example/
        │           └── multidb/
        │               ├── MultiDbSqlQueryApplication.java  # 启动类
        │               ├── common/
        │               │   ├── R.java                      # 统一返回结果
        │               │   └── GlobalExceptionHandler.java  # 全局异常处理
        │               ├── config/
        │               │   ├── JdbcConfig.java              # JDBC配置
        │               │   └── TargetTable.java             # openGauss↔SQLite表名映射
        │               ├── dto/
        │               │   ├── SqlQueryRequest.java         # 请求DTO
        │               │   └── TableSchemaRequest.java      # 表结构查询DTO
        │               ├── service/
        │               │   └── SqlQueryService.java         # 核心查询服务
        │               └── controller/
        │                   └── SqlQueryController.java      # HTTP接口控制器
        └── resources/
            └── application.yml  # 数据源配置文件
```

## 配置说明
修改 `src/main/resources/application.yml` 文件，配置你的数据库连接信息：

### 1. MySQL配置
```yaml
mysql:
  driver-class-name: com.mysql.cj.jdbc.Driver
  url: jdbc:mysql://你的MySQL地址:端口/数据库名?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false
  username: 你的MySQL用户名
  password: 你的MySQL密码
```

### 2. Oracle配置
```yaml
oracle:
  driver-class-name: oracle.jdbc.OracleDriver
  url: jdbc:oracle:thin:@//你的Oracle地址:端口/服务名
  username: 你的Oracle用户名
  password: 你的Oracle密码
```

### 3. OceanBase配置
```yaml
oceanbase:
  driver-class-name: com.oceanbase.jdbc.Driver
  url: jdbc:oceanbase://你的OceanBase地址:端口/数据库名?useUnicode=true&characterEncoding=utf8&useSSL=false
  username: 用户名@租户名  # 例如 root@test
  password: 你的OceanBase密码
```

### 4. openGauss配置
```yaml
opengauss:
  driver-class-name: org.opengauss.Driver
  url: jdbc:postgresql://你的openGauss地址:端口/数据库名?useUnicode=true&characterEncoding=utf8
  username: 你的openGauss用户名
  password: 你的openGauss密码
```

### 5. SQLite配置（内存模式）
```yaml
sqlite:
  driver-class-name: org.sqlite.JDBC
  url: jdbc:sqlite:file::memory:?cache=shared
```

## 使用方式

### 1. Java接口调用
直接注入 `SqlQueryService` 即可调用：
```java
@Autowired
private SqlQueryService sqlQueryService;

// 调用MySQL查询
List<Map<String, Object>> mysqlResult = sqlQueryService.executeQuery("mysql", "select * from user limit 10");

// 调用Oracle查询
List<Map<String, Object>> oracleResult = sqlQueryService.executeQuery("oracle", "select * from user where rownum <= 10");

// 调用OceanBase查询
List<Map<String, Object>> obResult = sqlQueryService.executeQuery("oceanbase", "select * from user limit 10");

// 调用openGauss查询
List<Map<String, Object>> ogResult = sqlQueryService.executeQuery("opengauss", "select * from user limit 10");

// 调用SQLite内存数据库查询（传入标准SQLite SQL）
List<Map<String, Object>> sqliteResult = sqlQueryService.executeQuery("sqlite", "select * from user where id = 1");
```

### 2. HTTP接口调用
启动项目后，通过POST请求调用 `http://localhost:8080/api/sql/query`

请求示例：
```json
{
    "dbName": "mysql",
    "sql": "select * from user limit 10"
}
```

响应示例：
```json
{
    "code": 200,
    "msg": "success",
    "data": [
        {
            "id": 1,
            "name": "张三",
            "age": 20
        },
        {
            "id": 2,
            "name": "李四",
            "age": 22
        }
    ]
}
```

### 3. 获取数据库表结构元数据
通过POST请求调用 `http://localhost:8080/api/sql/schema`

请求示例：
```json
{
    "dbName": "mysql",
    "schema": ""
}
```

## SQLite 内存沙箱模式

当 `dbName` 传 `"sqlite"` 时，系统进入内存沙箱模式，处理流程如下：

```
传入SQLite标准SQL（如 select * from user where id = 1）
  │
  ▼
① JSqlParser 解析SQL → 安全校验 + 提取表名 {"user"}
  │
  ▼
② 通过 TargetTable 配置反查 openGauss 表名（user → q_a_user）
  │
  ▼
③ 从 openGauss 同步表结构+数据到SQLite内存数据库（LIMIT 5000，防OOM）
  │
  ▼
④ 在SQLite内存数据库中执行原始SQL，返回结果
  │
  ▼
⑤ finally 块清理本次产生的临时表，防止内存泄漏
```

### 表名映射配置
在 `TargetTable.java` 中配置 openGauss 与 SQLite 的表名映射：

```java
public TargetTable() {
    // openGauss表名 → SQLite表名
    addMapping("q_a_user", "user");
    addMapping("q_a_order", "order");
}
```

### SQLite自定义函数
| 函数名 | 参数 | 返回值 | 说明 |
|---|---|---|---|
| `MonthsBetween(date1, date2)` | 日期字符串 | 整数 | 完整月份差，类似 `ChronoUnit.MONTHS.between()` |
| `MonthsBetweenWithDB(date1, date2)` | 日期字符串 | 小数 | 模拟Oracle `MONTHS_BETWEEN`，返回带小数的精确值 |

支持的日期格式：`yyyy-MM-dd`、`yyyy/MM/dd`、`yyyyMMdd`、`yyyy-MM-dd HH:mm:ss`、`yyyy-MM-dd HH:mm:ss.SSS` 等。

## 打包运行
1. 打包：`mvn clean package -DskipTests`
2. 运行：`java -jar target/multi-db-sql-query-0.0.1-SNAPSHOT.jar`

## 注意事项
1. 该接口仅支持查询语句（SELECT/WITH），不支持增删改操作
2. 请确保传入的SQL语句是合法的，注意SQL注入风险
3. 数据源名称必须和配置文件中的一致，否则会抛出异常
4. 执行完查询后会自动清除数据源上下文，不会影响后续请求
5. SQLite内存数据同步每次最多拉取5000行，防止OOM
6. SQLite查询为串行执行（synchronized），防止并发沙箱踩踏
