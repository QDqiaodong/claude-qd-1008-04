package com.golf.course.common;

import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务校验失败：400 + 中文业务提示
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(e.getMessage()));
    }

    /**
     * 并发冲突（页面版本过期）：409。前端必须明确提示「数据已变化」并展示当前状态，
     * 不能让后到的写静默覆盖先到者的结果。
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail(e.getMessage()));
    }

    /**
     * JPA 乐观锁兜底：即使漏传 expectedVersion，@Version 也会在提交时拦住并发覆盖，统一转 409。
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail(
                "赛事数据已变化：他人已先一步推进收口 / 改动成绩，你的页面版本已过期，本次操作未生效；请重新读取当前排行榜与赛事状态后再试"));
    }

    /**
     * 悲观行锁争用兜底：两人同时推进赛事收口、或收口与补录成绩并发时，
     * 后到者在赛事行锁上等待超时——只有一个版本能成为正式快照，其余一律 409 并要求重读。
     */
    @ExceptionHandler(CannotAcquireLockException.class)
    public ResponseEntity<ApiResponse<Void>> handleLockContention(CannotAcquireLockException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail(
                "赛事正被他人推进收口或录入成绩，当前状态已变化，本次操作未生效；请重新读取排行榜与赛事状态后再试"));
    }

    /**
     * 参数注解校验失败：400 + 字段名与提示
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(message.isEmpty() ? "请求参数校验失败" : message));
    }

    /**
     * 请求体格式错误：400
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail("请求体格式有误，请检查 JSON 字段类型"));
    }

    /**
     * 唯一约束冲突：400（避免在页面上抛出非人话的外键报错）
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleIntegrity(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail("存在唯一性约束冲突，请检查编号、手机号是否重复"));
    }

    /**
     * 兜底：500
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.fail("服务异常：" + detail));
    }
}
