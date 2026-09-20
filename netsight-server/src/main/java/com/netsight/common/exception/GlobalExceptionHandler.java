package com.netsight.common.exception;

import com.netsight.common.core.R;
import com.netsight.common.core.ResultCode;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * 统一捕获业务异常、参数校验异常、权限异常、系统异常，转换为统一返回格式
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常
     */
    @ExceptionHandler(ServiceException.class)
    public R<Void> handleServiceException(ServiceException e) {
        log.warn("业务异常: code={}, msg={}", e.getCode(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    /**
     * 参数校验异常（@RequestBody 实体校验）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", msg);
        return R.fail(ResultCode.PARAM_ERROR.getCode(), msg);
    }

    /**
     * 参数绑定异常（表单绑定）
     */
    @ExceptionHandler(BindException.class)
    public R<Void> handleBindException(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数绑定失败: {}", msg);
        return R.fail(ResultCode.PARAM_ERROR.getCode(), msg);
    }

    /**
     * 单个参数校验异常（@RequestParam + @Validated）
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public R<Void> handleConstraintViolation(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(v -> v.getMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", msg);
        return R.fail(ResultCode.PARAM_ERROR.getCode(), msg);
    }

    /**
     * 必填请求头缺失（如网关/Webhook 鉴权头），按认证失败处理而非 500
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public R<Void> handleMissingRequestHeader(MissingRequestHeaderException e) {
        log.warn("缺失必填请求头 {}: {}", e.getHeaderName(), e.getMessage());
        return R.fail(ResultCode.UNAUTHORIZED);
    }

    /**
     * 认证失败异常
     */
    @ExceptionHandler(BadCredentialsException.class)
    public R<Void> handleBadCredentials(BadCredentialsException e) {
        log.warn("认证失败: {}", e.getMessage());
        return R.fail(ResultCode.UNAUTHORIZED);
    }

    /**
     * 权限不足异常
     */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public R<Void> handleAccessDenied(AccessDeniedException e) {
        log.warn("权限不足: {}", e.getMessage());
        return R.fail(ResultCode.FORBIDDEN);
    }

    /**
     * 请求体 JSON 解析失败（格式错误 / 类型不匹配）——友好提示，避免暴露堆栈
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public R<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return R.fail(ResultCode.PARAM_ERROR.getCode(), "请求体格式错误或字段类型不匹配，请检查后重试");
    }

    /**
     * 唯一约束冲突（编码 / 编号 / 手机号等重复）——提取冲突键友好提示
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public R<Void> handleDuplicateKey(DuplicateKeyException e) {
        String msg = e.getMostSpecificCause() == null ? e.getMessage() : e.getMostSpecificCause().getMessage();
        log.warn("唯一约束冲突: {}", msg);
        // 提取形如 'xxx' 的重复键值（MySQL 错误信息中通常带单引号包裹的键值/键名）
        String friendly = "数据重复，请检查唯一性字段（编码/编号/手机号等）";
        if (msg != null && msg.indexOf('\'') >= 0 && msg.lastIndexOf('\'') > msg.indexOf('\'')) {
            String key = msg.substring(msg.indexOf('\'') + 1, msg.lastIndexOf('\''));
            if (key.length() <= 64) {
                friendly = "数据重复：与已存在的【" + key + "】冲突，请修改后重试";
            }
        }
        return R.fail(ResultCode.PARAM_ERROR.getCode(), friendly);
    }

    /**
     * 系统未知异常兜底
     */
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return R.fail(ResultCode.ERROR);
    }
}
