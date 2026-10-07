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

import com.golf.course.dto.StartCheckView;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务校验失败：400 + 中文业务提示
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Object>> handleBiz(BizException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(e.getMessage()));
    }

    /**
     * 开赛校验未通过：400 + 逐洞阻断明细。响应体 data 带按最新球洞版本重算的校验视图
     * （受影响洞号 / 当前状态 / 修复提示 / 当前赛事版本），前端直接渲染阻止面板，无需再次请求。
     */
    @ExceptionHandler(StartBlockedException.class)
    public ResponseEntity<ApiResponse<StartCheckView>> handleStartBlocked(StartBlockedException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse<>(false, e.getMessage(), e.view()));
    }

    /**
     * 并发冲突（页面版本过期）：409。前端必须明确提示「数据已变化」并展示当前状态，
     * 不能让后到的写静默覆盖先到者的结果。
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<Object>> handleConflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail(e.getMessage()));
    }

    /**
     * 开赛 / 维护球洞的并发冲突：409 + 当前赛事与球洞状态（校验视图），
     * 前端据此提示「数据已变化」并把页面刷新到返回的最新状态。
     */
    @ExceptionHandler(StartConflictException.class)
    public ResponseEntity<ApiResponse<StartCheckView>> handleStartConflict(StartConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiResponse<>(false, e.getMessage(), e.view()));
    }

    /**
     * JPA 乐观锁兜底：即使漏传 expectedVersion，@Version 也会在提交时拦住并发覆盖，统一转 409。
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail(
                "数据已变化：他人已先一步开赛 / 推进收口 / 维护球洞或改动成绩，你的页面版本已过期，本次操作未生效；请重新读取当前赛事与球洞（排行榜）状态后再试"));
    }

    /**
     * 悲观行锁争用兜底：两人同时开赛、同时维护同一球洞、或开赛与收口 / 录成绩并发时，
     * 后到者在赛事行锁 / 球洞行锁上等待超时——只有基于最新版本的一次能成功，其余一律 409 并要求重读。
     */
    @ExceptionHandler(CannotAcquireLockException.class)
    public ResponseEntity<ApiResponse<Void>> handleLockContention(CannotAcquireLockException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail(
                "赛事 / 球洞正被他人开赛、维护或录入成绩，当前状态已变化，本次操作未生效；请重新读取赛事与球洞（排行榜）状态后再试"));
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
