// src/main/java/com/example/forklift_erp/handler/GlobalExceptionHandler.java
package com.example.forklift_erp.handler;

import com.example.forklift_erp.common.Result;
import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PessimisticLockException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

// 注意：使用 jakarta.validation.ConstraintViolationException
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(resolveHttpStatus(e.getCode()))
                .body(Result.error(e.getCode(), e.getMessage()));
    }

    private HttpStatus resolveHttpStatus(Integer code) {
        if (ResultCode.UNAUTHORIZED.getCode().equals(code)) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (ResultCode.FORBIDDEN.getCode().equals(code)) {
            return HttpStatus.FORBIDDEN;
        }
        if (ResultCode.NOT_FOUND.getCode().equals(code)
                || ResultCode.DATA_NOT_FOUND.getCode().equals(code)
                || ResultCode.VEHICLE_NOT_FOUND.getCode().equals(code)
                || ResultCode.PART_NOT_FOUND.getCode().equals(code)) {
            return HttpStatus.NOT_FOUND;
        }
        if (ResultCode.DATA_DUPLICATE.getCode().equals(code)
                || ResultCode.CONFLICT.getCode().equals(code)
                || ResultCode.CONFIG_IN_USE.getCode().equals(code)
                || ResultCode.INSUFFICIENT_STOCK.getCode().equals(code)) {
            return HttpStatus.CONFLICT;
        }
        if (ResultCode.VALIDATE_ERROR.getCode().equals(code)
                || ResultCode.PARAM_ERROR.getCode().equals(code)) {
            return HttpStatus.BAD_REQUEST;
        }
        if (ResultCode.SYSTEM_ERROR.getCode().equals(code)
                || ResultCode.ERROR.getCode().equals(code)) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return HttpStatus.BAD_REQUEST;
    }

    /**
     * 处理方法级权限不足，例如 @PreAuthorize 拦截。
     */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleAccessDeniedException(AccessDeniedException e) {
        log.warn("权限不足: {}", e.getMessage());
        return Result.error(ResultCode.FORBIDDEN, "无权限访问");
    }

    /**
     * 处理参数校验异常（@Valid用于方法参数）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("参数校验失败: {}", message);
        return Result.error(ResultCode.VALIDATE_ERROR, message);
    }

    /**
     * 处理参数绑定异常（@Valid用于表单绑定）
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("参数绑定失败: {}", message);
        return Result.error(ResultCode.VALIDATE_ERROR, message);
    }

    /**
     * 处理参数校验异常（@Validated用于单个参数或方法级校验）
     * 注意：导入的是 jakarta.validation.ConstraintViolationException
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleConstraintViolationException(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining(", "));
        log.warn("约束校验失败: {}", message);
        return Result.error(ResultCode.VALIDATE_ERROR, message);
    }

    /**
     * 处理缺少请求参数
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMissingServletRequestParameterException(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数: {}", e.getMessage());
        return Result.error(ResultCode.PARAM_ERROR, "缺少必要参数: " + e.getParameterName());
    }

    /**
     * 处理参数类型不匹配
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不匹配: {}", e.getMessage());
        return Result.error(ResultCode.PARAM_ERROR, "参数类型错误: " + e.getName());
    }

    /**
     * 处理请求体不可读
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.warn("请求体不可读: {}", e.getMessage());
        return Result.error(ResultCode.PARAM_ERROR, "请求体格式错误或缺少请求体");
    }

    /**
     * 处理请求方法不支持
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<Void> handleHttpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持: {}", e.getMessage());
        return Result.error(ResultCode.METHOD_NOT_ALLOWED);
    }

    /**
     * 处理不支持的媒体类型
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public Result<Void> handleHttpMediaTypeNotSupportedException(HttpMediaTypeNotSupportedException e) {
        log.warn("不支持的媒体类型: {}", e.getMessage());
        return Result.error(ResultCode.PARAM_ERROR, "不支持的Content-Type");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        log.warn("上传文件过大: {}", e.getMessage());
        return Result.error(ResultCode.PARAM_ERROR, "上传文件不能超过20MB");
    }

    /**
     * 处理404
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoHandlerFoundException(NoHandlerFoundException e) {
        log.warn("请求地址不存在: {}", e.getMessage());
        return Result.error(ResultCode.NOT_FOUND);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResourceFoundException(NoResourceFoundException e) {
        log.warn("请求资源不存在: {}", e.getMessage());
        return Result.error(ResultCode.NOT_FOUND, "接口或资源不存在，请确认后端已更新并重启");
    }

    /**
     * 处理数据完整性异常（如唯一约束冲突）
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Void> handleDataIntegrityViolationException(DataIntegrityViolationException e) {
        log.warn("Database constraint rejected a write");
        if (e.getMessage() != null && e.getMessage().contains("Duplicate entry")) {
            return Result.error(ResultCode.DATA_DUPLICATE, "数据已存在，请勿重复添加");
        }
        return Result.error(ResultCode.CONFLICT,
                "Data conflicts with existing references or database constraints");
    }

    /**
     * 处理其他未知异常
     */
    @ExceptionHandler({
            ObjectOptimisticLockingFailureException.class,
            OptimisticLockException.class,
            PessimisticLockException.class,
            CannotAcquireLockException.class
    })
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Void> handleConcurrentWriteException(Exception e) {
        log.warn("并发写入冲突: {}", e.getMessage());
        return Result.error(ResultCode.CONFLICT, "数据正在被其他用户修改，请刷新后重试");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常: ", e);
        return Result.error(ResultCode.SYSTEM_ERROR);
    }
}
