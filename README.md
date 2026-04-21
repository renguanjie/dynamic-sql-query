# 多数据库SQL查询工具

这是一个封装了多数据库SQL查询的Spring Boot Maven项目，支持MySQL、Oracle、OceanBase、openGauss四种数据库，通过传入数据库名称和SQL语句，即可返回对应数据库的查询结果。

## 功能特点
1. 支持MySQL、Oracle、OceanBase、openGauss四种数据库的动态切换
2. 通用的查询结果返回，自动适配不同数据库的返回格式
3. 提供Java服务接口和HTTP REST接口两种调用方式
4. 统一的异常处理和日志记录

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
        │               ├── dto/
        │               │   └── SqlQueryRequest.java         # 请求DTO
        │               ├── service/
        │               │   └── SqlQueryService.java         # 核心查询服务
        │               └── controller/
        │                   └── SqlQueryController.java       # HTTP接口控制器
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

## 打包运行
1. 打包：`mvn clean package -DskipTests`
2. 运行：`java -jar target/multi-db-sql-query-0.0.1-SNAPSHOT.jar`

## 注意事项
1. 该接口仅支持查询语句（SELECT），如果需要执行增删改操作，请自行扩展
2. 请确保传入的SQL语句是合法的，并且注意SQL注入风险，不要传入未过滤的用户输入
3. 数据源名称必须和配置文件中的一致，否则会抛出异常
4. 执行完查询后会自动清除数据源上下文，不会影响后续请求