package com.example.multidb.common;

import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLException;

/**
 * 全局异常处理器
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 参数校验异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public R<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数错误：{}", e.getMessage());
        return R.error(e.getMessage());
    }

    /**
     * SQL异常
     */
    @ExceptionHandler(SQLException.class)
    public R<Void> handleSQLException(SQLException e) {
        log.error("SQL执行异常", e);
        return R.error("SQL执行失败：" + e.getMessage());
    }

    /**
     * 请求体格式错误
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public R<Void> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体格式错误：{}", e.getMessage());
        return R.error("请求体格式错误，请检查JSON格式");
    }

    /**
     * 其他未预期异常
     */
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        log.error("未预期异常", e);
        return R.error("服务器内部错误：" + e.getMessage());
    }
}
